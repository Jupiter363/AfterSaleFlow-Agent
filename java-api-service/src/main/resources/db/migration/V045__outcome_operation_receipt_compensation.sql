-- Additive Outcome projection and append-only external-effect coordination ledger.
-- This migration does not activate a Temporal Outcome writer or a real tool adapter.

create table outcome_process_projection (
    projection_id varchar(64) primary key,
    schema_version varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    epoch_id varchar(64) not null,
    room_type varchar(32) not null,
    outcome_epoch bigint not null,
    writer_mode varchar(16) not null,
    runtime_mode varchar(64) not null,
    fencing_token bigint not null,
    process_revision bigint not null,
    outcome_revision bigint not null,
    decision_authority_receipt_id varchar(64) not null,
    decision_request_hash varchar(64) not null,
    approved_operation_set_hash varchar(128) not null,
    expected_required_operation_count bigint not null,
    process_state varchar(48) not null,
    projected_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_outcome_projection_case_epoch
        unique (tenant_surrogate, case_id, outcome_epoch),
    constraint uq_outcome_projection_id_case
        unique (projection_id, case_id),
    constraint fk_outcome_projection_epoch
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, outcome_epoch, fencing_token
        ) references case_room_epoch(
            id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ) on delete cascade,
    constraint fk_outcome_projection_decision_authority
        foreign key (decision_authority_receipt_id) references human_review_record(id),
    constraint ck_outcome_projection_schema
        check (schema_version = 'outcome-process-projection.v1'),
    constraint ck_outcome_projection_authority
        check (
            room_type = 'REVIEW'
            and outcome_epoch >= 0
            and fencing_token >= 0
            and process_revision >= 0
            and outcome_revision >= 0
            and decision_request_hash ~ '^[0-9a-f]{64}$'
            and length(btrim(approved_operation_set_hash)) between 1 and 128
            and expected_required_operation_count >= 0
        ),
    constraint ck_outcome_projection_engineering_mode
        check (
            (writer_mode = 'LEGACY' and runtime_mode = 'DISABLED')
            or
            (writer_mode = 'SHADOW'
                and runtime_mode = 'JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW'
                and fencing_token > 0)
        ),
    constraint ck_outcome_projection_state
        check (process_state in (
            'REVIEW_WAIT', 'DECISION_RECORDED', 'OPERATIONS_RESERVED',
            'OPERATIONS_RUNNING', 'RECONCILING', 'COMPENSATING',
            'READY_TO_CLOSE', 'MANUAL_RECOVERY', 'CLOSED',
            'EVALUATION_PENDING', 'EVALUATED'
        )),
    constraint ck_outcome_projection_time check (updated_at >= projected_at)
);

create index idx_outcome_projection_reconcile
    on outcome_process_projection(
        writer_mode, process_state, epoch_id, fencing_token, process_revision, outcome_revision
    );

create function enforce_outcome_projection_authority()
returns trigger
language plpgsql
as $$
declare
    authority case_room_epoch%rowtype;
    approval human_review_record%rowtype;
    approved_plan remedy_plan%rowtype;
    task review_task%rowtype;
    scope_lock_key bigint;
    approved_identity_count bigint;
    distinct_approved_identity_count bigint;
    required_original_operation_count bigint;
    unresolved_required_operation_count bigint;
    required_action_set_exact boolean;
    required_action_records_succeeded boolean;
