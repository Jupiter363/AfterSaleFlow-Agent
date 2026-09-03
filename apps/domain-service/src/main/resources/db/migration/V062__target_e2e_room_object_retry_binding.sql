-- Separate immutable object bytes from their per-command capabilities so a later AgentRun
-- attempt can reuse the exact same logical input without mutating the original admission.
create table target_e2e_room_object_binding (
    object_ref varchar(512) not null references target_e2e_room_object_index(object_ref),
    object_kind varchar(24) not null,
    artifact_id varchar(128) not null,
    schema_version varchar(128) not null,
    activation_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    command_id varchar(128) not null,
    logical_run_id varchar(128) not null,
    attempt_id varchar(128) not null,
    checkpoint_ns varchar(128),
    checkpoint_id varchar(128),
    cognitive_revision bigint,
    created_at timestamptz not null default current_timestamp,
    primary key (
        activation_id, tenant_surrogate, case_id, room_type, room_epoch,
        room_fencing_token, command_id, object_ref),
    constraint ck_target_e2e_room_object_binding_shape check (
        object_kind in ('COMMAND_INPUT', 'MANIFEST_ASSET', 'PROPOSAL')
        and room_type in ('EVIDENCE', 'HEARING', 'REVIEW')
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and (object_kind = 'PROPOSAL') = (checkpoint_ns is not null)
        and (object_kind = 'PROPOSAL') = (checkpoint_id is not null)
        and (object_kind = 'PROPOSAL') = (cognitive_revision is not null)
    )
);

insert into target_e2e_room_object_binding (
    object_ref, object_kind, artifact_id, schema_version,
    activation_id, tenant_surrogate, case_id, room_type,
    room_epoch, room_fencing_token, command_id, logical_run_id, attempt_id,
    checkpoint_ns, checkpoint_id, cognitive_revision, created_at)
select object_ref, object_kind, artifact_id, schema_version,
       activation_id, tenant_surrogate, case_id, room_type,
       room_epoch, room_fencing_token, command_id, logical_run_id, attempt_id,
       case when object_kind = 'PROPOSAL' then coalesce(checkpoint_ns, '') else null end,
       checkpoint_id, cognitive_revision, created_at
  from target_e2e_room_object_index;

-- A proposal identity is first-wins inside one exact attempt authority. Object bytes may be
-- content-identical across attempts, but a later attempt must still append its own capability.
create unique index uq_target_e2e_room_object_binding_proposal_identity
    on target_e2e_room_object_binding (
        activation_id, tenant_surrogate, case_id, room_type, room_epoch,
        room_fencing_token, command_id, logical_run_id, attempt_id,
        artifact_id, schema_version, checkpoint_ns, checkpoint_id, cognitive_revision)
    where object_kind = 'PROPOSAL';

create index ix_target_e2e_room_object_binding_admitted on target_e2e_room_object_binding (
    activation_id, tenant_surrogate, case_id, room_type, room_epoch,
    room_fencing_token, command_id, object_ref);
create index ix_target_e2e_room_object_binding_proposal on target_e2e_room_object_binding (
    activation_id, tenant_surrogate, case_id, room_type, room_epoch,
    room_fencing_token, command_id, logical_run_id, attempt_id);

create trigger trg_target_e2e_room_object_binding_immutable
before update or delete on target_e2e_room_object_binding
for each row execute function reject_target_e2e_append_only_mutation();
create trigger trg_target_e2e_room_object_binding_no_truncate
before truncate on target_e2e_room_object_binding
for each statement execute function reject_target_e2e_append_only_mutation();

revoke all on target_e2e_room_object_binding from public;

-- V049 admitted only the initial Intake attempt. Later attempts are still required to carry
-- the exact same activation/admission authority, but use the explicit retry context schema and
-- must bind an adjacent predecessor in the durable AgentRun ledger.
create or replace function enforce_target_e2e_intake_command_material()
returns trigger
language plpgsql
as $$
declare
    admission_row target_e2e_command_admission%rowtype;
    context_document jsonb;
    context_target_schema text;
    context_logical_run_id text;
    context_attempt_id text;
    context_attempt_no_text text;
    context_attempt_no bigint;
    context_previous_attempt_id text;
    matched_attempt_id text;
    matched_previous_attempt_id text;
