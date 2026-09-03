-- A resumed parallel Intake command may execute only the unsealed Frame subset.
-- Every Java admission receipt is first bound to one exact Graph attempt fence;
-- a retryable receipt cycle may then release that fence without terminating the
-- command. The next receipt advances the same attempt by exactly one fence.

alter table agent_graph_technical_completion
    drop constraint ck_agent_graph_technical_completion_schema;

alter table agent_graph_technical_completion
    add constraint ck_agent_graph_technical_completion_schema
        check (
            completion_schema_version in (
                'intake-parallel-technical-completion.v1',
                'intake-parallel-technical-completion.v2'
            )
        );

create table agent_graph_parallel_receipt_execution (
    execution_id varchar(128) primary key,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    attempt_id varchar(64) not null,
    frame_set_id varchar(128) not null,
    receipt_sha256 varchar(64) not null,
    authority_sha256 varchar(64) not null,
    predecessor_cycle_id varchar(128),
    predecessor_execution_id varchar(128),
    provider_call_count_at_admission integer not null,
    owner_id varchar(128) not null,
    fencing_token bigint not null,
    admitted_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_graph_parallel_execution_command
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash)
        on delete restrict,
    constraint fk_agent_graph_parallel_execution_attempt
        -- The attempt identity is stable while its current fencing_token advances.
        -- The insert guard below freezes the historical fence at admission time.
        foreign key (attempt_id)
        references agent_graph_command_attempt(attempt_id)
        on delete restrict,
    constraint uq_agent_graph_parallel_execution_receipt
        unique (
            thread_id, command_id, attempt_id, receipt_sha256, fencing_token
        ),
    constraint uq_agent_graph_parallel_execution_fence
        unique (attempt_id, fencing_token),
    constraint ck_agent_graph_parallel_execution_id
        check (
            execution_id = 'parallel-receipt-execution.'
                || left(receipt_sha256, 24) || '.' || fencing_token::text
        ),
    constraint ck_agent_graph_parallel_execution_hashes
        check (
            request_hash ~ '^[0-9a-f]{64}$'
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and authority_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_parallel_execution_fence
        check (
            fencing_token >= 1
            and provider_call_count_at_admission >= 0
            and (
                (fencing_token = 1 and predecessor_cycle_id is null
                    and predecessor_execution_id is null)
                or
                (fencing_token > 1 and (
                    (predecessor_cycle_id is null)
                    <> (predecessor_execution_id is null)
                ))
            )
        ),
    constraint fk_agent_graph_parallel_execution_predecessor_execution
        foreign key (predecessor_execution_id)
        references agent_graph_parallel_receipt_execution(execution_id)
        on delete restrict
);

create table agent_graph_parallel_receipt_cycle (
    cycle_id varchar(128) primary key,
    execution_id varchar(128) not null,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    attempt_id varchar(64) not null,
    frame_set_id varchar(128) not null,
    receipt_sha256 varchar(64) not null,
    authority_sha256 varchar(64) not null,
    admission_receipt_json jsonb not null,
    canonical_events_json jsonb not null,
    terminal_error_code varchar(128) not null,
    terminal_retryable boolean not null,
    completion_sha256 varchar(64) not null,
    provider_call_count_before integer not null,
    provider_call_count_after integer not null,
    owner_id varchar(128) not null,
    fencing_token bigint not null,
    completed_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_graph_parallel_cycle_command
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash)
        on delete restrict,
    constraint fk_agent_graph_parallel_cycle_attempt
        -- Keep completed fence-N history valid after the attempt moves to N+1.
        foreign key (attempt_id)
        references agent_graph_command_attempt(attempt_id)
        on delete restrict,
    constraint fk_agent_graph_parallel_cycle_execution
        foreign key (execution_id)
        references agent_graph_parallel_receipt_execution(execution_id)
        on delete restrict,
    constraint uq_agent_graph_parallel_cycle_receipt
        unique (thread_id, command_id, attempt_id, receipt_sha256),
    constraint uq_agent_graph_parallel_cycle_fence
        unique (attempt_id, fencing_token),
    constraint ck_agent_graph_parallel_cycle_id
        check (
            cycle_id = 'parallel-receipt-cycle.' || left(receipt_sha256, 32)
            and execution_id = 'parallel-receipt-execution.'
                || left(receipt_sha256, 24) || '.' || fencing_token::text
        ),
    constraint ck_agent_graph_parallel_cycle_hashes
        check (
            request_hash ~ '^[0-9a-f]{64}$'
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and authority_sha256 ~ '^[0-9a-f]{64}$'
            and completion_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_parallel_cycle_counts
        check (
            provider_call_count_before >= 0
            and provider_call_count_after > provider_call_count_before
            and fencing_token >= 1
        ),
    constraint ck_agent_graph_parallel_cycle_receipt
        check (
            jsonb_typeof(admission_receipt_json) = 'object'
            and admission_receipt_json ->> 'receipt_sha256' = receipt_sha256
            and admission_receipt_json ->> 'request_hash' = request_hash
            and admission_receipt_json ->> 'frame_set_id' = frame_set_id
            and admission_receipt_json ->> 'attempt_id' = attempt_id
            and octet_length(admission_receipt_json::text) <= 65536
        ),
    constraint ck_agent_graph_parallel_cycle_events
        check (
            jsonb_typeof(canonical_events_json) = 'array'
            and jsonb_array_length(canonical_events_json) > 0
            and octet_length(canonical_events_json::text) <= 1048576
            and terminal_error_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
            and terminal_retryable
        )
);

