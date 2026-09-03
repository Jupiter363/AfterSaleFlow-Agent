-- Durable Case/Room Workflow bootstrap. Command delivery remains in case_command_outbox.
do $$
begin
    if exists (
        select 1
          from case_room_epoch
         where lifecycle_status = 'ACTIVE'
           and writer_mode <> 'LEGACY'
    ) then
        raise exception using
            errcode = '23514',
            message = 'V040.4 requires non-LEGACY active epochs to be drained before provisioning migration';
    end if;
end
$$;

alter table case_room_epoch
    alter column lifecycle_status type varchar(24),
    add column provisioning_status varchar(24) not null default 'NOT_REQUIRED',
    add column room_temporal_workflow_id varchar(128),
    add column room_temporal_run_id varchar(128),
    add column provisioned_at timestamp with time zone,
    add column provisioning_failure_code varchar(64);

alter table case_process_projection
    add column writer_activation_status varchar(24) not null default 'READY';

alter table case_room_epoch
    drop constraint ck_case_room_epoch_lifecycle,
    drop constraint ck_case_room_epoch_terminal,
    drop constraint ck_case_room_epoch_writer_binding,
    add constraint ck_case_room_epoch_lifecycle
        check (lifecycle_status in (
            'PREPARING', 'PROVISIONING', 'ACTIVE',
            'PROVISIONING_FAILED', 'TERMINAL'
        )),
    add constraint ck_case_room_epoch_provisioning_status
        check (provisioning_status in (
            'NOT_REQUIRED', 'PENDING', 'PROVISIONING', 'READY', 'FAILED'
        )),
    add constraint ck_case_room_epoch_terminal
        check (
            (lifecycle_status in ('PREPARING', 'PROVISIONING', 'ACTIVE')
                and terminal_at is null)
            or
            (lifecycle_status in ('PROVISIONING_FAILED', 'TERMINAL')
                and terminal_at is not null)
        ),
    add constraint ck_case_room_epoch_provisioning_time
        check (
            (provisioned_at is null or provisioned_at >= activated_at)
            and (provisioned_at is null or provisioned_at <= updated_at)
        ),
    add constraint ck_case_room_epoch_writer_binding
        check (
            (
                writer_mode = 'LEGACY'
                and lifecycle_status in ('ACTIVE', 'TERMINAL')
                and provisioning_status = 'NOT_REQUIRED'
                and temporal_workflow_id is null
                and temporal_run_id is null
                and room_temporal_workflow_id is null
                and room_temporal_run_id is null
            )
            or
            (
                writer_mode = 'SHADOW'
                and lifecycle_status in ('ACTIVE', 'TERMINAL')
                and provisioning_status in ('PENDING', 'PROVISIONING', 'READY', 'FAILED')
                and length(btrim(temporal_workflow_id)) > 0
                and length(btrim(room_temporal_workflow_id)) > 0
                and fencing_token > 0
                and (
                    provisioning_status <> 'READY'
                    or (
                        length(btrim(temporal_run_id)) > 0
                        and length(btrim(room_temporal_run_id)) > 0
                        and provisioned_at is not null
                    )
                )
            )
            or
            (
                writer_mode = 'TEMPORAL'
                and length(btrim(temporal_workflow_id)) > 0
                and length(btrim(room_temporal_workflow_id)) > 0
                and fencing_token > 0
                and (
                    (
                        lifecycle_status in ('PREPARING', 'PROVISIONING')
                        and provisioning_status in ('PENDING', 'PROVISIONING')
                        and temporal_run_id is null
                        and room_temporal_run_id is null
                    )
                    or
                    (
                        lifecycle_status in ('ACTIVE', 'TERMINAL')
                        and provisioning_status = 'READY'
                        and length(btrim(temporal_run_id)) > 0
                        and length(btrim(room_temporal_run_id)) > 0
                        and provisioned_at is not null
                    )
                    or
                    (
                        lifecycle_status = 'PROVISIONING_FAILED'
                        and provisioning_status = 'FAILED'
                    )
                )
            )
        );

alter table case_process_projection
    drop constraint ck_case_process_projection_writer_binding,
    add constraint ck_case_process_projection_activation
        check (writer_activation_status in (
            'PREPARING', 'PROVISIONING', 'READY', 'FAILED', 'TERMINAL'
        )),
    add constraint ck_case_process_projection_writer_binding
        check (
            length(btrim(temporal_build_id)) between 1 and 128
            and (
                (
                    writer_mode = 'LEGACY'
                    and writer_activation_status in ('READY', 'TERMINAL')
                    and temporal_workflow_id is null
                    and temporal_run_id is null
                )
                or
                (
                    writer_mode = 'SHADOW'
                    and length(btrim(temporal_workflow_id)) > 0
                    and fencing_token > 0
                    and (
                        writer_activation_status <> 'READY'
                        or length(btrim(temporal_run_id)) > 0
                    )
                )
                or
                (
                    writer_mode = 'TEMPORAL'
                    and length(btrim(temporal_workflow_id)) > 0
                    and fencing_token > 0
                    and (
                        (writer_activation_status in ('PREPARING', 'PROVISIONING', 'FAILED')
                            and temporal_run_id is null)
                        or
                        (writer_activation_status in ('READY', 'TERMINAL')
                            and length(btrim(temporal_run_id)) > 0)
                    )
                )
            )
        );