begin
    select * into admission_row
      from target_e2e_command_admission
     where admission_id = new.admission_id
     for share;

    if not found
       or admission_row.activation_id is distinct from new.activation_id
       or admission_row.activation_manifest_hash is distinct from new.activation_manifest_hash
       or admission_row.execution_lane is distinct from new.execution_lane
       or admission_row.isolated_domain_db_binding_hash is distinct from
            new.isolated_domain_db_binding_hash
       or admission_row.tenant_surrogate is distinct from new.tenant_surrogate
       or admission_row.case_id is distinct from new.case_id
       or admission_row.command_id is distinct from new.command_id
       or admission_row.command_hash is distinct from new.command_hash
       or admission_row.command_envelope_hash is distinct from new.command_envelope_hash
       or admission_row.room_epoch is distinct from new.room_epoch
       or admission_row.room_fencing_token is distinct from new.room_fencing_token
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material must exactly bind its command admission';
    end if;

    context_document := new.context_canonical_json::jsonb;
    if context_document #>> '{schemaVersion}' is distinct from new.context_schema_version
       or context_document #>> '{targetAgentRun,executionLane}' is distinct from
            new.execution_lane
       or context_document #>> '{targetAgentRun,activationId}' is distinct from new.activation_id
       or context_document #>> '{targetAgentRun,activationManifestHash}' is distinct from
            new.activation_manifest_hash
       or context_document #>> '{targetAgentRun,roomFencingToken}' is distinct from
            new.room_fencing_token::text
       or context_document #>> '{targetAgentRun,commandHash}' is distinct from new.command_hash
       or context_document #>> '{targetAgentRun,commandEnvelopeHash}' is distinct from
            new.command_envelope_hash
       or context_document #>> '{targetAgentRun,request,command,tenant_surrogate}' is distinct from
            new.tenant_surrogate
       or context_document #>> '{targetAgentRun,request,command,case_id}' is distinct from
            new.case_id
       or context_document #>> '{targetAgentRun,request,command,command_id}' is distinct from
            new.command_id
       or context_document #>> '{targetAgentRun,request,command,room_type}' is distinct from 'INTAKE'
       or context_document #>> '{targetAgentRun,request,command,room_epoch}' is distinct from
            new.room_epoch::text
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material context is not an exact admission binding';
    end if;

    context_target_schema := context_document #>> '{targetAgentRun,schemaVersion}';
    context_logical_run_id := context_document #>> '{targetAgentRun,request,agent_run_id}';
    context_attempt_id := context_document #>> '{targetAgentRun,request,command,attempt_id}';
    context_attempt_no_text := context_document #>> '{targetAgentRun,request,attempt_no}';
    context_previous_attempt_id :=
        context_document #>> '{targetAgentRun,request,previous_attempt_id}';

    if context_attempt_no_text is null
       or context_attempt_no_text !~ '^[1-9][0-9]{0,15}$'
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material has an invalid attempt number';
    end if;
    context_attempt_no := context_attempt_no_text::bigint;

    if context_attempt_no = 1 then
        if context_target_schema is distinct from 'intake-target-agent-run-context.v1'
           or context_previous_attempt_id is not null
        then
            raise exception using errcode = '23514',
                message = 'initial target E2E Intake material must use context v1 without a predecessor';
        end if;
    elsif context_target_schema is distinct from 'intake-target-agent-run-context.v2'
          or context_previous_attempt_id is null
    then
        raise exception using errcode = '23514',
            message = 'retry target E2E Intake material must use context v2 with a predecessor';
    end if;

    select attempt.id into matched_attempt_id
      from agent_run run
      join agent_run_attempt attempt on attempt.agent_run_id = run.id
     where run.id = context_logical_run_id
       and run.protocol = 'agent-stream.v2'
       and run.executor_kind = 'TEMPORAL_ACTIVITY'
       and run.tenant_surrogate = new.tenant_surrogate
       and run.case_id = new.case_id
       and run.room_type = 'INTAKE'
       and run.room_epoch = new.room_epoch
       and run.fencing_token = new.room_fencing_token
       and run.logical_input_hash =
            context_document #>> '{targetAgentRun,request,logical_input_hash}'
       and run.attempt_limit::text =
            context_document #>> '{targetAgentRun,request,attempt_limit}'
       and attempt.id = context_attempt_id
       and attempt.attempt_no = context_attempt_no
       and attempt.lineage_schema_version = 'agent-run-attempt-lineage.v1'
       and attempt.command_id = new.command_id
       and attempt.command_request_hash =
            context_document #>> '{targetAgentRun,request,command,request_hash}'
       and attempt.logical_input_hash = run.logical_input_hash
       and attempt.previous_attempt_id is not distinct from context_previous_attempt_id
       and context_document #>> '{targetAgentRun,request,command,logical_run_id}' = run.id
     for key share of run, attempt;

    if not found then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material does not bind its durable AgentRun attempt';
    end if;

    if context_attempt_no > 1 then
        select predecessor.id into matched_previous_attempt_id
          from agent_run_attempt predecessor
         where predecessor.id = context_previous_attempt_id
           and predecessor.agent_run_id = context_logical_run_id
           and predecessor.attempt_no = context_attempt_no - 1
         for key share;

        if not found then
            raise exception using errcode = '23514',
                message = 'target E2E Intake retry predecessor is not the adjacent attempt';
        end if;
    end if;

    return new;
end
$$;