begin
    scope_lock_key := hashtextextended(
        'outcome-compensation-order:' || new.tenant_surrogate || ':' ||
        new.case_id || ':' || new.outcome_epoch::text,
        0
    );
    if tg_op = 'UPDATE' then
        -- PostgreSQL has already locked the projection tuple before a BEFORE ROW UPDATE
        -- trigger runs. Never block here against the scope-first reservation path.
        if not pg_try_advisory_xact_lock(scope_lock_key) then
            raise exception using errcode = '40001',
                message = 'Outcome projection scope lock is busy; retry the whole transition';
        end if;
    else
        perform pg_advisory_xact_lock(scope_lock_key);
    end if;
    select epoch.*
      into authority
      from case_room_epoch epoch
     where epoch.id = new.epoch_id
       and epoch.tenant_surrogate = new.tenant_surrogate
       and epoch.case_id = new.case_id
       and epoch.room_type = 'REVIEW'
       and epoch.room_epoch = new.outcome_epoch
       and epoch.fencing_token = new.fencing_token
       and epoch.writer_mode = new.writer_mode
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome projection has no exact case_room_epoch authority';
    end if;
    if authority.writer_mode = 'TEMPORAL' then
        raise exception using errcode = '23514',
            message = 'V045 engineering scope forbids TEMPORAL Outcome allocation';
    end if;

    select value.* into approval
      from human_review_record value
     where value.id = new.decision_authority_receipt_id
       and value.case_id = new.case_id
       and value.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
       and value.action_snapshot_hash = new.approved_operation_set_hash
       and jsonb_typeof(value.approved_plan_json -> 'actions') = 'array'
       and jsonb_typeof(value.approved_plan_json -> 'notifications') = 'array'
       and jsonb_array_length(value.approved_plan_json -> 'actions')
           + jsonb_array_length(value.approved_plan_json -> 'notifications')
            = new.expected_required_operation_count
      for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome projection required-operation authority is invalid';
    end if;

    select value.* into approved_plan
      from remedy_plan value
     where value.id = approval.plan_id
       and value.case_id = new.case_id
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome projection approved plan authority is invalid';
    end if;
    if exists (
        select 1
          from jsonb_array_elements(approval.approved_plan_json -> 'actions') entry
         where jsonb_typeof(entry.value) <> 'object'
            or nullif(btrim(coalesce(entry.value ->> 'action_type', '')), '') is null
            or length(entry.value ->> 'action_type') > 64
            or nullif(btrim(coalesce(entry.value ->> 'idempotency_key', '')), '') is null
            or length(entry.value ->> 'idempotency_key') > 128
    ) or exists (
        select 1
          from jsonb_array_elements(
                   approval.approved_plan_json -> 'notifications'
               ) with ordinality notification(value, ordinal)
         where jsonb_typeof(notification.value) <> 'string'
            or nullif(btrim(coalesce(notification.value #>> '{}', '')), '') is null
            or length(notification.value #>> '{}') > 64
            or length(
                'REMEDY:' || new.case_id || ':' || approved_plan.plan_version::text
                || ':NOTIFICATION:' || (notification.ordinal - 1)::text || ':'
                || (notification.value #>> '{}')
            ) > 128
    ) then
        raise exception using errcode = '23514',
            message = 'Outcome projection approved action identity shape is invalid';
    end if;
    with approved_identity as (
        select entry.value ->> 'action_type' as action_type,
               entry.value ->> 'idempotency_key' as idempotency_key
          from jsonb_array_elements(approval.approved_plan_json -> 'actions') entry
        union all
        select notification.value #>> '{}' as action_type,
               'REMEDY:' || new.case_id || ':' || approved_plan.plan_version::text
                   || ':NOTIFICATION:' || (notification.ordinal - 1)::text || ':'
                   || (notification.value #>> '{}') as idempotency_key
          from jsonb_array_elements(
                   approval.approved_plan_json -> 'notifications'
               ) with ordinality notification(value, ordinal)
    )
    select count(*), count(distinct idempotency_key)
      into approved_identity_count, distinct_approved_identity_count
      from approved_identity;
    if approved_identity_count <> new.expected_required_operation_count
       or distinct_approved_identity_count <> approved_identity_count then
        raise exception using errcode = '23514',
            message = 'Outcome projection approved action identities are not a unique exact multiset';
    end if;

    select value.* into task
      from review_task value
     where value.id = approval.review_task_id
       and value.case_id = new.case_id
       and value.decision_json ->> 'request_hash' = new.decision_request_hash
       and value.decision_json ->> 'approved_action_hash' = new.approved_operation_set_hash
       and (value.decision_json ->> 'outcome_epoch')::bigint = new.outcome_epoch
       and (value.decision_json ->> 'fencing_token')::bigint = new.fencing_token
       and (value.decision_json ->> 'process_revision')::bigint <= new.process_revision
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome projection decision request authority is invalid';
    end if;
    if tg_op = 'INSERT' then
        if new.process_state <> 'DECISION_RECORDED' then
            raise exception using errcode = '23514',
                message = 'Outcome projection bootstrap state is illegal';
        end if;
        if authority.lifecycle_status <> 'ACTIVE'
           or new.process_revision <> authority.process_revision
           or new.outcome_revision <> authority.room_revision then
            raise exception using errcode = '23514',
                message = 'Outcome projection bootstrap authority is stale';
        end if;
    else
        if (new.projection_id, new.schema_version, new.tenant_surrogate, new.case_id,
                new.epoch_id, new.room_type, new.outcome_epoch, new.writer_mode,
                new.runtime_mode, new.fencing_token, new.decision_authority_receipt_id,
                new.decision_request_hash, new.approved_operation_set_hash,
                new.expected_required_operation_count, new.projected_at)
           is distinct from
           (old.projection_id, old.schema_version, old.tenant_surrogate, old.case_id,
                old.epoch_id, old.room_type, old.outcome_epoch, old.writer_mode,
                old.runtime_mode, old.fencing_token, old.decision_authority_receipt_id,
                old.decision_request_hash, old.approved_operation_set_hash,
                old.expected_required_operation_count, old.projected_at) then
            raise exception using errcode = '23514',
                message = 'Outcome projection immutable authority changed';
        end if;
        if new.process_revision <> old.process_revision + 1
           or new.outcome_revision <> old.outcome_revision + 1
           or new.process_revision <> authority.process_revision
           or new.outcome_revision <> authority.room_revision
           or new.updated_at < old.updated_at then
            raise exception using errcode = '23514',
                message = 'Outcome projection revision fence rejected';
        end if;
        if old.process_state in (
               'READY_TO_CLOSE', 'CLOSED', 'EVALUATION_PENDING', 'EVALUATED'
           )
           or new.process_state in (
               'READY_TO_CLOSE', 'CLOSED', 'EVALUATION_PENDING', 'EVALUATED'
           ) then
            if not (
                (new.process_state = 'READY_TO_CLOSE'
                    and old.process_state in (
                        'DECISION_RECORDED', 'OPERATIONS_RESERVED',
                        'OPERATIONS_RUNNING', 'RECONCILING',
                        'COMPENSATING', 'MANUAL_RECOVERY'
                    ))
                or (old.process_state = 'READY_TO_CLOSE'
                    and new.process_state = 'CLOSED')
                or (old.process_state = 'CLOSED'
                    and new.process_state = 'EVALUATION_PENDING')
                or (old.process_state = 'EVALUATION_PENDING'
                    and new.process_state = 'EVALUATED')
            ) then
                raise exception using errcode = '23514',
                    message = 'Outcome projection terminal transition is illegal';
            end if;
        end if;
        if new.process_state in (
            'READY_TO_CLOSE', 'CLOSED', 'EVALUATION_PENDING', 'EVALUATED'
        ) then
            execute
                'select outcome_required_action_set_is_exact($1), '
                || 'outcome_required_action_records_succeeded($1)'
               into required_action_set_exact, required_action_records_succeeded
              using new.projection_id;
            select count(operation.operation_id) filter (
                       where operation.required_for_closure
                         and operation.operation_kind = 'OPERATION'
                   ),
                   count(operation.operation_id) filter (
                       where operation.required_for_closure
                         and (
                             receipt.operation_id is null
                             or receipt.receipt_status <> 'SUCCEEDED'
                             or receipt.closure_disposition <> 'SATISFIED'
                         )
                   )
              into required_original_operation_count,
                   unresolved_required_operation_count
              from outcome_operation operation
              left join outcome_operation_receipt receipt
                on receipt.operation_id = operation.operation_id
             where operation.projection_id = new.projection_id;
            if required_original_operation_count
                   <> new.expected_required_operation_count
               or unresolved_required_operation_count <> 0
               or not required_action_set_exact
               or not required_action_records_succeeded then
                raise exception using errcode = '23514',
                    message = 'Outcome projection terminal transition requires closure readiness';
            end if;
        end if;
    end if;
    return new;
end
$$;

create trigger trg_outcome_projection_authority
    before insert or update on outcome_process_projection
    for each row execute function enforce_outcome_projection_authority();

create trigger trg_outcome_projection_truncate_guard
    before truncate on outcome_process_projection
    for each statement execute function reject_append_only_mutation();

create trigger trg_outcome_projection_delete_guard
    before delete on outcome_process_projection
    for each row execute function reject_append_only_mutation();

create table outcome_operation (
    operation_id varchar(64) primary key,
    schema_version varchar(64) not null,
    projection_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    outcome_epoch bigint not null,
    fencing_token bigint not null,
    process_revision bigint not null,
    outcome_revision bigint not null,
    operation_kind varchar(24) not null,
    operation_sequence bigint not null,
    operation_key varchar(256) not null,
    request_hash varchar(64) not null,
    review_packet_id varchar(64) not null,
    review_packet_version integer not null,
    review_packet_hash varchar(64) not null,
    review_packet_action_hash varchar(128) not null,
    approval_record_id varchar(64) not null,
    approval_hash varchar(128) not null,
    decision_request_hash varchar(64) not null,
    decision_policy_version varchar(64) not null,
    action_record_id varchar(64),
    action_snapshot_hash varchar(128) not null,
    adapter_id varchar(128) not null,
    adapter_version varchar(64) not null,
    retry_class varchar(48) not null,
    external_idempotency_key varchar(256) not null,
    required_for_closure boolean not null,
    compensable boolean not null,
    reserved_at timestamptz not null,
    constraint uq_outcome_operation_scope_key
        unique (tenant_surrogate, case_id, outcome_epoch, operation_key),
    constraint uq_outcome_operation_scope_key_hash
        unique (tenant_surrogate, case_id, outcome_epoch, operation_key, request_hash),
    constraint uq_outcome_operation_projection_sequence
        unique (projection_id, operation_sequence),
    constraint uq_outcome_operation_external_key
        unique (adapter_id, external_idempotency_key),
    constraint uq_outcome_operation_action
        unique (action_record_id),
    constraint uq_outcome_operation_identity
        unique (
            operation_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, request_hash
        ),
    constraint fk_outcome_operation_projection
        foreign key (projection_id, case_id)
        references outcome_process_projection(projection_id, case_id) on delete cascade,
    constraint fk_outcome_operation_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_outcome_operation_packet
        foreign key (review_packet_id) references review_packet(id),
    constraint fk_outcome_operation_approval
        foreign key (approval_record_id) references human_review_record(id),
    constraint fk_outcome_operation_action
        foreign key (action_record_id) references action_record(id),
    constraint ck_outcome_operation_schema
        check (schema_version = 'outcome-operation-command.v1'),
    constraint ck_outcome_operation_identity
        check (
            operation_kind in ('OPERATION', 'COMPENSATION')
            and operation_sequence >= 1
            and outcome_epoch >= 0
            and fencing_token >= 0
            and process_revision >= 0
            and outcome_revision >= 0
            and length(btrim(operation_key)) between 1 and 256
            and request_hash ~ '^[0-9a-f]{64}$'
            and review_packet_version > 0
            and review_packet_hash ~ '^[0-9a-f]{64}$'
            and length(btrim(review_packet_action_hash)) between 1 and 128
            and length(btrim(approval_hash)) between 1 and 128
            and decision_request_hash ~ '^[0-9a-f]{64}$'
            and length(btrim(decision_policy_version)) between 1 and 64
            and length(btrim(action_snapshot_hash)) between 1 and 128
            and length(btrim(adapter_id)) between 1 and 128
            and length(btrim(adapter_version)) between 1 and 64
            and length(btrim(external_idempotency_key)) between 1 and 256
        ),
    constraint ck_outcome_operation_retry
        check (retry_class in (
            'NON_RETRYABLE', 'BOUNDED_PRE_EFFECT',
            'IDEMPOTENT_PROVIDER', 'STATUS_QUERY_REQUIRED'
        ))
    ,constraint ck_outcome_compensation_required_for_closure
        check (operation_kind <> 'COMPENSATION' or required_for_closure)
);

create index idx_outcome_operation_closure
    on outcome_operation(projection_id, required_for_closure, operation_kind, reserved_at);

create index idx_outcome_operation_reconcile
    on outcome_operation(case_id, outcome_epoch, fencing_token, process_revision, outcome_revision);

create function outcome_required_action_record_is_authorized(
    p_projection_id varchar(64),
    p_action_record_id varchar(64)
)
returns boolean
language sql
stable
as $$
with authority as (
    select projection.projection_id,
           projection.case_id,
           projection.decision_authority_receipt_id,
           projection.approved_operation_set_hash,
           approval.plan_id,
           approval.review_packet_id,
           approval.approved_plan_json,
           plan.plan_version
      from outcome_process_projection projection
      join human_review_record approval
        on approval.id = projection.decision_authority_receipt_id
       and approval.case_id = projection.case_id
       and approval.action_snapshot_hash = projection.approved_operation_set_hash
       and approval.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
      join remedy_plan plan
        on plan.id = approval.plan_id
       and plan.case_id = projection.case_id
     where projection.projection_id = p_projection_id
), approved_identity as (
    select entry.value ->> 'action_type' as action_type,
           entry.value ->> 'idempotency_key' as idempotency_key
      from authority,
           jsonb_array_elements(authority.approved_plan_json -> 'actions') entry
    union all
    select notification.value #>> '{}' as action_type,
           'REMEDY:' || authority.case_id || ':' || authority.plan_version::text
               || ':NOTIFICATION:' || (notification.ordinal - 1)::text || ':'
               || (notification.value #>> '{}') as idempotency_key
      from authority,
           jsonb_array_elements(
               authority.approved_plan_json -> 'notifications'
           ) with ordinality notification(value, ordinal)
)
select exists (
    select 1
      from authority
      join action_record action
        on action.id = p_action_record_id
       and action.case_id = authority.case_id
       and action.plan_id = authority.plan_id
       and action.approval_record_id = authority.decision_authority_receipt_id
       and action.review_packet_id = authority.review_packet_id
       and action.action_snapshot_hash = authority.approved_operation_set_hash
      join approved_identity identity
        on identity.action_type = action.action_type
       and identity.idempotency_key = action.idempotency_key
)
$$;

create function outcome_required_action_set_is_exact(p_projection_id varchar(64))
returns boolean
language sql
stable
as $$
with authority as (
    select projection.projection_id,
           projection.tenant_surrogate,
           projection.case_id,
           projection.runtime_mode,
           projection.expected_required_operation_count,
           approval.approved_plan_json,
           plan.plan_version
      from outcome_process_projection projection
      join human_review_record approval
        on approval.id = projection.decision_authority_receipt_id
       and approval.case_id = projection.case_id
       and approval.action_snapshot_hash = projection.approved_operation_set_hash
       and approval.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
      join remedy_plan plan
        on plan.id = approval.plan_id
       and plan.case_id = projection.case_id
     where projection.projection_id = p_projection_id
), approved_identity as (
    select entry.value ->> 'action_type' as action_type,
           entry.value ->> 'idempotency_key' as idempotency_key
      from authority,
           jsonb_array_elements(authority.approved_plan_json -> 'actions') entry
    union all
    select notification.value #>> '{}' as action_type,
           'REMEDY:' || authority.case_id || ':' || authority.plan_version::text
               || ':NOTIFICATION:' || (notification.ordinal - 1)::text || ':'
               || (notification.value #>> '{}') as idempotency_key
      from authority,
           jsonb_array_elements(
               authority.approved_plan_json -> 'notifications'
           ) with ordinality notification(value, ordinal)
), required_original as (
    select operation.*
      from authority
      join outcome_operation operation
        on operation.projection_id = authority.projection_id
       and operation.operation_kind = 'OPERATION'
       and operation.required_for_closure
), reserved_identity as (
    select action.action_type, action.idempotency_key
      from required_original operation
      join action_record action
        on action.id = operation.action_record_id
     where outcome_required_action_record_is_authorized(
               operation.projection_id, operation.action_record_id
           )
)
select coalesce((
    select case
        when authority.runtime_mode = 'JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW' then
            (select count(*) from required_original)
                = authority.expected_required_operation_count
            and not exists (
                select 1
                  from required_original operation
                 where operation.action_record_id is not null
                    or operation.adapter_id <> 'SYNTHETIC_NOOP_ONLY'
                    or left(operation.tenant_surrogate, 18) <> 'OUTCOME_SYNTHETIC_'
                    or left(operation.case_id, 18) <> 'OUTCOME_SYNTHETIC_'
                    or operation.operation_sequence < 1
                    or operation.operation_sequence
                        > authority.expected_required_operation_count
            )
        else
            not exists (
                select 1
                  from required_original operation
                 where operation.action_record_id is null
                    or not outcome_required_action_record_is_authorized(
                        operation.projection_id, operation.action_record_id
                    )
            )
            and not exists (
                (select action_type, idempotency_key from approved_identity)
                except all
                (select action_type, idempotency_key from reserved_identity)
            )
            and not exists (
                (select action_type, idempotency_key from reserved_identity)
                except all
                (select action_type, idempotency_key from approved_identity)
            )
        end
      from authority
), false)
$$;

create function outcome_required_action_records_succeeded(p_projection_id varchar(64))
returns boolean
language sql
stable
as $$
select coalesce((
    select case
        when projection.runtime_mode = 'JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW' then true
        else not exists (
            select 1
              from outcome_operation operation
              left join action_record action
                on action.id = operation.action_record_id
             where operation.projection_id = projection.projection_id
               and operation.operation_kind = 'OPERATION'
               and operation.required_for_closure
               and (
                   action.execution_status is distinct from 'SUCCEEDED'
                   or not outcome_required_action_record_is_authorized(
                       operation.projection_id, operation.action_record_id
                   )
               )
        )
        end
      from outcome_process_projection projection
     where projection.projection_id = p_projection_id
), false)
$$;

create function enforce_outcome_operation_binding()
returns trigger
language plpgsql
as $$
declare
    projection outcome_process_projection%rowtype;
    packet review_packet%rowtype;
    approval human_review_record%rowtype;
    task review_task%rowtype;
    action action_record%rowtype;
    existing_operation_count bigint;
    reserved_required_operation_count bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-compensation-order:' || new.tenant_surrogate || ':' ||
        new.case_id || ':' || new.outcome_epoch::text,
        0
    ));
    select value.* into projection
      from outcome_process_projection value
     where value.projection_id = new.projection_id
       and value.tenant_surrogate = new.tenant_surrogate
       and value.case_id = new.case_id
       and value.outcome_epoch = new.outcome_epoch
       and value.fencing_token = new.fencing_token
       and value.process_revision = new.process_revision
       and value.outcome_revision = new.outcome_revision
     for update;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome operation projection fence is stale';
    end if;
    if projection.process_state in (
        'READY_TO_CLOSE', 'CLOSED', 'EVALUATION_PENDING', 'EVALUATED'
    ) then
        raise exception using errcode = '23514',
            message = 'Outcome operation reservation is forbidden after closure readiness';
    end if;
    if (new.approval_record_id, new.decision_request_hash, new.action_snapshot_hash)
       is distinct from
       (projection.decision_authority_receipt_id, projection.decision_request_hash,
           projection.approved_operation_set_hash) then
        raise exception using errcode = '23514',
            message = 'Outcome operation authority does not match the locked projection';
    end if;

    select count(*) into existing_operation_count
      from outcome_operation value
     where value.projection_id = new.projection_id;
    if new.operation_sequence <> existing_operation_count + 1 then
        raise exception using errcode = '23514',
            message = 'Outcome operation sequence must be consecutive and one-based';
    end if;
    if new.operation_kind = 'OPERATION' then
        if exists (
            select 1 from outcome_operation value
             where value.projection_id = new.projection_id
               and value.operation_kind = 'COMPENSATION'
        ) then
            raise exception using errcode = '23514',
                message = 'Outcome approved operation set is frozen before compensation';
        end if;
        select count(*) into reserved_required_operation_count
          from outcome_operation value
         where value.projection_id = new.projection_id
           and value.operation_kind = 'OPERATION'
           and value.required_for_closure;
        if new.required_for_closure
           and reserved_required_operation_count >= projection.expected_required_operation_count then
            raise exception using errcode = '23514',
                message = 'Outcome required operation count exceeds approved expectation';
        end if;
    end if;

    select value.* into packet
      from review_packet value
     where value.id = new.review_packet_id
       and value.case_id = new.case_id
       and value.packet_version = new.review_packet_version
       and value.frozen
       and value.action_hash = new.review_packet_action_hash
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome operation review packet binding is invalid';
    end if;

    select value.* into approval
      from human_review_record value
     where value.id = new.approval_record_id
       and value.case_id = new.case_id
       and value.plan_id = packet.plan_id
       and value.action_hash = new.approval_hash
       and value.review_packet_id = new.review_packet_id
       and value.review_packet_version = new.review_packet_version
       and value.policy_version = new.decision_policy_version
       and value.action_snapshot_hash = new.action_snapshot_hash
       and value.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome operation approval binding is invalid';
    end if;

    select value.* into task
      from review_task value
     where value.id = approval.review_task_id
       and value.case_id = new.case_id
       and value.packet_id = new.review_packet_id
       and value.decision_json ->> 'request_hash' = new.decision_request_hash
       and value.decision_json ->> 'packet_content_hash' = new.review_packet_hash
       and value.decision_json ->> 'approved_action_hash' = new.action_snapshot_hash
       and value.decision_json ->> 'policy_version' = new.decision_policy_version
       and (value.decision_json ->> 'outcome_epoch')::bigint = new.outcome_epoch
       and (value.decision_json ->> 'fencing_token')::bigint = new.fencing_token
       and (value.decision_json ->> 'process_revision')::bigint = new.process_revision
     for key share;
    if not found then
        raise exception using errcode = '23514',
            message = 'Outcome operation execution-authorized decision receipt is invalid';
    end if;

    if new.operation_kind = 'OPERATION' and new.required_for_closure then
        if projection.runtime_mode = 'JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW' then
            if new.action_record_id is not null
               or new.adapter_id <> 'SYNTHETIC_NOOP_ONLY'
               or left(new.tenant_surrogate, 18) <> 'OUTCOME_SYNTHETIC_'
               or left(new.case_id, 18) <> 'OUTCOME_SYNTHETIC_'
               or new.operation_sequence > projection.expected_required_operation_count then
                raise exception using errcode = '23514',
                    message = 'Synthetic Outcome required operation authority is invalid';
            end if;
        elsif new.action_record_id is null
           or not outcome_required_action_record_is_authorized(
               new.projection_id, new.action_record_id
           ) then
            raise exception using errcode = '23514',
                message = 'Outcome required operation has no exact approved ActionRecord authority';
        end if;
    end if;

    if new.action_record_id is not null then
        select value.* into action
          from action_record value
         where value.id = new.action_record_id
           and value.case_id = new.case_id
           and value.plan_id = packet.plan_id
           and value.approval_record_id = new.approval_record_id
           and value.review_packet_id = new.review_packet_id
           and value.action_snapshot_hash = new.action_snapshot_hash
         for key share;
        if not found then
            raise exception using errcode = '23514',
                message = 'Outcome operation ActionRecord binding is invalid';
        end if;
    end if;
    return new;
end
$$;

create trigger trg_outcome_operation_binding
    before insert on outcome_operation
    for each row execute function enforce_outcome_operation_binding();

create trigger trg_outcome_operation_append_only
    before update or truncate on outcome_operation
    for each statement execute function reject_append_only_mutation();

create trigger trg_outcome_operation_delete_append_only
    before delete on outcome_operation
    for each row execute function reject_append_only_mutation();

create table outcome_operation_attempt_observation (
    observation_id varchar(64) primary key,
    schema_version varchar(64) not null,
    observation_hash varchar(64) not null,
    operation_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    outcome_epoch bigint not null,
    fencing_token bigint not null,
    request_hash varchar(64) not null,
    attempt_sequence integer not null,
    observation_type varchar(48) not null,
    external_invocation_id varchar(256),
    observation_ref varchar(1024) not null,
    observation_payload_hash varchar(64) not null,
    effect_may_have_occurred boolean not null,
    retry_permitted boolean not null,
    observed_at timestamptz not null,
    constraint uq_outcome_attempt_operation_sequence
        unique (operation_id, attempt_sequence),
    constraint uq_outcome_attempt_id_hash
        unique (observation_id, observation_hash),
    constraint fk_outcome_attempt_operation
        foreign key (
            operation_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, request_hash
        ) references outcome_operation(
            operation_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, request_hash
        ) on delete cascade,
    constraint ck_outcome_attempt_schema
        check (schema_version = 'outcome-operation-attempt-observation.v1'),
    constraint ck_outcome_attempt_identity
        check (
            observation_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and observation_payload_hash ~ '^[0-9a-f]{64}$'
            and attempt_sequence > 0
            and observation_ref ~ '^(urn|s3|minio):'
        ),
    constraint ck_outcome_attempt_type
        check (observation_type in (
            'INVOCATION_DISPATCHED', 'PRE_EFFECT_RETRYABLE_FAILURE',
            'AMBIGUOUS', 'RECONCILING', 'NO_EFFECT_CONFIRMED'
        )),
    constraint ck_outcome_attempt_retry_safety
        check (
            (observation_type = 'AMBIGUOUS'
                and effect_may_have_occurred and not retry_permitted)
            or
            (observation_type = 'RECONCILING'
                and effect_may_have_occurred and not retry_permitted)
            or
            (observation_type = 'INVOCATION_DISPATCHED'
                and not retry_permitted)
            or
            (observation_type = 'PRE_EFFECT_RETRYABLE_FAILURE'
                and not effect_may_have_occurred and retry_permitted)
            or
            (observation_type = 'NO_EFFECT_CONFIRMED'
                and not effect_may_have_occurred)
        )
);

create index idx_outcome_attempt_reconcile
    on outcome_operation_attempt_observation(
        operation_id, observation_type, attempt_sequence desc, observed_at
    );

-- Existing lifecycle lock order is scope advisory, operation lifecycle advisory,
-- projection row when needed, operation row, then the append-only fact row.
create function enforce_outcome_attempt_sequence()
returns trigger
language plpgsql
as $$
declare
    previous outcome_operation_attempt_observation%rowtype;
    parent outcome_operation%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-compensation-order:' || new.tenant_surrogate || ':' ||
        new.case_id || ':' || new.outcome_epoch::text,
        0
    ));
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-operation-lifecycle:' || new.operation_id,
        0
    ));
    select value.* into parent
      from outcome_operation value
     where value.operation_id = new.operation_id
     for update;
    if not found then
        raise exception using errcode = '23503',
            message = 'Outcome attempt has no parent operation';
    end if;
    if parent.retry_class = 'NON_RETRYABLE' and new.retry_permitted then
        raise exception using errcode = '23514',
            message = 'NON_RETRYABLE Outcome operation cannot publish retry authority';
    end if;
    if exists (
        select 1 from outcome_operation_receipt receipt
         where receipt.operation_id = new.operation_id
    ) then
        raise exception using errcode = '23514',
            message = 'Terminal Outcome receipt forbids later attempts';
    end if;

    select value.* into previous
      from outcome_operation_attempt_observation value
     where value.operation_id = new.operation_id
     order by value.attempt_sequence desc
     limit 1;

    if not found then
        if new.attempt_sequence <> 1 or new.observation_type = 'RECONCILING' then
            raise exception using errcode = '23514',
                message = 'Outcome attempt sequence must start at one outside reconciliation';
        end if;
    else
        if new.attempt_sequence <> previous.attempt_sequence + 1 then
            raise exception using errcode = '23514',
                message = 'Outcome attempt sequence is not consecutive';
        end if;
        if previous.observation_type = 'AMBIGUOUS'
           and new.observation_type <> 'RECONCILING' then
            raise exception using errcode = '23514',
                message = 'AMBIGUOUS Outcome operation must enter RECONCILING';
        end if;
        if previous.observation_type = 'RECONCILING'
           and new.observation_type not in ('RECONCILING', 'NO_EFFECT_CONFIRMED') then
            raise exception using errcode = '23514',
                message = 'RECONCILING Outcome operation forbids another invocation';
        end if;
        if new.observation_type = 'INVOCATION_DISPATCHED'
           and (not previous.retry_permitted or parent.retry_class = 'NON_RETRYABLE') then
            raise exception using errcode = '23514',
                message = 'Outcome operation redispatch has no retry authority';
        end if;
    end if;
    return new;
end
$$;

create trigger trg_outcome_attempt_sequence
    before insert on outcome_operation_attempt_observation
    for each row execute function enforce_outcome_attempt_sequence();

create trigger trg_outcome_attempt_append_only
    before update or truncate on outcome_operation_attempt_observation
    for each statement execute function reject_append_only_mutation();

create trigger trg_outcome_attempt_delete_append_only
    before delete on outcome_operation_attempt_observation
    for each row execute function reject_append_only_mutation();

create table outcome_operation_receipt (
    receipt_id varchar(64) primary key,
    schema_version varchar(64) not null,
    receipt_hash varchar(64) not null,
    operation_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    outcome_epoch bigint not null,
    fencing_token bigint not null,
    request_hash varchar(64) not null,
    receipt_status varchar(16) not null,
    receipt_authority varchar(32) not null,
    external_receipt_id varchar(256) not null,
    response_ref varchar(1024) not null,
    response_hash varchar(64) not null,
    closure_disposition varchar(32) not null,
    completed_at timestamptz not null,
    constraint uq_outcome_receipt_operation unique (operation_id),
    constraint uq_outcome_receipt_external
        unique (receipt_authority, external_receipt_id),
    constraint uq_outcome_receipt_exact_parent
        unique (operation_id, receipt_id, receipt_hash),
    constraint fk_outcome_receipt_operation
        foreign key (
            operation_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, request_hash
        ) references outcome_operation(
            operation_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, request_hash
        ) on delete cascade,
    constraint ck_outcome_receipt_schema
        check (schema_version = 'outcome-operation-receipt.v1'),
    constraint ck_outcome_receipt_identity
        check (
            receipt_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and response_hash ~ '^[0-9a-f]{64}$'
            and response_ref ~ '^(urn|s3|minio):'
            and length(btrim(external_receipt_id)) between 1 and 256
        ),
    constraint ck_outcome_receipt_authority
        check (receipt_authority in (
            'DIRECT_RESPONSE', 'PROVIDER_CALLBACK',
            'PROVIDER_STATUS_QUERY', 'JAVA_RECONCILIATION'
        )),
    constraint ck_outcome_receipt_terminal
        check (
            (receipt_status = 'SUCCEEDED' and closure_disposition = 'SATISFIED')
            or
            (receipt_status = 'FAILED'
                and closure_disposition in ('BLOCKED', 'MANUAL_RECOVERY'))
        )
);

create index idx_outcome_receipt_case_status
    on outcome_operation_receipt(case_id, outcome_epoch, receipt_status, completed_at);

create function enforce_outcome_receipt_resolution()
returns trigger
language plpgsql
as $$
declare
    latest outcome_operation_attempt_observation%rowtype;
    parent outcome_operation%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-compensation-order:' || new.tenant_surrogate || ':' ||
        new.case_id || ':' || new.outcome_epoch::text,
        0
    ));
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-operation-lifecycle:' || new.operation_id,
        0
    ));
    select value.* into parent
      from outcome_operation value
     where value.operation_id = new.operation_id
     for update;
    if not found then
        raise exception using errcode = '23503',
            message = 'Outcome receipt has no parent operation';
    end if;
    if parent.operation_kind = 'OPERATION'
       and exists (
           select 1
             from outcome_operation compensation
            where compensation.projection_id = parent.projection_id
              and compensation.operation_kind = 'COMPENSATION'
       ) then
        raise exception using errcode = '23514',
            message = 'New original terminal receipts are forbidden after compensation starts';
    end if;
    select value.* into latest
      from outcome_operation_attempt_observation value
     where value.operation_id = new.operation_id
     order by value.attempt_sequence desc
     limit 1;
    if found
       and latest.observation_type = 'AMBIGUOUS' then
        raise exception using errcode = '23514',
            message = 'AMBIGUOUS Outcome operation requires RECONCILING before receipt';
    end if;
    if found
       and latest.observation_type = 'RECONCILING'
       and new.receipt_authority not in (
           'PROVIDER_CALLBACK', 'PROVIDER_STATUS_QUERY', 'JAVA_RECONCILIATION'
       ) then
        raise exception using errcode = '23514',
            message = 'RECONCILING Outcome operation requires authoritative resolution';
    end if;
    return new;
