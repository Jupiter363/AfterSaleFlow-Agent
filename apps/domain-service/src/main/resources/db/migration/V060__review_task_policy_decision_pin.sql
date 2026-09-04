alter table review_task
    add column policy_decision_id varchar(64);

-- Legacy rows that have a resolvable historical policy receive the same deterministic
-- created-at/id selection used before this migration. Fixtures without any matching policy stay
-- nullable for compatibility; every new target Review path requires a non-null exact pin.
update review_task task
   set policy_decision_id = (
       select policy.id
         from approval_policy_decision policy
        where policy.case_id = task.case_id
          and policy.plan_id = task.plan_id
          and policy.created_at <= task.created_at
        order by policy.created_at desc, policy.id desc
        limit 1)
 where task.policy_decision_id is null
   and exists (
       select 1
         from approval_policy_decision policy
        where policy.case_id = task.case_id
          and policy.plan_id = task.plan_id
          and policy.created_at <= task.created_at);

alter table approval_policy_decision
    add constraint uq_policy_decision_scope
        unique (id, case_id, plan_id);

alter table review_task
    add constraint fk_review_task_policy_decision_scope
        foreign key (policy_decision_id, case_id, plan_id)
        references approval_policy_decision(id, case_id, plan_id);

create index idx_review_task_policy_decision_scope
    on review_task(policy_decision_id, case_id, plan_id);

-- A rolling old node may still insert a null legacy pin. The first current-node authorization or
-- decision may set that null to its deterministic historical policy exactly once; no later rewrite
-- is authority-preserving.
create function enforce_review_task_policy_decision_pin_immutable()
returns trigger
language plpgsql
as $$
begin
    if old.policy_decision_id is not null
       and new.policy_decision_id is distinct from old.policy_decision_id then
        raise exception using errcode = '23514',
            message = 'review_task policy_decision_id is immutable after its first exact pin';
    end if;
    return new;
end;
$$;

create trigger trg_review_task_policy_decision_pin_immutable
    before update of policy_decision_id on review_task
    for each row execute function enforce_review_task_policy_decision_pin_immutable();

-- Preserve ordinary legacy idempotent replay without guessing an identity. A decided row receives
-- the JSON identity only when its exact task pin, policy version and durable approval all agree.
-- This update deliberately leaves authority_source unchanged.
update review_task task
   set decision_json = jsonb_set(
           task.decision_json,
           '{policy_decision_id}',
           to_jsonb(task.policy_decision_id),
           true),
       updated_at = current_timestamp
  from approval_policy_decision policy
 where policy.id = task.policy_decision_id
   and policy.case_id = task.case_id
   and policy.plan_id = task.plan_id
   and not (task.decision_json ? 'policy_decision_id')
   and task.decision_json ->> 'policy_version' = policy.policy_version
   and exists (
       select 1
         from human_review_record approval
        where approval.review_task_id = task.id
          and approval.case_id = task.case_id
          and approval.plan_id = task.plan_id
          and approval.review_packet_id = task.packet_id
          and approval.policy_version = policy.policy_version
          and approval.decision_type = task.decision_json ->> 'decision'
          and approval.original_plan_json = task.decision_json -> 'original_plan'
          and approval.approved_plan_json = task.decision_json -> 'approved_plan'
          and approval.action_snapshot_hash =
              task.decision_json ->> 'approved_action_hash'
   );

-- During a rolling deployment an old node can still write a decision after V060. If that task
-- already carries an exact pin, enrich the JSON in the same row write. A null pin stays null and
-- therefore remains fail-closed on a current-node replay.
create function enrich_review_decision_with_exact_policy_identity()
returns trigger
language plpgsql
as $$
begin
    if new.policy_decision_id is not null
       and jsonb_typeof(new.decision_json) = 'object'
       and not (new.decision_json ? 'policy_decision_id')
       and exists (
           select 1
             from approval_policy_decision policy
            where policy.id = new.policy_decision_id
              and policy.case_id = new.case_id
              and policy.plan_id = new.plan_id
              and policy.policy_version = new.decision_json ->> 'policy_version'
       ) then
        new.decision_json := jsonb_set(
            new.decision_json,
            '{policy_decision_id}',
            to_jsonb(new.policy_decision_id),
            true);
    end if;
    return new;
