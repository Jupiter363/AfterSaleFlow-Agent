-- Recover one expired parallel receipt without guessing Provider completion.
--
-- An abandonment is technical, append-only authority.  It proves that the
-- exact receipt's Provider intent advanced, its lease is no longer active, and
-- no receipt cycle completed.  It neither completes the Graph attempt nor
-- chooses Frame generations; Java applies it only to its exact STARTED slots
-- and publishes the successor admission receipt.

alter table agent_graph_parallel_receipt_execution
    add column predecessor_abandonment_id varchar(128);

create table agent_graph_parallel_receipt_abandonment (
    abandonment_id varchar(128) primary key,
    execution_id varchar(128) not null unique,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    attempt_id varchar(64) not null,
    frame_set_id varchar(128) not null,
    receipt_sha256 varchar(64) not null,
    authority_sha256 varchar(64) not null,
    admission_receipt_json jsonb not null,
    provider_call_count_before integer not null,
    provider_call_count_after integer not null,
    owner_id varchar(128) not null,
    fencing_token bigint not null,
    abandoned_at timestamptz not null,
    abandonment_sha256 varchar(64) not null unique,
    constraint fk_agent_graph_parallel_abandonment_command
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash)
        on delete restrict,
    constraint fk_agent_graph_parallel_abandonment_attempt
        foreign key (attempt_id)
        references agent_graph_command_attempt(attempt_id)
        on delete restrict,
    constraint fk_agent_graph_parallel_abandonment_execution
        foreign key (execution_id)
        references agent_graph_parallel_receipt_execution(execution_id)
        on delete restrict,
    constraint uq_agent_graph_parallel_abandonment_receipt
        unique (thread_id, command_id, attempt_id, receipt_sha256),
    constraint uq_agent_graph_parallel_abandonment_fence
        unique (attempt_id, fencing_token),
    constraint ck_agent_graph_parallel_abandonment_id
        check (
            abandonment_id = 'parallel-receipt-abandonment.'
                || left(receipt_sha256, 24) || '.' || fencing_token::text
        ),
    constraint ck_agent_graph_parallel_abandonment_hashes
        check (
            request_hash ~ '^[0-9a-f]{64}$'
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and authority_sha256 ~ '^[0-9a-f]{64}$'
            and abandonment_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_parallel_abandonment_counts
        check (
            fencing_token >= 1
            and provider_call_count_before >= 0
            and provider_call_count_after > provider_call_count_before
        ),
    constraint ck_agent_graph_parallel_abandonment_receipt
        check (
            jsonb_typeof(admission_receipt_json) = 'object'
            and admission_receipt_json ->> 'receipt_sha256' = receipt_sha256
            and admission_receipt_json ->> 'request_hash' = request_hash
            and admission_receipt_json ->> 'frame_set_id' = frame_set_id
            and admission_receipt_json ->> 'attempt_id' = attempt_id
            and octet_length(admission_receipt_json::text) <= 65536
        )
);

alter table agent_graph_parallel_receipt_execution
    add constraint fk_agent_graph_parallel_execution_predecessor_abandonment
        foreign key (predecessor_abandonment_id)
        references agent_graph_parallel_receipt_abandonment(abandonment_id)
        on delete restrict;

alter table agent_graph_parallel_receipt_execution
    drop constraint ck_agent_graph_parallel_execution_fence;

alter table agent_graph_parallel_receipt_execution
    add constraint ck_agent_graph_parallel_execution_fence
        check (
            fencing_token >= 1
            and provider_call_count_at_admission >= 0
            and num_nonnulls(
                predecessor_cycle_id,
                predecessor_execution_id,
                predecessor_abandonment_id
            ) <= 1
        );

create trigger trg_reject_agent_graph_parallel_abandonment_update
before update or delete on agent_graph_parallel_receipt_abandonment
for each row execute function reject_agent_graph_parallel_receipt_mutation();