end
$$;

create trigger trg_outcome_receipt_resolution
    before insert on outcome_operation_receipt
    for each row execute function enforce_outcome_receipt_resolution();

create trigger trg_outcome_receipt_append_only
    before update or truncate on outcome_operation_receipt
    for each statement execute function reject_append_only_mutation();

create trigger trg_outcome_receipt_delete_append_only
    before delete on outcome_operation_receipt
    for each row execute function reject_append_only_mutation();

create table outcome_compensation_parent_binding (
    binding_id varchar(64) primary key,
    schema_version varchar(64) not null,
    binding_hash varchar(64) not null,
    child_operation_id varchar(64) not null unique,
    parent_operation_id varchar(64) not null,
    parent_receipt_id varchar(64) not null,
    parent_receipt_hash varchar(64) not null,
    compensation_policy_version varchar(64) not null,
    reverse_order bigint not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    outcome_epoch bigint not null,
    fencing_token bigint not null,
    created_at timestamptz not null,
    constraint uq_outcome_compensation_parent
        unique (parent_operation_id, child_operation_id),
    constraint uq_outcome_compensation_parent_once
        unique (parent_operation_id),
    constraint uq_outcome_compensation_reverse_order
        unique (tenant_surrogate, case_id, outcome_epoch, reverse_order),
    constraint fk_outcome_compensation_child
        foreign key (child_operation_id) references outcome_operation(operation_id)
        on delete cascade,
    constraint fk_outcome_compensation_parent_operation
        foreign key (parent_operation_id) references outcome_operation(operation_id)
        on delete cascade,
    constraint fk_outcome_compensation_parent_receipt
        foreign key (parent_operation_id, parent_receipt_id, parent_receipt_hash)
        references outcome_operation_receipt(operation_id, receipt_id, receipt_hash)
        on delete cascade,
    constraint ck_outcome_compensation_schema
        check (schema_version = 'outcome-compensation-parent-binding.v1'),
    constraint ck_outcome_compensation_identity
        check (
            child_operation_id <> parent_operation_id
            and binding_hash ~ '^[0-9a-f]{64}$'
            and parent_receipt_hash ~ '^[0-9a-f]{64}$'
            and length(btrim(compensation_policy_version)) between 1 and 64
            and reverse_order >= 1
        )
);

