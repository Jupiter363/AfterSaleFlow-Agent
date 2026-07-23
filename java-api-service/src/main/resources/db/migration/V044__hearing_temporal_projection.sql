-- Additive Hearing authority projection and Java-committed receipt ledger.
-- case_room_epoch remains the only epoch/revision/fence/writer authority.

create function hearing_flow_stage_sequence_v2(stage_code text)
returns integer
language sql
immutable
strict
as $$
    select case stage_code
        when 'COURT_PREPARING' then 1
        when 'CASE_INTRODUCTION' then 2
        when 'EVIDENCE_INTRODUCTION' then 3
        when 'INTAKE_QUESTIONS_GENERATING' then 4
        when 'PARTY_ANSWERS_OPEN' then 5
        when 'INTAKE_SYNTHESIZING' then 6
        when 'EVIDENCE_REQUESTS_GENERATING' then 7
        when 'PARTY_EVIDENCE_OPEN' then 8
        when 'EVIDENCE_SYNTHESIZING' then 9
        when 'DOSSIER_FREEZING' then 10
        when 'JUDGE_V1_GENERATING' then 11
        when 'JURY_REVIEWING' then 12
        when 'JUDGE_V2_GENERATING' then 13
        when 'HUMAN_REVIEW_OPEN' then 14
        when 'CLOSED' then 15
        else null
    end
$$;