create trigger trg_reject_agent_graph_parallel_abandonment_truncate
before truncate on agent_graph_parallel_receipt_abandonment
for each statement execute function reject_agent_graph_parallel_receipt_mutation();

create function guard_agent_graph_parallel_abandonment_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) or not exists (
        select 1
          from agent_graph_parallel_receipt_execution execution
          join agent_graph_command command
            on command.thread_id = execution.thread_id
           and command.command_id = execution.command_id
           and command.request_hash = execution.request_hash
          join agent_graph_command_attempt attempt
            on attempt.attempt_id = execution.attempt_id
           and attempt.thread_id = execution.thread_id
           and attempt.command_id = execution.command_id
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
           and command.status = 'EXECUTING'
           and command.fencing_token = new.fencing_token
           and attempt.attempt_status = 'EXECUTING'
           and attempt.owner_id = new.owner_id
           and attempt.fencing_token = new.fencing_token
           and attempt.provider_call_count = new.provider_call_count_after
           and lease.owner_id = new.owner_id
           and lease.fencing_token = new.fencing_token
           and (
               lease.lease_expires_at <= clock_timestamp()
               or lease.released_at is not null
               or lease.cancelled_at is not null
           )
           and new.abandoned_at >= execution.admitted_at
           and new.abandoned_at <= clock_timestamp()
           and not exists (
               select 1
                 from agent_graph_parallel_receipt_cycle cycle
                where cycle.execution_id = execution.execution_id
           )
           and not exists (
               select 1
                 from agent_graph_parallel_receipt_execution successor
                where successor.attempt_id = execution.attempt_id
                  and successor.fencing_token > execution.fencing_token
           )
    ) then
        raise exception using errcode = '23514',
            message = 'parallel receipt abandonment authority is invalid';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_agent_graph_parallel_abandonment_insert
before insert on agent_graph_parallel_receipt_abandonment
for each row execute function guard_agent_graph_parallel_abandonment_insert();