create index idx_outcome_compensation_parent
    on outcome_compensation_parent_binding(parent_operation_id, created_at);

create function enforce_outcome_compensation_parent()
returns trigger
language plpgsql
as $$
declare
    child outcome_operation%rowtype;
    parent outcome_operation%rowtype;
    parent_receipt outcome_operation_receipt%rowtype;
    expected_parent_operation_id varchar(64);
    expected_parent_receipt_id varchar(64);
    expected_parent_receipt_hash varchar(64);
    expected_reverse_order bigint;
    existing_binding_count bigint;
    projection outcome_process_projection%rowtype;
    child_projection_id varchar(64);
    reserved_required_operation_count bigint;
    terminal_required_operation_count bigint;
    unresolved_reconciliation_count bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'outcome-compensation-order:' || new.tenant_surrogate || ':' ||
        new.case_id || ':' || new.outcome_epoch::text,
        0
    ));
    select value.projection_id into child_projection_id
      from outcome_operation value
     where value.operation_id = new.child_operation_id;
    select value.* into projection
      from outcome_process_projection value
     where value.projection_id = child_projection_id
     for update;
    if projection.process_state in (
        'READY_TO_CLOSE', 'CLOSED', 'EVALUATION_PENDING', 'EVALUATED'
    ) then
        raise exception using errcode = '23514',
            message = 'Outcome compensation binding is forbidden after closure readiness';
    end if;
    select value.* into child
      from outcome_operation value
     where value.operation_id = new.child_operation_id
     for key share;
    select value.* into parent
      from outcome_operation value
     where value.operation_id = new.parent_operation_id
     for key share;
    select value.* into parent_receipt
      from outcome_operation_receipt value
     where value.operation_id = new.parent_operation_id
       and value.receipt_id = new.parent_receipt_id
       and value.receipt_hash = new.parent_receipt_hash
     for key share;
    select count(*),
           count(receipt.operation_id),
           count(*) filter (
               where receipt.operation_id is null
                 and latest.observation_type in ('AMBIGUOUS', 'RECONCILING')
           )
      into reserved_required_operation_count,
           terminal_required_operation_count,
           unresolved_reconciliation_count
      from outcome_operation original
      left join outcome_operation_receipt receipt
        on receipt.operation_id = original.operation_id
      left join lateral (
          select observation.observation_type
            from outcome_operation_attempt_observation observation
           where observation.operation_id = original.operation_id
           order by observation.attempt_sequence desc
           limit 1
      ) latest on true
     where original.projection_id = child.projection_id
       and original.operation_kind = 'OPERATION'
       and original.required_for_closure;
    if reserved_required_operation_count <> projection.expected_required_operation_count
       or not outcome_required_action_set_is_exact(child.projection_id) then
        raise exception using errcode = '23514',
            message = 'Outcome compensation requires the exact approved required original operation set';
    end if;
    if terminal_required_operation_count <> projection.expected_required_operation_count
       or unresolved_reconciliation_count <> 0 then
        raise exception using errcode = '23514',
            message = 'Outcome compensation barrier requires every approved required original operation terminal and resolved';
    end if;
    select count(*) into existing_binding_count
      from outcome_compensation_parent_binding value
     where value.tenant_surrogate = new.tenant_surrogate
       and value.case_id = new.case_id
       and value.outcome_epoch = new.outcome_epoch;
    expected_reverse_order := existing_binding_count + 1;
    select ranked.operation_id,
           ranked.receipt_id,
           ranked.receipt_hash
      into expected_parent_operation_id,
           expected_parent_receipt_id,
           expected_parent_receipt_hash
      from (
          select candidate.operation_id,
                 candidate_receipt.receipt_id,
                 candidate_receipt.receipt_hash,
                 row_number() over (
                     order by candidate.operation_sequence desc, candidate.operation_id desc
                 ) as ranked_reverse_order
            from outcome_operation candidate
            join outcome_operation_receipt candidate_receipt
              on candidate_receipt.operation_id = candidate.operation_id
             and candidate_receipt.receipt_status = 'SUCCEEDED'
             and candidate_receipt.closure_disposition = 'SATISFIED'
           where candidate.tenant_surrogate = new.tenant_surrogate
             and candidate.case_id = new.case_id
             and candidate.outcome_epoch = new.outcome_epoch
             and candidate.fencing_token = new.fencing_token
             and candidate.operation_kind = 'OPERATION'
             and candidate.compensable
      ) ranked
     where ranked.ranked_reverse_order = expected_reverse_order;
    if child.operation_kind <> 'COMPENSATION'
       or parent.operation_kind <> 'OPERATION'
       or not parent.compensable
       or parent_receipt.receipt_status <> 'SUCCEEDED'
       or parent_receipt.closure_disposition <> 'SATISFIED'
       or new.reverse_order <> expected_reverse_order
       or expected_parent_operation_id is null
       or (new.parent_operation_id, new.parent_receipt_id, new.parent_receipt_hash)
          is distinct from
          (expected_parent_operation_id, expected_parent_receipt_id,
              expected_parent_receipt_hash)
       or (child.tenant_surrogate, child.case_id, child.outcome_epoch, child.fencing_token)
          is distinct from
          (parent.tenant_surrogate, parent.case_id, parent.outcome_epoch, parent.fencing_token)
       or (new.tenant_surrogate, new.case_id, new.outcome_epoch, new.fencing_token)
          is distinct from
          (parent.tenant_surrogate, parent.case_id, parent.outcome_epoch, parent.fencing_token) then
        raise exception using errcode = '23514',
            message = 'Outcome compensation parent operation or receipt binding is invalid';
    end if;
    return new;