alter table agent_graph_parallel_receipt_execution
    add constraint fk_agent_graph_parallel_execution_predecessor
        foreign key (predecessor_cycle_id)
        references agent_graph_parallel_receipt_cycle(cycle_id)
        on delete restrict;

create index idx_agent_graph_parallel_cycle_latest
    on agent_graph_parallel_receipt_cycle(
        thread_id, command_id, attempt_id, fencing_token desc
    );

create function reject_agent_graph_parallel_receipt_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'parallel receipt authority rows are immutable';
end;
$function$;

create trigger trg_reject_agent_graph_parallel_execution_update
before update or delete on agent_graph_parallel_receipt_execution
for each row execute function reject_agent_graph_parallel_receipt_mutation();

create trigger trg_reject_agent_graph_parallel_execution_truncate
before truncate on agent_graph_parallel_receipt_execution
for each statement execute function reject_agent_graph_parallel_receipt_mutation();

create trigger trg_reject_agent_graph_parallel_cycle_update
before update or delete on agent_graph_parallel_receipt_cycle
for each row execute function reject_agent_graph_parallel_receipt_mutation();

create trigger trg_reject_agent_graph_parallel_cycle_truncate
before truncate on agent_graph_parallel_receipt_cycle
for each statement execute function reject_agent_graph_parallel_receipt_mutation();

create function require_parallel_intake_graph_command(
    target_thread_id varchar,
    target_command_id varchar,
    target_request_hash varchar
)
returns boolean
language sql
stable
as $function$
    select exists (
        select 1
          from agent_graph_command command
         where command.thread_id = target_thread_id
           and command.command_id = target_command_id
           and command.request_hash = target_request_hash
           and command.output_schema_version = 'target-e2e-room-proposal-source.v2'
           and command.request_json ->> 'room_type' = 'INTAKE'
           and command.request_json ->> 'room_id' is not null
           and jsonb_typeof(command.request_json -> 'event_ref') = 'object'
           and command.request_json #>> '{invocation_context,agent_profile_id}'
               = 'dispute-intake-officer.parallel-frames.v1'
           and command.request_json #>> '{invocation_context,output_schema_version}'
               = 'target-e2e-room-proposal-source.v2'
           and command.request_json #>> '{actor_scope,actor_role}' in ('USER', 'MERCHANT')
           and command.request_json #>> '{actor_scope,audience}'
               = command.request_json #>> '{actor_scope,actor_role}'
    )
$function$;

