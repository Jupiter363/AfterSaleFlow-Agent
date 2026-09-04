-- Reviewer-authorized, exact-thread cleanup for Production-Runtime test Graph data.
--
-- This maintenance boundary is intentionally separate from the runtime role. It preserves
-- activation, room-authority, synthetic-case reservation and audit authorities while removing
-- one already-terminal Graph thread and its command/checkpoint closure in a single transaction.

create table agent_graph_production_runtime_purge_receipt (
    purge_receipt_id varchar(128) primary key,
    java_purge_audit_id varchar(128) not null unique,
    purge_request_id varchar(128) not null unique,
    case_id varchar(64) not null,
    thread_id varchar(39) not null unique,
    tenant_surrogate varchar(128) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    source_thread_lifecycle varchar(32) not null,
    activation_id varchar(64) not null,
    environment_generation bigint not null,
    reviewer_id varchar(128) not null,
    reviewer_role varchar(32) not null,
    purged_row_counts jsonb not null,
    purged_at timestamptz not null default clock_timestamp(),
    foreign key (activation_id)
        references agent_graph_production_runtime_activation(activation_id) on delete restrict,
    constraint ck_graph_production_runtime_purge_receipt_id
        check (purge_receipt_id ~ '^GRAPH_PURGE_[A-Z0-9]{16,64}$'),
    constraint ck_graph_production_runtime_purge_java_audit
        check (java_purge_audit_id ~ '^PURGE_[A-Z0-9]{16,64}$'),
    constraint ck_graph_production_runtime_purge_request
        check (purge_request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    constraint ck_graph_production_runtime_purge_case
        check (case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    constraint ck_graph_production_runtime_purge_thread
        check (thread_id ~ '^grt\.v1\.[0-9a-f]{32}$'),
    constraint ck_graph_production_runtime_purge_room
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW') and room_epoch >= 0),
    constraint ck_graph_production_runtime_purge_lifecycle
        check (source_thread_lifecycle in ('ACTIVE', 'RETIRED', 'CANCELLED')),
    constraint ck_graph_production_runtime_purge_activation
        check (
            activation_id ~ '^p9act\.v1\.[0-9a-f]{32}$'
            and environment_generation >= 1
        ),
    constraint ck_graph_production_runtime_purge_reviewer
        check (
            reviewer_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and reviewer_role = 'PLATFORM_REVIEWER'
        ),
    constraint ck_graph_production_runtime_purge_counts
        check (
            jsonb_typeof(purged_row_counts) = 'object'
            and octet_length(purged_row_counts::text) <= 4096
        )
);

create function reject_agent_graph_production_runtime_purge_receipt_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'production-runtime Graph purge receipts are append-only';
end;
$function$;

create trigger trg_reject_agent_graph_production_runtime_purge_receipt_update
before update or delete on agent_graph_production_runtime_purge_receipt
for each row execute function reject_agent_graph_production_runtime_purge_receipt_mutation();

create trigger trg_reject_agent_graph_production_runtime_purge_receipt_truncate
before truncate on agent_graph_production_runtime_purge_receipt
for each statement execute function reject_agent_graph_production_runtime_purge_receipt_mutation();

create function graph_production_runtime_purge_context_allows(target_thread_id varchar)
returns boolean
language sql
stable
security definer
set search_path from current
as $function$
    select exists (
        select 1
          from agent_graph_production_runtime_purge_receipt receipt
         where receipt.purge_receipt_id =
                   current_setting('after_sale_flow.graph_target_purge_receipt_id', true)
           and receipt.thread_id = target_thread_id
    )
$function$;

create or replace function guard_agent_graph_nonce_delete()
returns trigger
language plpgsql
as $function$
begin
    if graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    if old.retained_until > statement_timestamp() then
        raise exception using errcode = '23514', message = 'invocation nonce is retained';
    end if;
    return old;
end;
$function$;

create or replace function reject_agent_graph_result_mutation()
returns trigger
language plpgsql
as $function$
begin
    if tg_op = 'DELETE' and graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    raise exception using errcode = '23514', message = 'graph results are immutable';
end;
$function$;

create or replace function guard_agent_graph_shadow_delete()
returns trigger
language plpgsql
security definer
set search_path from current
as $function$
begin
    if graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    if old.expires_at > statement_timestamp() or old.evidence_manifest_ref is not null then
        raise exception using errcode = '23514',
            message = 'shadow comparison is retained';
    end if;
    insert into agent_graph_shadow_cleanup_receipt (
        cleanup_receipt_id, comparison_id, comparison_hash,
        expired_at, deleted_by
    ) values (
        old.comparison_id, old.comparison_id, old.comparison_hash,
        old.expires_at, session_user
    );
    return old;
end;
$function$;

create or replace function reject_agent_graph_fanout_delete()
returns trigger
language plpgsql
as $function$
begin
    if graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    raise exception using errcode = '23514', message = 'Graph fanout permit rows are durable';
end;
$function$;

create or replace function reject_agent_graph_fanout_owner_generation_mutation()
returns trigger
language plpgsql
as $function$
begin
    if tg_op = 'DELETE' and exists (
        select 1
          from agent_graph_fanout_permit permit
         where permit.request_id = old.request_id
           and graph_production_runtime_purge_context_allows(permit.thread_id)
    ) then
        return old;
    end if;
    raise exception using
        errcode = '23514',
        message = 'Graph fanout permit owner generations are append-only';
end;
$function$;

create or replace function reject_agent_graph_technical_completion_mutation()
returns trigger
language plpgsql
as $function$
begin
    if tg_op = 'DELETE' and graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    raise exception using errcode = '23514',
        message = 'graph technical completion rows are immutable';
end;
$function$;

create or replace function reject_agent_graph_parallel_receipt_mutation()
returns trigger
language plpgsql
as $function$
begin
    if tg_op = 'DELETE' and graph_production_runtime_purge_context_allows(old.thread_id) then
        return old;
    end if;
    raise exception using errcode = '23514',
        message = 'parallel receipt authority rows are immutable';
end;
$function$;

create function purge_production_runtime_test_graph_thread(
    target_case_id varchar,
    target_thread_id varchar,
    target_activation_id varchar,
    target_environment_generation bigint,
    target_java_purge_audit_id varchar,
    target_reviewer_id varchar,
    target_reviewer_role varchar,
    target_purge_request_id varchar,
    target_expected_row_counts jsonb
)
returns varchar
language plpgsql
security definer
set search_path from current
as $function$
declare
    selected_thread graph_thread_registry%rowtype;
    selected_receipt agent_graph_production_runtime_purge_receipt%rowtype;
    selected_counts jsonb;
    selected_receipt_id varchar(128);
    deleted_rows integer;
begin
    if target_case_id is null
        or target_case_id !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        or target_thread_id is null
        or target_thread_id !~ '^grt\.v1\.[0-9a-f]{32}$'
        or target_activation_id is null
        or target_activation_id !~ '^p9act\.v1\.[0-9a-f]{32}$'
        or target_environment_generation is null
        or target_environment_generation < 1
        or target_java_purge_audit_id is null
        or target_java_purge_audit_id !~ '^PURGE_[A-Z0-9]{16,64}$'
        or target_reviewer_id is null
        or target_reviewer_id !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        or target_reviewer_role is distinct from 'PLATFORM_REVIEWER'
        or target_purge_request_id is null
        or target_purge_request_id !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        or jsonb_typeof(target_expected_row_counts) is distinct from 'object'
    then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge authority is invalid';
    end if;

    selected_receipt_id := 'GRAPH_' || target_java_purge_audit_id;
    select *
      into selected_receipt
      from agent_graph_production_runtime_purge_receipt receipt
     where receipt.purge_receipt_id = selected_receipt_id
        or receipt.java_purge_audit_id = target_java_purge_audit_id
        or receipt.purge_request_id = target_purge_request_id
        or receipt.thread_id = target_thread_id
     order by receipt.purged_at
     limit 1;

    if found then
        if row(
            selected_receipt.purge_receipt_id,
            selected_receipt.java_purge_audit_id,
            selected_receipt.purge_request_id,
            selected_receipt.case_id,
            selected_receipt.thread_id,
            selected_receipt.activation_id,
            selected_receipt.environment_generation,
            selected_receipt.reviewer_id,
            selected_receipt.reviewer_role,
            selected_receipt.purged_row_counts
        ) is distinct from row(
            selected_receipt_id,
            target_java_purge_audit_id,
            target_purge_request_id,
            target_case_id,
            target_thread_id,
            target_activation_id,
            target_environment_generation,
            target_reviewer_id,
            target_reviewer_role,
            target_expected_row_counts
        ) then
            raise exception using errcode = '23514',
                message = 'production-runtime Graph purge replay conflicts with the retained receipt';
        end if;
        return selected_receipt.purge_receipt_id;
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended('after-sale-flow:graph-target-purge:' || target_thread_id, 0)
    );

    select *
      into selected_thread
      from graph_thread_registry thread
     where thread.thread_id = target_thread_id
     for update;
    if not found or selected_thread.case_id <> target_case_id then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge thread binding is missing or foreign';
    end if;

    if not exists (
        select 1
          from agent_graph_production_runtime_activation activation
         where activation.activation_id = target_activation_id
           and activation.environment_generation = target_environment_generation
    ) or not exists (
        select 1
          from agent_graph_production_runtime_room_authority authority
         where authority.tenant_surrogate = selected_thread.tenant_surrogate
           and authority.case_id = target_case_id
           and authority.room_type = selected_thread.room_type
           and authority.room_epoch = selected_thread.room_epoch
           and authority.activation_id = target_activation_id
    ) then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge activation authority is missing or foreign';
    end if;

    perform 1
      from agent_graph_command command
     where command.thread_id = target_thread_id
     order by command.command_id
     for update;
    if not found or exists (
        select 1
          from agent_graph_command command
         where command.thread_id = target_thread_id
           and (
               command.execution_mode <> 'PRODUCTION'
               or command.activation_id is distinct from target_activation_id
               or command.status not in ('COMPLETED', 'CANCELLED', 'ABORTED')
           )
    ) then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge requires only terminal candidate commands';
    end if;

    perform 1
      from agent_graph_command_attempt attempt
     where attempt.thread_id = target_thread_id
     order by attempt.attempt_id
     for update;
    if exists (
        select 1
          from agent_graph_command_attempt attempt
         where attempt.thread_id = target_thread_id
           and attempt.attempt_status not in ('COMPLETED', 'FAILED', 'LEASE_LOST', 'CANCELLED')
    ) then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge requires only terminal attempts';
    end if;

    if exists (
        select 1
          from agent_graph_lease lease
         where lease.thread_id = target_thread_id
           and lease.released_at is null
           and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
    ) or exists (
        select 1
          from agent_graph_fanout_permit permit
         where permit.thread_id = target_thread_id
           and permit.status in ('QUEUED', 'GRANTED')
    ) then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge cannot remove active execution authority';
    end if;

    selected_counts := jsonb_build_object(
        'fanout_owner_generations', (
            select count(*) from agent_graph_fanout_permit_owner_generation generation
            join agent_graph_fanout_permit permit using (request_id)
            where permit.thread_id = target_thread_id
        ),
        'fanout_permits', (
            select count(*) from agent_graph_fanout_permit
            where thread_id = target_thread_id
        ),
        'parallel_receipt_cycles', (
            select count(*) from agent_graph_parallel_receipt_cycle
            where thread_id = target_thread_id
        ),
        'parallel_receipt_executions', (
            select count(*) from agent_graph_parallel_receipt_execution
            where thread_id = target_thread_id
        ),
        'technical_completions', (
            select count(*) from agent_graph_technical_completion
            where thread_id = target_thread_id
        ),
        'results', (
            select count(*) from agent_graph_result where thread_id = target_thread_id
        ),
        'shadow_comparisons', (
            select count(*) from agent_graph_shadow_comparison where thread_id = target_thread_id
        ),
        'invocation_nonces', (
            select count(*) from agent_graph_invocation_nonce where thread_id = target_thread_id
        ),
        'leases', (
            select count(*) from agent_graph_lease where thread_id = target_thread_id
        ),
        'attempts', (
            select count(*) from agent_graph_command_attempt where thread_id = target_thread_id
        ),
        'commands', (
            select count(*) from agent_graph_command where thread_id = target_thread_id
        ),
        'checkpoint_writes', (
            select count(*) from checkpoint_writes where thread_id = target_thread_id
        ),
        'checkpoint_blobs', (
            select count(*) from checkpoint_blobs where thread_id = target_thread_id
        ),
        'checkpoints', (
            select count(*) from checkpoints where thread_id = target_thread_id
        )
    );
    if selected_counts is distinct from target_expected_row_counts then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge row-count authority drifted';
    end if;

    insert into agent_graph_production_runtime_purge_receipt (
        purge_receipt_id, java_purge_audit_id, purge_request_id,
        case_id, thread_id, tenant_surrogate, room_type, room_epoch,
        source_thread_lifecycle, activation_id, environment_generation,
        reviewer_id, reviewer_role, purged_row_counts
    ) values (
        selected_receipt_id, target_java_purge_audit_id, target_purge_request_id,
        target_case_id, target_thread_id, selected_thread.tenant_surrogate,
        selected_thread.room_type, selected_thread.room_epoch,
        selected_thread.lifecycle_status, target_activation_id, target_environment_generation,
        target_reviewer_id, target_reviewer_role, selected_counts
    );
    perform set_config(
        'after_sale_flow.graph_target_purge_receipt_id', selected_receipt_id, true
    );

    delete from agent_graph_fanout_permit_owner_generation generation
     where exists (
         select 1 from agent_graph_fanout_permit permit
          where permit.request_id = generation.request_id
            and permit.thread_id = target_thread_id
     );
    delete from agent_graph_fanout_permit where thread_id = target_thread_id;

    while exists (
        select 1 from agent_graph_parallel_receipt_cycle
         where thread_id = target_thread_id
    ) loop
        delete from agent_graph_parallel_receipt_cycle cycle
         where cycle.thread_id = target_thread_id
           and not exists (
               select 1 from agent_graph_parallel_receipt_cycle successor
                where successor.predecessor_cycle_id = cycle.cycle_id
           );
        get diagnostics deleted_rows = row_count;
        if deleted_rows = 0 then
            raise exception using errcode = '23514',
                message = 'production-runtime Graph purge cycle lineage is not acyclic';
        end if;
    end loop;

    while exists (
        select 1 from agent_graph_parallel_receipt_execution
         where thread_id = target_thread_id
    ) loop
        delete from agent_graph_parallel_receipt_execution execution
         where execution.thread_id = target_thread_id
           and not exists (
               select 1 from agent_graph_parallel_receipt_execution successor
                where successor.predecessor_execution_id = execution.execution_id
           );
        get diagnostics deleted_rows = row_count;
        if deleted_rows = 0 then
            raise exception using errcode = '23514',
                message = 'production-runtime Graph purge execution lineage is not acyclic';
        end if;
    end loop;

    delete from agent_graph_technical_completion where thread_id = target_thread_id;
    delete from agent_graph_result where thread_id = target_thread_id;
    delete from agent_graph_shadow_comparison where thread_id = target_thread_id;
    delete from agent_graph_invocation_nonce where thread_id = target_thread_id;
    delete from agent_graph_lease where thread_id = target_thread_id;
    delete from agent_graph_command_attempt where thread_id = target_thread_id;
    delete from checkpoint_writes where thread_id = target_thread_id;
    delete from checkpoint_blobs where thread_id = target_thread_id;
    delete from checkpoints where thread_id = target_thread_id;
    delete from agent_graph_command where thread_id = target_thread_id;
    delete from graph_thread_registry where thread_id = target_thread_id;

    if exists (
        select 1 from graph_thread_registry where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_command where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_command_attempt where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_result where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_lease where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_invocation_nonce where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_technical_completion where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_parallel_receipt_execution
         where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_parallel_receipt_cycle where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_fanout_permit where thread_id = target_thread_id
    ) or exists (
        select 1 from agent_graph_shadow_comparison where thread_id = target_thread_id
    ) or exists (
        select 1 from checkpoint_writes where thread_id = target_thread_id
    ) or exists (
        select 1 from checkpoint_blobs where thread_id = target_thread_id
    ) or exists (
        select 1 from checkpoints where thread_id = target_thread_id
    ) then
        raise exception using errcode = '23514',
            message = 'production-runtime Graph purge did not remove the exact thread closure';
    end if;

    perform set_config('after_sale_flow.graph_target_purge_receipt_id', '', true);
    return selected_receipt_id;
end;
$function$;