create unique index uq_case_room_epoch_writer_slot
    on case_room_epoch(case_id)
    where lifecycle_status in ('PREPARING', 'PROVISIONING', 'ACTIVE');

create table room_epoch_bootstrap_outbox (
    id varchar(64) primary key,
    epoch_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    writer_mode varchar(16) not null,
    case_workflow_id varchar(128) not null,
    room_workflow_id varchar(128) not null,
    workflow_type varchar(128) not null,
    task_queue varchar(128) not null,
    update_id varchar(128) not null,
    payload_json text not null,
    payload_sha256 varchar(64) not null,
    outbox_status varchar(32) not null,
    available_at timestamptz not null,
    attempt_count integer not null default 0,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    last_attempt_at timestamptz,
    delivered_at timestamptz,
    case_temporal_run_id varchar(128),
    room_temporal_run_id varchar(128),
    last_error_code varchar(64),
    last_error_detail text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint fk_room_epoch_bootstrap_epoch
        foreign key (epoch_id) references case_room_epoch(id),
    constraint fk_room_epoch_bootstrap_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_room_epoch_bootstrap_epoch unique (epoch_id),
    constraint uq_room_epoch_bootstrap_update unique (tenant_surrogate, update_id),
    constraint ck_room_epoch_bootstrap_room
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_room_epoch_bootstrap_writer
        check (writer_mode in ('SHADOW', 'TEMPORAL')),
    constraint ck_room_epoch_bootstrap_status
        check (outbox_status in (
            'PENDING', 'CLAIMED', 'RETRY', 'DELIVERED',
            'RECONCILED', 'DEAD_LETTER'
        )),
    constraint ck_room_epoch_bootstrap_tuple
        check (room_epoch >= 0 and fencing_token > 0),
    constraint ck_room_epoch_bootstrap_payload
        check (length(payload_json) > 1 and payload_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_room_epoch_bootstrap_lease
        check (
            (lease_owner is null and lease_expires_at is null)
            or (lease_owner is not null and lease_expires_at is not null)
        ),
    constraint ck_room_epoch_bootstrap_receipt
        check (
            (outbox_status <> 'DELIVERED')
            or (
                delivered_at is not null
                and length(btrim(case_temporal_run_id)) > 0
                and length(btrim(room_temporal_run_id)) > 0
            )
        ),
    constraint ck_room_epoch_bootstrap_version
        check (attempt_count >= 0 and version >= 0)
);

create index idx_room_epoch_bootstrap_pending
    on room_epoch_bootstrap_outbox(available_at, created_at, id)
    where outbox_status in ('PENDING', 'RETRY');

create index idx_room_epoch_bootstrap_lease
    on room_epoch_bootstrap_outbox(lease_expires_at)
    where outbox_status = 'CLAIMED';

create or replace function reject_case_room_epoch_selection_rewrite()
returns trigger
language plpgsql
as $$
begin
    if new.id is distinct from old.id
        or new.tenant_surrogate is distinct from old.tenant_surrogate
        or new.case_id is distinct from old.case_id
        or new.room_id is distinct from old.room_id
        or new.room_type is distinct from old.room_type
        or new.room_epoch is distinct from old.room_epoch
        or new.writer_mode is distinct from old.writer_mode
        or new.fencing_token is distinct from old.fencing_token
        or new.temporal_workflow_id is distinct from old.temporal_workflow_id
        or new.temporal_build_id is distinct from old.temporal_build_id
        or new.graph_key is distinct from old.graph_key
        or new.graph_version is distinct from old.graph_version
        or new.checkpoint_schema_version is distinct from old.checkpoint_schema_version
        or new.stream_protocol is distinct from old.stream_protocol
        or new.selection_schema_version is distinct from old.selection_schema_version
        or new.process_contract_version is distinct from old.process_contract_version
        or new.workflow_type is distinct from old.workflow_type
        or new.activated_at is distinct from old.activated_at
        or new.created_at is distinct from old.created_at
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch immutable execution selection cannot be rewritten';
    end if;

    if new.process_revision < old.process_revision
        or new.room_revision < old.room_revision
        or new.updated_at < old.updated_at
        or new.version < old.version
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch revisions, update time and version cannot move backward';
    end if;

    if (old.provisioning_status = 'NOT_REQUIRED'
            and new.provisioning_status <> 'NOT_REQUIRED')
        or (old.provisioning_status = 'PENDING'
            and new.provisioning_status not in ('PENDING', 'PROVISIONING'))
        or (old.provisioning_status = 'PROVISIONING'
            and new.provisioning_status not in ('PROVISIONING', 'READY', 'FAILED'))
        or (old.provisioning_status = 'READY'
            and new.provisioning_status <> 'READY')
        or (old.provisioning_status = 'FAILED'
            and new.provisioning_status <> 'FAILED')
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch provisioning status cannot move backward';
    end if;

    if old.lifecycle_status = 'TERMINAL' then
        if new is distinct from old then
            raise exception using
                errcode = '23514',
                message = 'case_room_epoch TERMINAL lifecycle is immutable';
        end if;
        return new;
    end if;

    if old.lifecycle_status = 'PROVISIONING_FAILED' then
        if new is distinct from old then
            raise exception using
                errcode = '23514',
                message = 'case_room_epoch PROVISIONING_FAILED lifecycle is immutable';
        end if;
        return new;
    end if;

    if (old.lifecycle_status = 'PREPARING'
            and new.lifecycle_status not in ('PREPARING', 'PROVISIONING', 'PROVISIONING_FAILED'))
        or (old.lifecycle_status = 'PROVISIONING'
            and new.lifecycle_status not in ('PROVISIONING', 'ACTIVE', 'PROVISIONING_FAILED'))
        or (old.lifecycle_status = 'ACTIVE'
            and new.lifecycle_status not in ('ACTIVE', 'TERMINAL'))
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch lifecycle transition is invalid';
    end if;

    if new.lifecycle_status = 'TERMINAL'
        and (new.terminal_at is null
            or new.terminal_at < old.updated_at
            or new.process_revision <= old.process_revision)
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch terminal transition must advance revision and time';
    end if;

    if old.room_temporal_workflow_id is not null
        and new.room_temporal_workflow_id is distinct from old.room_temporal_workflow_id
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch room workflow binding is immutable';
    end if;

    if old.temporal_run_id is not null
        and new.temporal_run_id is distinct from old.temporal_run_id
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch case run binding is immutable';
    end if;

    if old.room_temporal_run_id is not null
        and new.room_temporal_run_id is distinct from old.room_temporal_run_id
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch room run binding is immutable';
    end if;

    if (old.temporal_run_id is null and new.temporal_run_id is not null)
        or (old.room_temporal_run_id is null and new.room_temporal_run_id is not null)
    then
        if new.provisioning_status <> 'READY'
            or new.lifecycle_status <> 'ACTIVE'
            or new.temporal_run_id is null
            or new.room_temporal_run_id is null
            or new.provisioned_at is null
        then
            raise exception using
                errcode = '23514',
                message = 'case_room_epoch run bindings require atomic READY activation';
        end if;
    end if;

    if old.provisioned_at is not null
        and new.provisioned_at is distinct from old.provisioned_at
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch provisioned time is immutable';
    end if;

    if new.lifecycle_status = 'PROVISIONING_FAILED'
        and (new.provisioning_status <> 'FAILED'
            or new.terminal_at is null
            or coalesce(btrim(new.provisioning_failure_code), '') = '')
    then
        raise exception using
            errcode = '23514',
            message = 'failed room epoch provisioning requires a durable failure record';
    end if;
    return new;
end
$$;

create or replace function enforce_case_process_projection_activation()
returns trigger
language plpgsql
as $$
begin
    if new.process_revision < old.process_revision
        or new.last_command_sequence < old.last_command_sequence
        or new.last_case_event_sequence < old.last_case_event_sequence
        or new.updated_at < old.updated_at
        or new.version < old.version
    then
        raise exception using
            errcode = '23514',
            message = 'case_process_projection revisions, sequences, update time and version cannot move backward';
    end if;

    if new.room_epoch is not distinct from old.room_epoch
        and new.fencing_token is not distinct from old.fencing_token
    then
        if (old.writer_activation_status = 'PREPARING'
                and new.writer_activation_status not in ('PREPARING', 'PROVISIONING'))
            or (old.writer_activation_status = 'PROVISIONING'
                and new.writer_activation_status not in ('PROVISIONING', 'READY', 'FAILED'))
            or (old.writer_activation_status = 'READY'
                and new.writer_activation_status not in ('READY', 'TERMINAL'))
            or (old.writer_activation_status = 'FAILED'
                and new.writer_activation_status <> 'FAILED')
            or (old.writer_activation_status = 'TERMINAL'
                and new.writer_activation_status <> 'TERMINAL')
        then
            raise exception using
                errcode = '23514',
                message = 'case_process_projection activation status cannot move backward';
        end if;

        if old.temporal_run_id is not null
            and new.temporal_run_id is distinct from old.temporal_run_id
        then
            raise exception using
                errcode = '23514',
                message = 'case_process_projection run binding is immutable within an epoch';
        end if;
    elsif new.fencing_token <= old.fencing_token
        or new.process_revision <= old.process_revision
    then
        raise exception using
            errcode = '23514',
            message = 'case_process_projection epoch switch must advance revision and fence';
    end if;
    return new;
end
$$;

create trigger trg_case_process_projection_activation
before update on case_process_projection
for each row
execute function enforce_case_process_projection_activation();