create function guard_agent_graph_parallel_execution_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) or not exists (
        select 1
          from agent_graph_command command
          join agent_graph_command_attempt attempt
            on attempt.thread_id = command.thread_id
           and attempt.command_id = command.command_id
          join agent_graph_lease lease
            on lease.thread_id = command.thread_id
           and lease.command_id = command.command_id
         where command.thread_id = new.thread_id
           and command.command_id = new.command_id
           and command.status = 'EXECUTING'
           and command.fencing_token = new.fencing_token
           and attempt.attempt_id = new.attempt_id
           and attempt.attempt_status = 'EXECUTING'
           and lease.owner_id = new.owner_id
           and lease.fencing_token = new.fencing_token
           and lease.released_at is null
           and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
           and (
               (
                   new.predecessor_cycle_id is null
                   and new.predecessor_execution_id is null
                   and new.fencing_token = 1
                   and attempt.fencing_token = new.fencing_token
                   and new.provider_call_count_at_admission = 0
                   and attempt.provider_call_count = 0
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_execution prior
                        where prior.attempt_id = new.attempt_id
                   )
                   and not exists (
                       select 1 from agent_graph_parallel_receipt_cycle prior
                        where prior.attempt_id = new.attempt_id
                   )
               )
               or (
                   new.predecessor_cycle_id is not null
                   and new.predecessor_execution_id is null
                   and attempt.fencing_token = new.fencing_token - 1
                   and new.provider_call_count_at_admission
                       = attempt.provider_call_count
                   and exists (
                       select 1
                         from agent_graph_parallel_receipt_cycle prior
                        where prior.cycle_id = new.predecessor_cycle_id
                          and prior.attempt_id = new.attempt_id
                          and prior.thread_id = new.thread_id
                          and prior.command_id = new.command_id
                          and prior.frame_set_id = new.frame_set_id
                          and prior.authority_sha256 = new.authority_sha256
                          and prior.receipt_sha256 <> new.receipt_sha256
                          and prior.fencing_token = new.fencing_token - 1
                          and prior.provider_call_count_after
                              = attempt.provider_call_count
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_cycle newer
                               where newer.attempt_id = prior.attempt_id
                                 and newer.fencing_token > prior.fencing_token
                          )
                   )
               )
               or (
                   new.predecessor_cycle_id is null
                   and new.predecessor_execution_id is not null
                   and attempt.fencing_token = new.fencing_token - 1
                   and new.provider_call_count_at_admission
                       = attempt.provider_call_count
                   and exists (
                       select 1
                         from agent_graph_parallel_receipt_execution predecessor
                        where predecessor.execution_id
                            = new.predecessor_execution_id
                          and predecessor.attempt_id = new.attempt_id
                          and predecessor.thread_id = new.thread_id
                          and predecessor.command_id = new.command_id
                          and predecessor.frame_set_id = new.frame_set_id
                          and predecessor.receipt_sha256 = new.receipt_sha256
                          and predecessor.authority_sha256 = new.authority_sha256
                          and predecessor.owner_id = attempt.owner_id
                          and predecessor.fencing_token = new.fencing_token - 1
                          and predecessor.provider_call_count_at_admission
                              = attempt.provider_call_count
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_execution newer
                               where newer.attempt_id = predecessor.attempt_id
                                 and newer.receipt_sha256
                                     = predecessor.receipt_sha256
                                 and newer.fencing_token
                                     > predecessor.fencing_token
                          )
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_cycle completed
                               where completed.attempt_id = predecessor.attempt_id
                                 and completed.receipt_sha256
                                     = predecessor.receipt_sha256
                          )
                   )
               )
           )
    ) then
        raise exception using errcode = '23514',
            message = 'parallel receipt execution authority is invalid';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_agent_graph_parallel_execution_insert
before insert on agent_graph_parallel_receipt_execution
for each row execute function guard_agent_graph_parallel_execution_insert();

create function guard_agent_graph_parallel_cycle_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) or not exists (
        select 1
          from agent_graph_parallel_receipt_execution execution
          join agent_graph_command_attempt attempt
            on attempt.attempt_id = execution.attempt_id
           and attempt.thread_id = execution.thread_id
           and attempt.command_id = execution.command_id
           and attempt.fencing_token = execution.fencing_token
          join agent_graph_lease lease
            on lease.thread_id = execution.thread_id
           and lease.command_id = execution.command_id
         where execution.execution_id = new.execution_id
           and execution.thread_id = new.thread_id
           and execution.command_id = new.command_id
           and execution.request_hash = new.request_hash
           and execution.attempt_id = new.attempt_id
           and execution.frame_set_id = new.frame_set_id
           and execution.receipt_sha256 = new.receipt_sha256
           and execution.authority_sha256 = new.authority_sha256
           and execution.owner_id = new.owner_id
           and execution.fencing_token = new.fencing_token
           and execution.provider_call_count_at_admission
               = new.provider_call_count_before
           and attempt.attempt_status = 'EXECUTING'
           and attempt.owner_id = new.owner_id
           and attempt.provider_call_count = new.provider_call_count_after
           and lease.owner_id = new.owner_id
           and lease.fencing_token = new.fencing_token
           and lease.released_at is null
           and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
    ) then
        raise exception using errcode = '23514',
            message = 'parallel receipt cycle authority is invalid';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_agent_graph_parallel_cycle_insert
before insert on agent_graph_parallel_receipt_cycle
for each row execute function guard_agent_graph_parallel_cycle_insert();

create function guard_agent_graph_parallel_completion_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) then
        raise exception using errcode = '23514',
            message = 'parallel technical completion authority is invalid';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_agent_graph_parallel_completion_insert
before insert on agent_graph_technical_completion
for each row execute function guard_agent_graph_parallel_completion_insert();

