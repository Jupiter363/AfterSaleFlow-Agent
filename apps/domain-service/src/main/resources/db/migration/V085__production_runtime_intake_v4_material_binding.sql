-- Bind target Intake command material to the protocol that the material itself freezes.
-- V3 remains the legacy lane; V4 is admitted only for the exact parallel Intake profile.

create or replace function enforce_production_runtime_intake_command_material()
returns trigger
language plpgsql
as $$
declare
    admission_row production_runtime_command_admission%rowtype;
    context_document jsonb;
    context_target_schema text;
    context_stream_protocol text;
    context_logical_run_id text;
    context_attempt_id text;
    context_attempt_no_text text;
    context_attempt_no bigint;
    context_previous_attempt_id text;
    matched_attempt_id text;
    matched_previous_attempt_id text;
begin
    select * into admission_row
      from production_runtime_command_admission
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
            message = 'production runtime Intake material must exactly bind its command admission';
    end if;

    context_document := new.context_canonical_json::jsonb;
    if context_document #>> '{schemaVersion}' is distinct from new.context_schema_version
       or context_document #>> '{targetAgentRun,executionLane}' is distinct from new.execution_lane
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
            message = 'production runtime Intake material context is not an exact admission binding';
    end if;

    context_target_schema := context_document #>> '{targetAgentRun,schemaVersion}';
    context_stream_protocol := context_document #>> '{targetAgentRun,request,stream_protocol}';
    context_logical_run_id := context_document #>> '{targetAgentRun,request,agent_run_id}';
    context_attempt_id := context_document #>> '{targetAgentRun,request,command,attempt_id}';
    context_attempt_no_text := context_document #>> '{targetAgentRun,request,attempt_no}';
    context_previous_attempt_id :=
        context_document #>> '{targetAgentRun,request,previous_attempt_id}';

    if context_stream_protocol not in ('agent-stream.v3', 'agent-stream.v4') then
        raise exception using errcode = '23514',
            message = 'production runtime Intake material has an unsupported stream protocol';
    end if;

    if context_attempt_no_text is null
       or context_attempt_no_text !~ '^[1-9][0-9]{0,15}$'
    then
        raise exception using errcode = '23514',
            message = 'production runtime Intake material has an invalid attempt number';
    end if;
    context_attempt_no := context_attempt_no_text::bigint;

    if context_attempt_no = 1 then
        if context_target_schema is distinct from 'intake-target-agent-run-context.v1'
           or context_previous_attempt_id is not null
        then
            raise exception using errcode = '23514',
                message = 'initial production runtime Intake material must use context v1 without a predecessor';
        end if;
    elsif context_target_schema is distinct from 'intake-target-agent-run-context.v2'
          or context_previous_attempt_id is null
    then
        raise exception using errcode = '23514',
            message = 'retry production runtime Intake material must use context v2 with a predecessor';
    end if;

    if context_stream_protocol = 'agent-stream.v4'
       and (context_attempt_no <> 1
            or context_document #>> '{targetAgentRun,request,attempt_limit}' is distinct from '1'
            or context_document #>> '{targetAgentRun,request,reset_required}' is distinct from 'false'
            or context_document #>> '{targetAgentRun,request,public_sequence_offset}' is distinct from '0'
            or context_document #>> '{targetAgentRun,request,command,invocation_context,agent_profile_id}'
                is distinct from 'dispute-intake-officer.parallel-frames.v1'
            or context_document #>> '{targetAgentRun,request,command,invocation_context,output_schema_version}'
                is distinct from 'production-runtime-room-proposal-source.v2'
            or nullif(context_document #>> '{targetAgentRun,request,command,room_id}', '') is null
            or jsonb_typeof(context_document #> '{targetAgentRun,request,command,event_ref}')
                is distinct from 'object'
            or context_document #>> '{targetAgentRun,request,command,actor_scope,actor_role}'
                not in ('USER', 'MERCHANT')
            or context_document #>> '{targetAgentRun,request,command,actor_scope,audience}'
                is distinct from
                   context_document #>> '{targetAgentRun,request,command,actor_scope,actor_role}'
            or coalesce(
                    context_document #>>
                        '{targetAgentRun,request,command,retry_budget,provider_attempts_remaining}',
                    '') !~ '^[3-6]$')
    then
        raise exception using errcode = '23514',
            message = 'agent-stream.v4 Intake material requires the exact parallel execution authority';
    end if;

    select attempt.id into matched_attempt_id
      from agent_run run
      join agent_run_attempt attempt on attempt.agent_run_id = run.id
     where run.id = context_logical_run_id
       and run.protocol = context_stream_protocol
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
            message = 'production runtime Intake material does not bind its durable AgentRun attempt';
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
                message = 'production runtime Intake retry predecessor is not the adjacent attempt';
        end if;
    end if;

    return new;
end
$$;