create table hearing_temporal_projection (
    flow_instance_id varchar(64) primary key,
    case_id varchar(64) not null,
    schema_version varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    epoch_id varchar(64) not null,
    room_type varchar(32) not null,
    hearing_epoch bigint not null,
    writer_mode varchar(16) not null,
    process_revision bigint not null,
    room_revision bigint not null,
    fencing_token bigint not null,
    current_stage varchar(64) not null,
    stage_sequence integer not null,
    stage_deadline_at timestamptz,
    temporal_namespace varchar(128),
    temporal_workflow_id varchar(128),
    temporal_run_id varchar(128),
    temporal_build_or_deployment varchar(128) not null,
    last_acknowledged_receipt_id varchar(64),
    last_acknowledged_receipt_hash varchar(64),
    last_acknowledged_history_event_id bigint,
    projected_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint uq_hearing_temporal_projection_flow_case
        unique (flow_instance_id, case_id),
    constraint fk_hearing_temporal_projection_flow
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id),
    constraint fk_hearing_temporal_projection_epoch
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, hearing_epoch, fencing_token
        ) references case_room_epoch(
            id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint ck_hearing_temporal_projection_schema
        check (schema_version = 'hearing-stage-projection.v1'),
    constraint ck_hearing_temporal_projection_authority
        check (
            room_type = 'HEARING'
            and writer_mode in ('LEGACY', 'SHADOW', 'TEMPORAL')
            and hearing_epoch >= 0
            and process_revision >= 0
            and room_revision >= 0
            and fencing_token >= 0
        ),
    constraint ck_hearing_temporal_projection_stage
        check (hearing_flow_stage_sequence_v2(current_stage) = stage_sequence),
    constraint ck_hearing_temporal_projection_deadline
        check (
            (current_stage in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and stage_deadline_at is not null)
            or
            (current_stage not in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and stage_deadline_at is null)
        ),
    constraint ck_hearing_temporal_projection_execution
        check (
            length(btrim(temporal_build_or_deployment)) between 1 and 128
            and (
                (writer_mode = 'LEGACY'
                    and temporal_namespace is null
                    and temporal_workflow_id is null
                    and temporal_run_id is null)
                or
                (writer_mode = 'SHADOW'
                    and length(btrim(temporal_namespace)) between 1 and 128
                    and length(btrim(temporal_workflow_id)) between 1 and 128
                    and fencing_token > 0)
                or
                (writer_mode = 'TEMPORAL'
                    and length(btrim(temporal_namespace)) between 1 and 128
                    and length(btrim(temporal_workflow_id)) between 1 and 128
                    and length(btrim(temporal_run_id)) between 1 and 128
                    and fencing_token > 0)
            )
        ),
    constraint ck_hearing_temporal_projection_receipt
        check (
            (last_acknowledged_receipt_id is null
                and last_acknowledged_receipt_hash is null)
            or
            (length(btrim(last_acknowledged_receipt_id)) between 1 and 64
                and last_acknowledged_receipt_hash ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_hearing_temporal_projection_history
        check (last_acknowledged_history_event_id is null
            or last_acknowledged_history_event_id > 0),
    constraint ck_hearing_temporal_projection_time
        check (updated_at >= projected_at)
);

create index idx_hearing_temporal_projection_active
    on hearing_temporal_projection(writer_mode, current_stage, updated_at);

create index idx_hearing_temporal_projection_reconcile
    on hearing_temporal_projection(epoch_id, process_revision, room_revision, fencing_token);

do $$
begin
    if exists (
        select 1
          from hearing_flow_instance flow
         where not exists (
            select 1
              from case_room_epoch epoch
             where epoch.case_id = flow.case_id
               and epoch.room_type = 'HEARING'
               and epoch.writer_mode = 'LEGACY'
         )
    ) then
        raise exception using
            errcode = '23514',
            message = 'V044 cannot bind a historical Hearing flow without a LEGACY case_room_epoch';
    end if;
end
$$;

insert into hearing_temporal_projection (
    flow_instance_id, case_id, schema_version, tenant_surrogate, epoch_id,
    room_type, hearing_epoch, writer_mode, process_revision, room_revision,
    fencing_token, current_stage, stage_sequence, stage_deadline_at,
    temporal_namespace, temporal_workflow_id, temporal_run_id,
    temporal_build_or_deployment, projected_at, updated_at
)
select
    flow.id,
    flow.case_id,
    'hearing-stage-projection.v1',
    epoch.tenant_surrogate,
    epoch.id,
    'HEARING',
    epoch.room_epoch,
    'LEGACY',
    epoch.process_revision,
    epoch.room_revision,
    epoch.fencing_token,
    flow.current_stage,
    flow.stage_sequence,
    flow.shared_deadline_at,
    null,
    null,
    null,
    coalesce(epoch.room_workflow_build_id, epoch.temporal_build_id),
    flow.created_at,
    greatest(flow.updated_at, flow.created_at)
from hearing_flow_instance flow
join lateral (
    select candidate.*
      from case_room_epoch candidate
     where candidate.case_id = flow.case_id
       and candidate.room_type = 'HEARING'
       and candidate.writer_mode = 'LEGACY'
     order by candidate.room_epoch desc, candidate.id
     limit 1
) epoch on true;

create table hearing_domain_receipt (
    schema_version varchar(64) not null,
    receipt_id varchar(64) primary key,
    receipt_hash varchar(64) not null,
    operation_type varchar(32) not null,
    operation_key varchar(512) not null,
    request_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    flow_instance_id varchar(64) not null,
    epoch_id varchar(64) not null,
    room_type varchar(32) not null,
    hearing_epoch bigint not null,
    writer_mode varchar(16) not null,
    fencing_token bigint not null,
    source_stage varchar(64) not null,
    source_stage_sequence integer not null,
    source_process_revision bigint not null,
    source_room_revision bigint not null,
    stage_code varchar(64) not null,
    stage_sequence integer not null,
    stage_deadline_at timestamptz,
    process_revision bigint not null,
    room_revision bigint not null,
    result_ref varchar(1024) not null,
    result_hash varchar(64) not null,
    committed_event_sequence bigint not null,
    temporal_namespace varchar(128),
    temporal_workflow_id varchar(128),
    temporal_run_id varchar(128),
    temporal_build_or_deployment varchar(128) not null,
    temporal_history_event_id bigint,
    committed_at timestamptz not null,
    constraint uq_hearing_domain_receipt_id_hash unique (receipt_id, receipt_hash),
    constraint uq_hearing_domain_receipt_operation unique (tenant_surrogate, operation_key),
    constraint uq_hearing_domain_receipt_event unique (case_id, committed_event_sequence),
    constraint fk_hearing_domain_receipt_projection
        foreign key (flow_instance_id, case_id)
        references hearing_temporal_projection(flow_instance_id, case_id),
    constraint fk_hearing_domain_receipt_epoch
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, hearing_epoch, fencing_token
        ) references case_room_epoch(
            id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint ck_hearing_domain_receipt_schema
        check (schema_version = 'hearing-domain-receipt.v1'),
    constraint ck_hearing_domain_receipt_identity
        check (
            room_type = 'HEARING'
            and writer_mode in ('LEGACY', 'TEMPORAL')
            and operation_key ~ '^hearing[.](stage|party|agent|finalize|handoff|close)'
            and request_hash ~ '^[0-9a-f]{64}$'
            and receipt_hash ~ '^[0-9a-f]{64}$'
            and result_hash ~ '^[0-9a-f]{64}$'
            and result_ref ~ '^(urn|s3|minio):'
        ),
    constraint ck_hearing_domain_receipt_operation
        check (operation_type in (
            'STAGE', 'PARTY_TERMINAL', 'AGENT_RESULT', 'FINALIZE', 'HANDOFF', 'CLOSE'
        )),
    constraint ck_hearing_domain_receipt_authority
        check (
            hearing_epoch >= 0
            and fencing_token >= 0
            and source_process_revision >= 0
            and source_room_revision >= 0
            and process_revision = source_process_revision + 1
            and room_revision = source_room_revision + 1
        ),
    constraint ck_hearing_domain_receipt_stage
        check (
            hearing_flow_stage_sequence_v2(source_stage) = source_stage_sequence
            and hearing_flow_stage_sequence_v2(stage_code) = stage_sequence
            and stage_sequence in (source_stage_sequence, source_stage_sequence + 1)
            and (
                (stage_code in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                    and stage_deadline_at is not null)
                or
                (stage_code not in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                    and stage_deadline_at is null)
            )
        ),
    constraint ck_hearing_domain_receipt_event
        check (
            committed_event_sequence > 0
            and (temporal_history_event_id is null or temporal_history_event_id > 0)
        ),
    constraint ck_hearing_domain_receipt_execution
        check (
            length(btrim(temporal_build_or_deployment)) between 1 and 128
            and (
                (writer_mode = 'LEGACY'
                    and temporal_namespace is null
                    and temporal_workflow_id is null
                    and temporal_run_id is null)
                or
                (writer_mode = 'TEMPORAL'
                    and length(btrim(temporal_namespace)) between 1 and 128
                    and length(btrim(temporal_workflow_id)) between 1 and 128
                    and length(btrim(temporal_run_id)) between 1 and 128
                    and fencing_token > 0)
            )
        )
);

create index idx_hearing_domain_receipt_flow_stage
    on hearing_domain_receipt(flow_instance_id, stage_sequence, committed_at);

create index idx_hearing_domain_receipt_reconcile
    on hearing_domain_receipt(epoch_id, process_revision, room_revision, fencing_token);

create trigger trg_hearing_domain_receipt_append_only
    before update or truncate on hearing_domain_receipt
    for each statement execute function reject_append_only_mutation();

create trigger trg_hearing_domain_receipt_delete_append_only
    before delete on hearing_domain_receipt
    for each row execute function reject_append_only_mutation();

create function enforce_hearing_temporal_projection_authority()
returns trigger
language plpgsql
as $$
declare
    authority case_room_epoch%rowtype;
    flow hearing_flow_instance%rowtype;
    authority_workflow_id text;
    authority_run_id text;
    authority_build_id text;
begin
    select epoch.*
      into authority
      from case_room_epoch epoch
     where epoch.id = new.epoch_id
       and epoch.tenant_surrogate = new.tenant_surrogate
       and epoch.case_id = new.case_id
       and epoch.room_type = 'HEARING'
       and epoch.room_epoch = new.hearing_epoch
       and epoch.fencing_token = new.fencing_token;
    if not found then
        raise exception using errcode = '23503',
            message = 'Hearing projection has no exact case_room_epoch authority';
    end if;

    select instance.*
      into flow
      from hearing_flow_instance instance
     where instance.id = new.flow_instance_id
       and instance.case_id = new.case_id;
    if not found then
        raise exception using errcode = '23503',
            message = 'Hearing projection has no exact V035 flow';
    end if;

    authority_workflow_id := coalesce(
        authority.room_temporal_workflow_id, authority.temporal_workflow_id);
    authority_run_id := coalesce(
        authority.room_temporal_run_id, authority.temporal_run_id);
    authority_build_id := coalesce(
        authority.room_workflow_build_id, authority.temporal_build_id);

    if new.writer_mode <> authority.writer_mode
       or new.process_revision <> authority.process_revision
       or new.room_revision <> authority.room_revision
       or new.temporal_workflow_id is distinct from authority_workflow_id
       or new.temporal_run_id is distinct from authority_run_id
       or new.temporal_build_or_deployment is distinct from authority_build_id then
        raise exception using errcode = '23514',
            message = 'Hearing projection does not match current case_room_epoch authority';
    end if;

    if new.current_stage <> flow.current_stage
       or new.stage_sequence <> flow.stage_sequence
       or new.stage_deadline_at is distinct from flow.shared_deadline_at then
        raise exception using errcode = '23514',
            message = 'Hearing projection does not match the Java V035 formal cursor';
    end if;

    if new.last_acknowledged_receipt_id is not null
       and not exists (
            select 1
              from hearing_domain_receipt receipt
             where receipt.receipt_id = new.last_acknowledged_receipt_id
               and receipt.receipt_hash = new.last_acknowledged_receipt_hash
               and receipt.flow_instance_id = new.flow_instance_id
               and receipt.case_id = new.case_id
               and receipt.epoch_id = new.epoch_id
               and receipt.process_revision = new.process_revision
               and receipt.room_revision = new.room_revision
               and receipt.fencing_token = new.fencing_token
       ) then
        raise exception using errcode = '23514',
            message = 'Hearing projection receipt acknowledgement is not exact';
    end if;

    if tg_op = 'UPDATE' then
        if new.epoch_id <> old.epoch_id
           or new.tenant_surrogate <> old.tenant_surrogate
           or new.case_id <> old.case_id
           or new.hearing_epoch <> old.hearing_epoch
           or new.writer_mode <> old.writer_mode
           or new.fencing_token <> old.fencing_token
           or new.temporal_namespace is distinct from old.temporal_namespace
           or new.temporal_workflow_id is distinct from old.temporal_workflow_id
           or new.temporal_run_id is distinct from old.temporal_run_id
           or new.temporal_build_or_deployment <> old.temporal_build_or_deployment then
            raise exception using errcode = '23514',
                message = 'Hearing epoch and execution selection are immutable';
        end if;
        if new.process_revision < old.process_revision
           or new.room_revision < old.room_revision
           or new.process_revision > old.process_revision + 1
           or new.room_revision > old.room_revision + 1 then
            raise exception using errcode = '23514',
                message = 'Hearing projection revisions must use monotonic single-step CAS';
        end if;
        if not (
            (new.stage_sequence = old.stage_sequence
                and new.current_stage = old.current_stage)
            or
            (new.stage_sequence = old.stage_sequence + 1)
        ) then
            raise exception using errcode = '23514',
                message = 'Hearing projection can move only to the adjacent stage';
        end if;
    end if;
    return new;
end
$$;

create trigger trg_hearing_temporal_projection_authority
    before insert or update on hearing_temporal_projection
    for each row execute function enforce_hearing_temporal_projection_authority();

create function bind_new_hearing_flow_projection()
returns trigger
language plpgsql
as $$
declare
    authority case_room_epoch%rowtype;
begin
    select epoch.*
      into authority
      from case_room_epoch epoch
     where epoch.case_id = new.case_id
       and epoch.room_type = 'HEARING'
       and epoch.lifecycle_status = 'ACTIVE'
     order by epoch.room_epoch desc, epoch.id
     limit 1;
    if not found then
        raise exception using errcode = '23514',
            message = 'new Hearing flow requires an ACTIVE case_room_epoch authority';
    end if;
    if authority.writer_mode = 'SHADOW' then
        raise exception using errcode = '23514',
            message = 'SHADOW Hearing epoch cannot create formal V035 facts';
    end if;
    if authority.writer_mode = 'TEMPORAL'
       and current_setting('app.hearing_authority_commit', true) is distinct from 'on' then
        raise exception using errcode = '23514',
            message = 'TEMPORAL Hearing flow creation requires the fenced Java authority commit';
    end if;

    insert into hearing_temporal_projection (
        flow_instance_id, case_id, schema_version, tenant_surrogate, epoch_id,
        room_type, hearing_epoch, writer_mode, process_revision, room_revision,
        fencing_token, current_stage, stage_sequence, stage_deadline_at,
        temporal_namespace, temporal_workflow_id, temporal_run_id,
        temporal_build_or_deployment, projected_at, updated_at
    ) values (
        new.id, new.case_id, 'hearing-stage-projection.v1', authority.tenant_surrogate,
        authority.id, 'HEARING', authority.room_epoch, authority.writer_mode,
        authority.process_revision, authority.room_revision, authority.fencing_token,
        new.current_stage, new.stage_sequence, new.shared_deadline_at,
        case when authority.writer_mode = 'LEGACY' then null else 'default' end,
        coalesce(authority.room_temporal_workflow_id, authority.temporal_workflow_id),
        coalesce(authority.room_temporal_run_id, authority.temporal_run_id),
        coalesce(authority.room_workflow_build_id, authority.temporal_build_id),
        new.created_at, greatest(new.updated_at, new.created_at)
    );
    return new;
end
$$;

create trigger trg_bind_new_hearing_flow_projection
    after insert on hearing_flow_instance
    for each row execute function bind_new_hearing_flow_projection();

create function guard_hearing_flow_cursor_writer()
returns trigger
language plpgsql
as $$
declare
    selected_writer text;
begin
    if new.current_stage is not distinct from old.current_stage
       and new.stage_sequence is not distinct from old.stage_sequence
       and new.flow_status is not distinct from old.flow_status
       and new.shared_deadline_at is not distinct from old.shared_deadline_at then
        return new;
    end if;
    select projection.writer_mode
      into selected_writer
      from hearing_temporal_projection projection
     where projection.flow_instance_id = old.id
       and projection.case_id = old.case_id;
    if selected_writer = 'SHADOW' then
        raise exception using errcode = '23514',
            message = 'SHADOW Hearing epoch cannot mutate the formal V035 cursor';
    end if;
    if selected_writer = 'TEMPORAL'
       and current_setting('app.hearing_authority_commit', true) is distinct from 'on' then
        raise exception using errcode = '23514',
            message = 'old Java Hearing advance is fenced for a TEMPORAL epoch';
    end if;
    return new;
end
$$;

create trigger trg_guard_hearing_flow_cursor_writer
    before update of current_stage, stage_sequence, flow_status, shared_deadline_at
    on hearing_flow_instance
    for each row execute function guard_hearing_flow_cursor_writer();

create function sync_legacy_hearing_projection()
returns trigger
language plpgsql
as $$
begin
    update hearing_temporal_projection
       set current_stage = new.current_stage,
           stage_sequence = new.stage_sequence,
           stage_deadline_at = new.shared_deadline_at,
           updated_at = greatest(updated_at, new.updated_at)
     where flow_instance_id = new.id
       and case_id = new.case_id
       and writer_mode = 'LEGACY'
       and (current_stage, stage_sequence, stage_deadline_at)
           is distinct from (new.current_stage, new.stage_sequence, new.shared_deadline_at);
    return new;
end
$$;

create trigger trg_sync_legacy_hearing_projection
    after update of current_stage, stage_sequence, flow_status, shared_deadline_at
    on hearing_flow_instance
    for each row execute function sync_legacy_hearing_projection();

-- V040's reviewer-only demo purge deletes room epochs before the V035 aggregate.
-- Clear the new children only inside that already-authorized, case-scoped transaction.
create function purge_hearing_temporal_before_epoch_delete()
returns trigger
language plpgsql
as $$
declare
    purge_case_id text;
    purge_reviewer_role text;
begin
    purge_case_id := current_setting('app.demo_case_purge_case_id', true);
    purge_reviewer_role := current_setting('app.demo_case_purge_reviewer_role', true);
    if purge_reviewer_role = 'PLATFORM_REVIEWER'
       and purge_case_id is not null
       and old.case_id = purge_case_id then
        delete from hearing_domain_receipt where epoch_id = old.id and case_id = old.case_id;
        delete from hearing_temporal_projection where epoch_id = old.id and case_id = old.case_id;
    end if;
    return old;
end
$$;

create trigger trg_purge_hearing_temporal_before_epoch_delete
    before delete on case_room_epoch
    for each row execute function purge_hearing_temporal_before_epoch_delete();