create or replace function guard_agent_graph_attempt_update()
returns trigger
language plpgsql
as $function$
declare
    transition_allowed boolean;
    execution_rebound boolean;
begin
    if row(
        new.attempt_id, new.thread_id, new.command_id, new.attempt_no,
        new.started_at, new.created_at
    ) is distinct from row(
        old.attempt_id, old.thread_id, old.command_id, old.attempt_no,
        old.started_at, old.created_at
    ) then
        raise exception using errcode = '23514',
            message = 'graph command attempt identity is immutable';
    end if;
    execution_rebound := row(new.owner_id, new.fencing_token)
        is distinct from row(old.owner_id, old.fencing_token);
    if execution_rebound and (
        old.attempt_status <> 'EXECUTING'
        or new.attempt_status <> 'EXECUTING'
        or new.fencing_token <> old.fencing_token + 1
        or row(
            new.provider_call_count, new.error_code,
            new.error_classification, new.completed_at
        ) is distinct from row(
            old.provider_call_count, old.error_code,
            old.error_classification, old.completed_at
        )
        or not exists (
            select 1
              from agent_graph_parallel_receipt_execution execution
              left join agent_graph_parallel_receipt_cycle cycle
                on cycle.cycle_id = execution.predecessor_cycle_id
              left join agent_graph_parallel_receipt_execution predecessor
                on predecessor.execution_id = execution.predecessor_execution_id
              join agent_graph_command command
                on command.thread_id = execution.thread_id
               and command.command_id = execution.command_id
              join agent_graph_lease lease
                on lease.thread_id = command.thread_id
               and lease.command_id = command.command_id
             where execution.attempt_id = old.attempt_id
               and execution.thread_id = old.thread_id
               and execution.command_id = old.command_id
               and execution.owner_id = new.owner_id
               and execution.fencing_token = new.fencing_token
               and execution.provider_call_count_at_admission
                   = old.provider_call_count
               and require_parallel_intake_graph_command(
                   command.thread_id, command.command_id, command.request_hash
               )
               and command.status = 'EXECUTING'
               and command.fencing_token = new.fencing_token
               and lease.owner_id = new.owner_id
               and lease.fencing_token = new.fencing_token
               and lease.released_at is null
               and lease.cancelled_at is null
               and lease.lease_expires_at > clock_timestamp()
               and (
                   (
                       execution.predecessor_cycle_id is not null
                       and execution.predecessor_execution_id is null
                       and cycle.attempt_id = old.attempt_id
                       and cycle.owner_id = old.owner_id
                       and cycle.fencing_token = old.fencing_token
                       and cycle.provider_call_count_after
                           = old.provider_call_count
                       and cycle.receipt_sha256 <> execution.receipt_sha256
                       and not exists (
                           select 1
                             from agent_graph_parallel_receipt_cycle newer
                            where newer.attempt_id = cycle.attempt_id
                              and newer.fencing_token > cycle.fencing_token
                       )
                   )
                   or (
                       execution.predecessor_cycle_id is null
                       and execution.predecessor_execution_id is not null
                       and predecessor.attempt_id = old.attempt_id
                       and predecessor.owner_id = old.owner_id
                       and predecessor.fencing_token = old.fencing_token
                       and predecessor.receipt_sha256
                           = execution.receipt_sha256
                       and predecessor.provider_call_count_at_admission
                           = old.provider_call_count
                       and not exists (
                           select 1
                             from agent_graph_parallel_receipt_cycle completed
                            where completed.attempt_id = old.attempt_id
                              and completed.receipt_sha256
                                  = execution.receipt_sha256
                       )
                   )
               )
        )
    ) then
        raise exception using errcode = '23514',
            message = 'graph command attempt fence handoff is unauthorized';
    end if;
    transition_allowed := new.attempt_status = old.attempt_status or (
        old.attempt_status = 'EXECUTING'
        and new.attempt_status in (
            'CHECKPOINTED', 'COMPLETED', 'FAILED', 'LEASE_LOST', 'CANCELLED'
        )
    ) or (
        old.attempt_status = 'CHECKPOINTED'
        and new.attempt_status in ('COMPLETED', 'FAILED', 'LEASE_LOST', 'CANCELLED')
    );
    if not transition_allowed then
        raise exception using errcode = '23514', message = 'illegal graph attempt transition';
    end if;
    if new.provider_call_count < old.provider_call_count then
        raise exception using errcode = '23514',
            message = 'provider call count cannot decrease';
    end if;
    if new.last_heartbeat_at < old.last_heartbeat_at then
        raise exception using errcode = '23514',
            message = 'attempt heartbeat cannot move backwards';
    end if;
    return new;
end;
$function$;