end
$$;

create trigger trg_outcome_compensation_parent
    before insert on outcome_compensation_parent_binding
    for each row execute function enforce_outcome_compensation_parent();

create trigger trg_outcome_compensation_append_only
    before update or truncate on outcome_compensation_parent_binding
    for each statement execute function reject_append_only_mutation();

create trigger trg_outcome_compensation_delete_append_only
    before delete on outcome_compensation_parent_binding
    for each row execute function reject_append_only_mutation();

create function require_outcome_compensation_parent_at_commit()
returns trigger
language plpgsql
as $$
begin
    if new.operation_kind = 'COMPENSATION'
       and not exists (
           select 1
             from outcome_compensation_parent_binding binding
            where binding.child_operation_id = new.operation_id
       ) then
        raise exception using errcode = '23514',
            message = 'COMPENSATION Outcome operation requires an exact parent receipt';
    end if;
    return null;
end
$$;

create constraint trigger trg_outcome_compensation_parent_required
    after insert on outcome_operation
    deferrable initially deferred
    for each row execute function require_outcome_compensation_parent_at_commit();

create view outcome_operation_state as
select operation.projection_id,
       operation.operation_id,
       operation.operation_kind,
       operation.operation_sequence,
       operation.operation_key,
       operation.request_hash,
       operation.required_for_closure,
       operation.compensable,
       operation.tenant_surrogate,
       operation.case_id,
       operation.outcome_epoch,
       operation.fencing_token,
       operation.process_revision,
       operation.outcome_revision,
       case
           when receipt.receipt_status = 'SUCCEEDED' then 'SUCCEEDED'
           when receipt.receipt_status = 'FAILED'
                and receipt.closure_disposition = 'MANUAL_RECOVERY' then 'MANUAL_RECOVERY'
           when receipt.receipt_status = 'FAILED' then 'FAILED'
           when attempt.observation_type is not null then attempt.observation_type
           else 'RESERVED'
       end as operation_status,
       receipt.receipt_id,
       receipt.receipt_hash,
       receipt.receipt_status,
       receipt.closure_disposition,
       receipt.operation_id is not null as java_authoritative,
       compensation.parent_operation_id,
       compensation.parent_receipt_id,
       compensation.parent_receipt_hash,
       compensation.compensation_policy_version,
       compensation.reverse_order
  from outcome_operation operation
  left join outcome_operation_receipt receipt
    on receipt.operation_id = operation.operation_id
  left join outcome_compensation_parent_binding compensation
    on compensation.child_operation_id = operation.operation_id
  left join lateral (
      select value.observation_type
        from outcome_operation_attempt_observation value
       where value.operation_id = operation.operation_id
       order by value.attempt_sequence desc
       limit 1
  ) attempt on true;