end;
$$;

create trigger trg_review_task_decision_policy_identity_insert
    before insert on review_task
    for each row execute function enrich_review_decision_with_exact_policy_identity();

create trigger trg_review_task_decision_policy_identity_update
    before update of decision_json, policy_decision_id on review_task
    for each row execute function enrich_review_decision_with_exact_policy_identity();

-- Old Review nodes used LEGACY_NONE even after they had durably emitted the target Review event,
-- command and Outcome handoff. Upgrade only rows for which that complete chain proves the exact
-- approval, command, packet and pinned policy. The policy id may be absent from an old event, but
-- when present it must agree with the exact task pin. New events are checked more strictly by the
-- invocation loader.
create function backfill_exact_legacy_target_review_decisions(p_command_id varchar default null)
returns bigint
language plpgsql
as $$
declare
    updated_count bigint;
begin
    with exact_target_chain as (
        select distinct task.id as task_id, policy.id as policy_decision_id
          from review_task task
          join approval_policy_decision policy
            on policy.id = task.policy_decision_id
           and policy.case_id = task.case_id
           and policy.plan_id = task.plan_id
          join human_review_record approval
            on approval.review_task_id = task.id
           and approval.case_id = task.case_id
           and approval.plan_id = task.plan_id
           and approval.review_packet_id = task.packet_id
           and approval.policy_version = policy.policy_version
          join case_timeline_event event
            on event.case_id = task.case_id
           and event.event_type = 'TARGET_REVIEW_DECISION_COMMITTED'
           and event.event_key = 'target-review-decision:' || approval.id
           and event.event_json ->> 'schema_version' =
               'production-runtime-review-human-decision-event.v1'
           and event.event_json ->> 'case_id' = task.case_id
           and event.event_json ->> 'review_task_id' = task.id
           and event.event_json ->> 'approval_record_id' = approval.id
           and event.event_json ->> 'approval_hash' = approval.action_hash
           and event.event_json ->> 'packet_id' = task.packet_id
           and event.event_json ->> 'packet_version' = approval.review_packet_version::text
           and event.event_json ->> 'reviewer_id' = approval.reviewer_id
           and event.event_json ->> 'decision' = approval.decision_type
           and event.event_json -> 'original_plan' = approval.original_plan_json
           and event.event_json -> 'approved_plan' = approval.approved_plan_json
           and event.event_json ->> 'approved_action_snapshot_hash' =
               approval.action_snapshot_hash
           and event.event_json ->> 'policy_version' = policy.policy_version
           and (
               not (event.event_json ? 'policy_decision_id')
               or event.event_json ->> 'policy_decision_id' = policy.id
           )
          join case_command command
            on command.case_id = task.case_id
           and command.command_id = event.event_json ->> 'command_id'
           and command.command_type = 'REVIEW_DECISION'
           and command.room_type = 'REVIEW'
           and command.actor_id = approval.reviewer_id
           and command.actor_role = 'PLATFORM_REVIEWER'
           and command.room_epoch::text = event.event_json ->> 'room_epoch'
           and command.expected_process_revision::text =
               event.event_json ->> 'case_process_revision'
           and command.payload_schema_version =
               'production-runtime-review-human-decision-event.v1'
           and command.payload_uri = 'urn:production-runtime:review-decision:' || event.id
           and command.payload_sha256 ~ '^[0-9a-f]{64}$'
          join notification_outbox handoff
            on handoff.case_id = task.case_id
           and handoff.event_type = 'TARGET_REVIEW_OUTCOME_HANDOFF'
           and handoff.business_event_key like 'target-review-handoff:%'
           and handoff.event_payload_json ->> 'schema_version' =
               'production-runtime-review-outcome-handoff.v1'
           and handoff.event_payload_json ->> 'handoff_id' = handoff.id
           and handoff.event_payload_json ->> 'activation_id' ~
               '^p9act[.]v1[.][0-9a-f]{32}$'
           and handoff.event_payload_json ->> 'activation_manifest_hash' ~ '^[0-9a-f]{64}$'
           and handoff.event_payload_json ->> 'handoff_hash' ~ '^[0-9a-f]{64}$'
           and handoff.event_payload_json ->> 'tenant_surrogate' = command.tenant_surrogate
           and handoff.event_payload_json ->> 'case_id' = task.case_id
           and handoff.event_payload_json ->> 'command_id' = command.command_id
           and handoff.event_payload_json ->> 'room_epoch' = command.room_epoch::text
           and handoff.event_payload_json ->> 'room_fencing_token' =
               event.event_json ->> 'fencing_token'
           and handoff.event_payload_json #>> '{human_decision,schema_version}' =
               'production-runtime-review-human-decision-receipt.v1'
           and handoff.event_payload_json #>> '{human_decision,decision_authority}' =
               'JAVA_HUMAN'
           and handoff.event_payload_json #>> '{human_decision,decision_record_id}' = approval.id
           and handoff.event_payload_json #>> '{human_decision,decision_record_hash}' =
               command.payload_sha256
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,schema_version}' =
               'outcome-reviewer-decision-receipt.v1'
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,case_id}' = task.case_id
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,receipt_id}' = approval.id
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,receipt_hash}' = command.payload_sha256
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,review_task_id}' = task.id
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,frozen_review_packet_ref}' = task.packet_id
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,frozen_review_packet_hash}' =
               event.event_json ->> 'packet_content_hash'
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,action_snapshot_hash}' =
               event.event_json ->> 'frozen_action_snapshot_hash'
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,decision_record_ref}' = approval.id
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,decision_record_hash}' = command.payload_sha256
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,decision}' = approval.decision_type
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,request_hash}' =
               task.decision_json ->> 'request_hash'
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,policy_version}' = policy.policy_version
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,epoch}' = command.room_epoch::text
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,fence}' = event.event_json ->> 'fencing_token'
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,committed_event_sequence}' = event.sequence_no::text
           and handoff.event_payload_json #>>
               '{human_decision,outcome_receipt,synthetic_only}' = 'false'
         where task.decision_json ->> 'authority_source' = 'LEGACY_NONE'
           and task.decision_json ->> 'decision' = approval.decision_type
           and task.decision_json ->> 'policy_version' = policy.policy_version
           and task.decision_json -> 'original_plan' = approval.original_plan_json
           and task.decision_json -> 'approved_plan' = approval.approved_plan_json
           and task.decision_json ->> 'approved_action_hash' = approval.action_snapshot_hash
           and (
               not (task.decision_json ? 'policy_decision_id')
               or task.decision_json ->> 'policy_decision_id' = policy.id
           )
           and (p_command_id is null or command.command_id = p_command_id)
           and (
               (
                   approval.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
                   and handoff.event_payload_json #>>
                       '{human_decision,outcome_receipt,execution_authorized}' = 'true'
                   and handoff.event_payload_json #>>
                       '{human_decision,outcome_receipt,approved_action_snapshot_ref}' =
                       'approval:' || approval.id || ':action'
                   and handoff.event_payload_json #>>
                       '{human_decision,outcome_receipt,approved_action_snapshot_hash}' =
                       approval.action_snapshot_hash
                   and handoff.event_payload_json #>>
                       '{human_decision,outcome_receipt,operation_key_hash}' ~ '^[0-9a-f]{64}$'
               )
               or (
                   approval.decision_type in
                       ('REJECT', 'REQUEST_MORE_EVIDENCE', 'ESCALATE_MANUAL')
                   and handoff.event_payload_json #>>
                       '{human_decision,outcome_receipt,execution_authorized}' = 'false'
                   and handoff.event_payload_json #>
                       '{human_decision,outcome_receipt,approved_action_snapshot_ref}' is null
                   and handoff.event_payload_json #>
                       '{human_decision,outcome_receipt,approved_action_snapshot_hash}' is null
                   and handoff.event_payload_json #>
                       '{human_decision,outcome_receipt,operation_key_hash}' is null
               )
           )
    )
    update review_task task
       set decision_json = jsonb_set(
               jsonb_set(
                   task.decision_json,
                   '{policy_decision_id}',
                   to_jsonb(exact.policy_decision_id),
                   true),
               '{authority_source}',
               to_jsonb('TARGET_REVIEW'::text),
               true),
           updated_at = current_timestamp
      from exact_target_chain exact
     where task.id = exact.task_id
       and task.decision_json ->> 'authority_source' = 'LEGACY_NONE';
    get diagnostics updated_count = row_count;
    return updated_count;
