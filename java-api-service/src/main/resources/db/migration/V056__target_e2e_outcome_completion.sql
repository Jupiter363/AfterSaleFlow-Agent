-- TARGET_E2E_CANDIDATE durable relay facts. These rows are append-only evidence; the Temporal
-- workflow only applies exact facts read back from this ledger.
create table target_e2e_outcome_completion_fact (
    workflow_id varchar(128) not null,
    case_id varchar(64) not null,
    outcome_epoch bigint not null,
    fencing_token bigint not null,
    human_receipt_id varchar(128) not null,
    human_receipt_hash varchar(64) not null,
    fact_kind varchar(32) not null,
    revision bigint not null,
    committed_event_sequence bigint not null,
    payload_json jsonb not null,
    payload_hash varchar(64) not null,
    committed_at timestamptz not null,
    committed_by varchar(128) not null,
    primary key (workflow_id, revision),
    unique (workflow_id, committed_event_sequence),
    constraint fk_target_e2e_outcome_completion_case
      foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_target_e2e_outcome_completion_shape check (
      outcome_epoch >= 1 and fencing_token >= 1 and revision >= 1
      and committed_event_sequence >= 1
      and human_receipt_hash ~ '^[0-9a-f]{64}$'
      and payload_hash ~ '^[0-9a-f]{64}$'
      and fact_kind in ('OPERATION_COMMAND', 'OPERATION_RECEIPT', 'CLOSURE_RECEIPT', 'EVALUATION_RECEIPT')
    )
);

create function enforce_target_e2e_outcome_completion_fact()
returns trigger language plpgsql as $$
declare previous target_e2e_outcome_completion_fact%rowtype;
begin
  select * into previous from target_e2e_outcome_completion_fact
   where workflow_id = new.workflow_id order by revision desc limit 1 for update;
  if found and (new.revision <> previous.revision + 1
      or new.committed_event_sequence <= previous.committed_event_sequence
      or new.case_id <> previous.case_id or new.outcome_epoch <> previous.outcome_epoch
      or new.fencing_token <> previous.fencing_token or new.human_receipt_id <> previous.human_receipt_id
      or new.human_receipt_hash <> previous.human_receipt_hash) then
    raise exception using errcode = '23514', message = 'target Outcome completion relay is not causally fenced';
  end if;
  return new;
end $$;

create trigger trg_target_e2e_outcome_completion_fact
before insert on target_e2e_outcome_completion_fact
for each row execute function enforce_target_e2e_outcome_completion_fact();
create trigger trg_target_e2e_outcome_completion_fact_immutable
before update or delete on target_e2e_outcome_completion_fact
for each row execute function reject_append_only_mutation();
create trigger trg_target_e2e_outcome_completion_fact_no_truncate
before truncate on target_e2e_outcome_completion_fact
for each statement execute function reject_append_only_mutation();

-- V045 deliberately kept the ledger engineering-only. TARGET_E2E_CANDIDATE needs the same
-- append-only tables under a formal writer; all non-TEMPORAL trigger behavior is preserved.
alter table outcome_process_projection
    drop constraint ck_outcome_projection_engineering_mode;
alter table outcome_process_projection
    add constraint ck_outcome_projection_engineering_mode check (
      (writer_mode = 'LEGACY' and runtime_mode = 'DISABLED')
      or (writer_mode = 'SHADOW' and runtime_mode = 'JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW'
          and fencing_token > 0)
      or (writer_mode = 'TEMPORAL' and runtime_mode = 'TEMPORAL' and fencing_token > 0)
    );

-- Keep the original V045 authority trigger unchanged for every non-TEMPORAL writer. Formal
-- target projections use an explicit trigger instead of rewriting database source text.
alter function enforce_outcome_projection_authority() rename to enforce_outcome_projection_authority_v045;
drop trigger trg_outcome_projection_authority on outcome_process_projection;
create trigger trg_outcome_projection_authority_v045 before insert or update on outcome_process_projection
for each row when (new.writer_mode <> 'TEMPORAL') execute function enforce_outcome_projection_authority_v045();

-- Full V045 authority program, explicitly retained for formal TEMPORAL projections.
create or replace function enforce_target_temporal_outcome_projection_authority()
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
            or jsonb_typeof(entry.value -> 'action_type') <> 'string'
            or nullif(btrim(coalesce(entry.value ->> 'action_type', '')), '') is null
            or length(entry.value ->> 'action_type') > 64
            or jsonb_typeof(entry.value -> 'idempotency_key') <> 'string'
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
           or new.updated_at < old.updated_at
           or (authority.writer_mode <> 'TEMPORAL' and (
               new.process_revision <> authority.process_revision
               or new.outcome_revision <> authority.room_revision
           )) then
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
            perform outcome_lock_required_action_records(new.projection_id);
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
create trigger trg_target_temporal_outcome_projection_authority
before insert or update on outcome_process_projection
for each row when (new.writer_mode = 'TEMPORAL')
execute function enforce_target_temporal_outcome_projection_authority();