create or replace function guard_agent_graph_parallel_execution_insert()
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
                   num_nonnulls(
                       new.predecessor_cycle_id,
                       new.predecessor_execution_id,
                       new.predecessor_abandonment_id
                   ) = 0
                   and attempt.fencing_token = new.fencing_token
                   and new.provider_call_count_at_admission = 0
                   and attempt.provider_call_count = 0
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_execution prior
                        where prior.attempt_id = new.attempt_id
                   )
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_cycle prior
                        where prior.attempt_id = new.attempt_id
                   )
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_abandonment prior
                        where prior.attempt_id = new.attempt_id
                   )
               )
               or (
                   new.predecessor_cycle_id is not null
                   and new.predecessor_execution_id is null
                   and new.predecessor_abandonment_id is null
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
                   and new.predecessor_abandonment_id is null
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
                               where completed.execution_id = predecessor.execution_id
                          )
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_abandonment abandoned
                               where abandoned.execution_id = predecessor.execution_id
                          )
                   )
               )
               or (
                   new.predecessor_cycle_id is null
                   and new.predecessor_execution_id is null
                   and new.predecessor_abandonment_id is not null
                   and attempt.fencing_token = new.fencing_token - 1
                   and new.provider_call_count_at_admission
                       = attempt.provider_call_count
                   and exists (
                       select 1
                         from agent_graph_parallel_receipt_abandonment abandoned
                         join agent_graph_parallel_receipt_execution predecessor
                           on predecessor.execution_id = abandoned.execution_id
                        where abandoned.abandonment_id
                            = new.predecessor_abandonment_id
                          and abandoned.attempt_id = new.attempt_id
                          and abandoned.thread_id = new.thread_id
                          and abandoned.command_id = new.command_id
                          and abandoned.frame_set_id = new.frame_set_id
                          and abandoned.authority_sha256 = new.authority_sha256
                          and abandoned.receipt_sha256 <> new.receipt_sha256
                          and abandoned.fencing_token = new.fencing_token - 1
                          and abandoned.provider_call_count_after
                              = attempt.provider_call_count
                          and predecessor.owner_id = attempt.owner_id
                          and predecessor.fencing_token = abandoned.fencing_token
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_execution newer
                               where newer.attempt_id = abandoned.attempt_id
                                 and newer.fencing_token > abandoned.fencing_token
                          )
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_cycle completed
                               where completed.execution_id = abandoned.execution_id
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

create or replace function guard_agent_graph_parallel_cycle_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) or exists (
        select 1
          from agent_graph_parallel_receipt_abandonment abandonment
         where abandonment.execution_id = new.execution_id
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
              left join agent_graph_parallel_receipt_abandonment abandonment
                on abandonment.abandonment_id
                    = execution.predecessor_abandonment_id
              left join agent_graph_parallel_receipt_execution predecessor
                on predecessor.execution_id = coalesce(
                    execution.predecessor_execution_id,
                    abandonment.execution_id
                )
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
                       and execution.predecessor_abandonment_id is null
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
                       and execution.predecessor_abandonment_id is null
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
                            where completed.execution_id
                                = predecessor.execution_id
                       )
                       and not exists (
                           select 1
                             from agent_graph_parallel_receipt_abandonment abandoned
                            where abandoned.execution_id
                                = predecessor.execution_id
                       )
                   )
                   or (
                       execution.predecessor_cycle_id is null
                       and execution.predecessor_execution_id is null
                       and execution.predecessor_abandonment_id is not null
                       and predecessor.attempt_id = old.attempt_id
                       and predecessor.owner_id = old.owner_id
                       and predecessor.fencing_token = old.fencing_token
                       and predecessor.receipt_sha256
                           <> execution.receipt_sha256
                       and abandonment.execution_id = predecessor.execution_id
                       and abandonment.owner_id = old.owner_id
                       and abandonment.fencing_token = old.fencing_token
                       and abandonment.provider_call_count_before
                           = predecessor.provider_call_count_at_admission
                       and abandonment.provider_call_count_after
                           = old.provider_call_count
                       and not exists (
                           select 1
                             from agent_graph_parallel_receipt_cycle completed
                            where completed.execution_id
                                = predecessor.execution_id
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
        raise exception using errcode = '23514',
            message = 'illegal graph attempt transition';
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

-- Extend the reviewed purge boundary and replace its invalid two-pass cycle
-- deletion with one deterministic leaf-pruning loop for the alternating
-- execution/cycle lineage.
do $migration$
declare
    purge_definition text;
    count_anchor constant text :=
        '        ''parallel_receipt_cycles'', (';
    count_replacement constant text :=
        '        ''parallel_receipt_abandonments'', (' || chr(10) ||
        '            select count(*) from agent_graph_parallel_receipt_abandonment' || chr(10) ||
        '            where thread_id = target_thread_id' || chr(10) ||
        '        ),' || chr(10) ||
        '        ''parallel_receipt_cycles'', (';
    verification_anchor constant text :=
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_execution' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_cycle where thread_id = target_thread_id';
    verification_replacement constant text :=
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_abandonment' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_execution' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_cycle where thread_id = target_thread_id';
    lineage_start_anchor constant text :=
        '    while exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_cycle' || chr(10) ||
        '         where thread_id = target_thread_id';
    lineage_end_anchor constant text :=
        '    delete from agent_graph_technical_completion where thread_id = target_thread_id;';
    lineage_replacement constant text :=
        '    while exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_cycle' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_abandonment' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) or exists (' || chr(10) ||
        '        select 1 from agent_graph_parallel_receipt_execution' || chr(10) ||
        '         where thread_id = target_thread_id' || chr(10) ||
        '    ) loop' || chr(10) ||
        '        deleted_rows := 0;' || chr(10) ||
        '        with deleted as (' || chr(10) ||
        '            delete from agent_graph_parallel_receipt_cycle cycle' || chr(10) ||
        '             where cycle.thread_id = target_thread_id' || chr(10) ||
        '               and not exists (' || chr(10) ||
        '                   select 1 from agent_graph_parallel_receipt_execution successor' || chr(10) ||
        '                    where successor.predecessor_cycle_id = cycle.cycle_id' || chr(10) ||
        '               )' || chr(10) ||
        '            returning 1' || chr(10) ||
        '        ) select count(*) into deleted_rows from deleted;' || chr(10) ||
        '        with deleted as (' || chr(10) ||
        '            delete from agent_graph_parallel_receipt_abandonment abandonment' || chr(10) ||
        '             where abandonment.thread_id = target_thread_id' || chr(10) ||
        '               and not exists (' || chr(10) ||
        '                   select 1 from agent_graph_parallel_receipt_execution successor' || chr(10) ||
        '                    where successor.predecessor_abandonment_id = abandonment.abandonment_id' || chr(10) ||
        '               )' || chr(10) ||
        '            returning 1' || chr(10) ||
        '        ) select deleted_rows + count(*) into deleted_rows from deleted;' || chr(10) ||
        '        with deleted as (' || chr(10) ||
        '            delete from agent_graph_parallel_receipt_execution execution' || chr(10) ||
        '             where execution.thread_id = target_thread_id' || chr(10) ||
        '               and not exists (' || chr(10) ||
        '                   select 1 from agent_graph_parallel_receipt_cycle cycle' || chr(10) ||
        '                    where cycle.execution_id = execution.execution_id' || chr(10) ||
        '               )' || chr(10) ||
        '               and not exists (' || chr(10) ||
        '                   select 1 from agent_graph_parallel_receipt_abandonment abandonment' || chr(10) ||
        '                    where abandonment.execution_id = execution.execution_id' || chr(10) ||
        '               )' || chr(10) ||
        '               and not exists (' || chr(10) ||
        '                   select 1 from agent_graph_parallel_receipt_execution successor' || chr(10) ||
        '                    where successor.predecessor_execution_id = execution.execution_id' || chr(10) ||
        '               )' || chr(10) ||
        '            returning 1' || chr(10) ||
        '        ) select deleted_rows + count(*) into deleted_rows from deleted;' || chr(10) ||
        '        if deleted_rows = 0 then' || chr(10) ||
        '            raise exception using errcode = ''23514'',' || chr(10) ||
        '                message = ''target-E2E Graph purge receipt lineage is not acyclic'';' || chr(10) ||
        '        end if;' || chr(10) ||
        '    end loop;' || chr(10) || chr(10) ||
        lineage_end_anchor;
    start_position integer;
    end_position integer;
begin
    select pg_get_functiondef(
        'purge_target_e2e_test_graph_thread(varchar,varchar,varchar,bigint,varchar,varchar,varchar,varchar,jsonb)'::regprocedure
    ) into purge_definition;

    if position(count_anchor in purge_definition) = 0
        or position(verification_anchor in purge_definition) = 0
    then
        raise exception 'G016 could not locate Graph purge count anchors';
    end if;
    purge_definition := replace(
        purge_definition,
        count_anchor,
        count_replacement
    );
    purge_definition := replace(
        purge_definition,
        verification_anchor,
        verification_replacement
    );

    start_position := position(lineage_start_anchor in purge_definition);
    end_position := position(lineage_end_anchor in purge_definition);
    if start_position = 0 or end_position <= start_position then
        raise exception 'G016 could not locate Graph purge lineage anchors';
    end if;
    purge_definition := substring(purge_definition from 1 for start_position - 1)
        || lineage_replacement
        || substring(
            purge_definition
            from end_position + length(lineage_end_anchor)
        );
    execute purge_definition;
end;
$migration$;
