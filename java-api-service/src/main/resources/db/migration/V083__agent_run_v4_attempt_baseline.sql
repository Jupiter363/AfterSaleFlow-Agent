-- Give agent-stream.v4 an explicit empty-attempt sequence authority.
-- V3 keeps its durable attempt_started/reset prelude and therefore remains non-negative.

alter table agent_run_attempt
    drop constraint ck_agent_run_attempt_progress;

alter table agent_run_attempt
    add constraint ck_agent_run_attempt_progress
        check (last_sequence_no >= -1);

create or replace function enforce_agent_run_attempt_protocol_baseline()
returns trigger
language plpgsql
as $$
declare
    parent_protocol varchar(32);
begin
    select run.protocol
      into parent_protocol
      from agent_run run
     where run.id = new.agent_run_id;

    if parent_protocol is null then
        raise exception using errcode = '23514',
            message = 'AgentRun attempt is missing its parent protocol authority';
    end if;

    if parent_protocol = 'agent-stream.v4' then
        if new.attempt_no <> 1
            or new.previous_attempt_id is not null
            or new.reset_required
            or new.public_sequence_offset <> 0 then
            raise exception using errcode = '23514',
                message = 'agent-stream.v4 uses one outer attempt without V3 reset lineage';
        end if;
        if new.last_sequence_no < -1 then
            raise exception using errcode = '23514',
                message = 'agent-stream.v4 attempt sequence is below its empty baseline';
        end if;
        if new.last_sequence_no = -1 and (
            new.attempt_status <> 'RUNNING'
            or new.public_output_emitted
            or new.final_frame_observed
        ) then
            raise exception using errcode = '23514',
                message = 'agent-stream.v4 empty sequence baseline has progressed state';
        end if;
    elsif new.last_sequence_no < 0 then
        raise exception using errcode = '23514',
            message = 'only agent-stream.v4 may persist an empty attempt sequence baseline';
    end if;

    return new;
end;
$$;

drop trigger if exists trg_agent_run_attempt_protocol_baseline on agent_run_attempt;
create trigger trg_agent_run_attempt_protocol_baseline
before insert or update of agent_run_id, attempt_no, attempt_status, previous_attempt_id,
    last_sequence_no, reset_required, public_sequence_offset,
    public_output_emitted, final_frame_observed
on agent_run_attempt
for each row
execute function enforce_agent_run_attempt_protocol_baseline();

create or replace function reject_agent_run_protocol_drift_with_attempts()
returns trigger
language plpgsql
as $$
begin
    if new.protocol is distinct from old.protocol
       and exists (
           select 1
             from agent_run_attempt attempt
            where attempt.agent_run_id = old.id
       ) then
        raise exception using errcode = '23514',
            message = 'AgentRun protocol cannot change after attempt admission';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_agent_run_protocol_drift_with_attempts on agent_run;
create trigger trg_agent_run_protocol_drift_with_attempts
before update of protocol on agent_run
for each row
execute function reject_agent_run_protocol_drift_with_attempts();
