-- The parallel Intake lane terminates only its Python technical execution.
-- Java remains the sole owner of RoomGraphResult, AgentRun FINAL and business commit.

alter table agent_graph_command
    add column technical_completed_at timestamptz;

alter table agent_graph_command
    drop constraint ck_agent_graph_command_status,
    drop constraint ck_agent_graph_command_terminal_time,
    drop constraint ck_agent_graph_command_checkpoint_binding;

alter table agent_graph_command
    add constraint ck_agent_graph_command_status
        check (
            status in (
                'REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED',
                'COMPLETED', 'TECHNICAL_COMPLETED', 'CANCELLED', 'ABORTED'
            )
        ),
    add constraint ck_agent_graph_command_terminal_time
        check (
            (status = 'COMPLETED'
                and completed_at is not null
                and technical_completed_at is null
                and result_hash is not null)
            or (status = 'TECHNICAL_COMPLETED'
                and completed_at is null
                and technical_completed_at is not null
                and committed_checkpoint_ns is null
                and committed_checkpoint_id is null
                and result_ref is null
                and result_hash is null)
            or (status = 'CANCELLED'
                and cancelled_at is not null
                and technical_completed_at is null)
            or (status = 'ABORTED'
                and aborted_at is not null
                and technical_completed_at is null)
            or (status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
                and technical_completed_at is null)
        ),
    add constraint ck_agent_graph_command_checkpoint_binding
        check (
            status not in ('RESULT_CHECKPOINTED', 'COMPLETED')
            or (
                fencing_token is not null
                and committed_checkpoint_ns is not null
                and committed_checkpoint_id is not null
                and result_ref is not null
                and result_hash is not null
                and result_checkpointed_at is not null
            )
        );

alter table agent_graph_command_attempt
    add constraint uq_agent_graph_attempt_technical_authority
        unique (attempt_id, thread_id, command_id, fencing_token);

create table agent_graph_technical_completion (
    completion_id varchar(128) primary key,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    attempt_id varchar(64) not null,
    fencing_token bigint not null,
    completion_schema_version varchar(128) not null,
    completion_json jsonb not null,
    completion_hash varchar(64) not null,
    completed_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_graph_technical_completion_command
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash)
        on delete restrict,
    constraint fk_agent_graph_technical_completion_attempt
        foreign key (attempt_id, thread_id, command_id, fencing_token)
        references agent_graph_command_attempt(
            attempt_id, thread_id, command_id, fencing_token
        )
        on delete restrict,
    constraint uq_agent_graph_technical_completion_command
        unique (thread_id, command_id),
    constraint ck_agent_graph_technical_completion_schema
        check (completion_schema_version = 'intake-parallel-technical-completion.v1'),
    constraint ck_agent_graph_technical_completion_payload
        check (
            jsonb_typeof(completion_json) = 'object'
            and octet_length(completion_json::text) between 2 and 1048576
            and completion_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and fencing_token >= 1
        )
);

create function reject_agent_graph_technical_completion_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'graph technical completion rows are immutable';
end;
$function$;

create trigger trg_reject_agent_graph_technical_completion_update
before update on agent_graph_technical_completion
for each row execute function reject_agent_graph_technical_completion_mutation();

create trigger trg_reject_agent_graph_technical_completion_delete
before delete on agent_graph_technical_completion
for each row execute function reject_agent_graph_technical_completion_mutation();

create trigger trg_reject_agent_graph_technical_completion_truncate
before truncate on agent_graph_technical_completion
for each statement execute function reject_agent_graph_technical_completion_mutation();

create or replace function guard_agent_graph_command_update()
returns trigger
language plpgsql
as $function$
declare
    transition_allowed boolean;
begin
    if row(
        new.thread_id, new.command_id, new.request_schema_version,
        new.request_json, new.request_hash, new.execution_mode, new.room_epoch,
        new.graph_key, new.graph_version, new.checkpoint_schema_version,
        new.prompt_version, new.model_profile_id, new.output_schema_version,
        new.policy_version, new.guardrail_version, new.tool_policy_version,
        new.deadline_at
    ) is distinct from row(
        old.thread_id, old.command_id, old.request_schema_version,
        old.request_json, old.request_hash, old.execution_mode, old.room_epoch,
        old.graph_key, old.graph_version, old.checkpoint_schema_version,
        old.prompt_version, old.model_profile_id, old.output_schema_version,
        old.policy_version, old.guardrail_version, old.tool_policy_version,
        old.deadline_at
    ) then
        raise exception using errcode = '23514',
            message = 'graph command identity, hash, and version bindings are immutable';
    end if;

    transition_allowed := new.status = old.status or (
        (old.status = 'REGISTERED' and new.status in ('EXECUTING', 'CANCELLED', 'ABORTED'))
        or (old.status = 'EXECUTING' and new.status in (
            'RESULT_CHECKPOINTED', 'TECHNICAL_COMPLETED', 'CANCELLED', 'ABORTED'
        ))
        or (old.status = 'RESULT_CHECKPOINTED' and new.status = 'COMPLETED')
    );
    if not transition_allowed then
        raise exception using errcode = '23514', message = 'illegal graph command transition';
    end if;
    if old.result_hash is not null and row(new.result_hash, new.result_ref)
        is distinct from row(old.result_hash, old.result_ref) then
        raise exception using errcode = '23514', message = 'graph result binding is immutable';
    end if;
    if old.technical_completed_at is not null
        and new.technical_completed_at is distinct from old.technical_completed_at then
        raise exception using errcode = '23514',
            message = 'graph technical completion time is immutable';
    end if;
    if new.command_revision < old.command_revision then
        raise exception using errcode = '23514', message = 'command revision cannot decrease';
    end if;
    if new.attempt_count < old.attempt_count then
        raise exception using errcode = '23514', message = 'attempt count cannot decrease';
    end if;
    if old.fencing_token is not null and (
        new.fencing_token is null or new.fencing_token < old.fencing_token
    ) then
        raise exception using errcode = '23514', message = 'command fence cannot decrease';
    end if;
    return new;
end;
$function$;