end;
$$;

do $$
begin
    perform backfill_exact_legacy_target_review_decisions(null);
end;
$$;

-- A deferred constraint trigger observes the whole transaction, independent of Hibernate insert
-- ordering. It covers old nodes that finish a target command after V060 was already installed.
create function reconcile_legacy_target_review_decision_after_command()
returns trigger
language plpgsql
as $$
begin
    perform backfill_exact_legacy_target_review_decisions(new.command_id);
    return new;
end;
$$;

create constraint trigger trg_reconcile_legacy_target_review_decision
    after insert on case_command
    deferrable initially deferred
    for each row execute function reconcile_legacy_target_review_decision_after_command();

alter table review_task
    add constraint uq_review_task_policy_binding
        unique (id, case_id, plan_id, policy_decision_id);

alter table case_room_epoch
    add constraint uq_case_room_epoch_review_binding
        unique (id, tenant_surrogate, case_id, room_epoch, fencing_token);

alter table hearing_review_handoff_fact
    add constraint uq_hearing_review_handoff_task_binding
        unique (id, case_id, review_task_id);

-- The Review epoch is authorized by the exact task created by the Hearing handoff. This table is
-- intentionally not backfilled from "the latest open task": legacy epochs without a durable
-- handoff binding remain unavailable to target SQL.
create table production_runtime_review_epoch_task_binding (
    epoch_id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    review_task_id varchar(64) not null,
    plan_id varchar(64) not null,
    policy_decision_id varchar(64) not null,
    source_handoff_id varchar(64) not null unique,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_review_epoch_task_epoch
        foreign key (epoch_id, tenant_surrogate, case_id, room_epoch, room_fencing_token)
        references case_room_epoch(id, tenant_surrogate, case_id, room_epoch, fencing_token),
    constraint fk_review_epoch_task_task_policy
        foreign key (review_task_id, case_id, plan_id, policy_decision_id)
        references review_task(id, case_id, plan_id, policy_decision_id),
    constraint fk_review_epoch_task_policy_scope
        foreign key (policy_decision_id, case_id, plan_id)
        references approval_policy_decision(id, case_id, plan_id),
    constraint fk_review_epoch_task_handoff
        foreign key (source_handoff_id, case_id, review_task_id)
        references hearing_review_handoff_fact(id, case_id, review_task_id),
    constraint uq_review_epoch_task_coordinates
        unique (tenant_surrogate, case_id, room_epoch, room_fencing_token),
    constraint ck_review_epoch_task_coordinates
        check (room_epoch > 0 and room_fencing_token > 0)
);

create function reject_review_epoch_task_binding_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using errcode = '23514',
        message = 'target Review epoch task binding is append-only';
end;
$$;

create trigger trg_review_epoch_task_binding_reject_row_mutation
    before update or delete on production_runtime_review_epoch_task_binding
    for each row execute function reject_review_epoch_task_binding_mutation();

create trigger trg_review_epoch_task_binding_reject_truncate
    before truncate on production_runtime_review_epoch_task_binding
    for each statement execute function reject_review_epoch_task_binding_mutation();