create view outcome_closure_readiness as
select projection.projection_id,
       projection.tenant_surrogate,
       projection.case_id,
       projection.outcome_epoch,
       projection.fencing_token,
       projection.expected_required_operation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and operation.operation_kind = 'OPERATION'
       ) as required_operation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and receipt.operation_id is null
       ) as unresolved_operation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and receipt.operation_id is not null
             and (
                 receipt.receipt_status <> 'SUCCEEDED'
                 or receipt.closure_disposition <> 'SATISFIED'
             )
       ) as blocked_operation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and attempt.observation_type in ('AMBIGUOUS', 'RECONCILING')
             and receipt.operation_id is null
       ) as reconciliation_operation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and operation.operation_kind = 'COMPENSATION'
             and (
                 receipt.operation_id is null
                 or receipt.receipt_status <> 'SUCCEEDED'
                 or receipt.closure_disposition <> 'SATISFIED'
             )
       ) as pending_compensation_count,
       count(operation.operation_id) filter (
           where operation.required_for_closure
             and operation.operation_kind = 'OPERATION'
       ) = projection.expected_required_operation_count
       and count(operation.operation_id) filter (
           where operation.required_for_closure
             and (
                 receipt.operation_id is null
                 or receipt.receipt_status <> 'SUCCEEDED'
                 or receipt.closure_disposition <> 'SATISFIED'
              )
       ) = 0
       and outcome_required_action_set_is_exact(projection.projection_id)
       and outcome_required_action_records_succeeded(projection.projection_id)
       as closure_ready
  from outcome_process_projection projection
  left join outcome_operation operation
    on operation.projection_id = projection.projection_id
  left join outcome_operation_receipt receipt
    on receipt.operation_id = operation.operation_id
  left join lateral (
      select value.observation_type
        from outcome_operation_attempt_observation value
       where value.operation_id = operation.operation_id
       order by value.attempt_sequence desc
       limit 1
  ) attempt on true
 group by projection.projection_id, projection.tenant_surrogate,
          projection.case_id, projection.outcome_epoch, projection.fencing_token,
          projection.expected_required_operation_count;
