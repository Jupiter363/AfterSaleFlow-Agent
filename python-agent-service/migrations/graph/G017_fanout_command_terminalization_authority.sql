-- Atomically terminalize every provider permit owned by one exact parallel command/frame set.
-- Runtime callers retain SELECT-only access to fanout tables; mutation stays behind this
-- SECURITY DEFINER boundary so failure cleanup cannot bypass the fanout lock discipline.

create function agent_graph_terminalize_command_fanout_permits(
    selected_thread_id varchar,
    selected_command_id varchar,
    selected_attempt_id varchar,
    selected_item_key varchar,
    selected_graph_owner_id varchar,
    selected_graph_fence bigint,
    selected_error_code varchar,
    selected_error_classification varchar
)
returns setof agent_graph_fanout_permit
language plpgsql
security definer
set search_path from current
as $function$
declare
    selected_count integer;
    active_count integer;
    updated_count integer;
begin
    perform pg_advisory_xact_lock(
        hashtextextended('agent-graph-fanout-admission', 0)
    );

    if not exists (
        select 1
          from agent_graph_command command
          join agent_graph_command_attempt attempt
            on attempt.thread_id = command.thread_id
           and attempt.command_id = command.command_id
         where command.thread_id = selected_thread_id
           and command.command_id = selected_command_id
           and command.status in ('ABORTED', 'CANCELLED')
           and command.error_code = selected_error_code
           and command.error_classification = selected_error_classification
           and command.fencing_token = selected_graph_fence
           and attempt.attempt_id = selected_attempt_id
           and attempt.owner_id = selected_graph_owner_id
           and attempt.fencing_token = selected_graph_fence
           and attempt.attempt_status in ('FAILED', 'LEASE_LOST', 'CANCELLED')
           and attempt.error_code = command.error_code
           and attempt.error_classification = command.error_classification
    ) then
        raise exception using
            errcode = 'P0001',
            message = 'GRAPH_FANOUT_BINDING_CONFLICT';
    end if;

    select count(*)
      into selected_count
      from agent_graph_fanout_permit permit
     where permit.thread_id = selected_thread_id
       and permit.command_id = selected_command_id
       and permit.item_key = selected_item_key;
    if selected_count > 32 then
        raise exception using
            errcode = '23514',
            message = 'parallel command retained too many provider permits';
    end if;

    -- All fanout owners take the global advisory lock first.  Lock exact rows in stable
    -- request order before changing them so cleanup cannot deadlock or observe a partial set.
    perform permit.request_id
      from agent_graph_fanout_permit permit
     where permit.thread_id = selected_thread_id
       and permit.command_id = selected_command_id
       and permit.item_key = selected_item_key
     order by permit.request_id
     for update;

    select count(*)
      into active_count
      from agent_graph_fanout_permit permit
     where permit.thread_id = selected_thread_id
       and permit.command_id = selected_command_id
       and permit.item_key = selected_item_key
       and permit.status in ('QUEUED', 'GRANTED');

    -- A mutable thread lease is authority only while there is still work to terminalize.
    -- Once the exact permit set is terminal, historical replay is proven by the immutable
    -- terminal command/attempt above and must survive a later command taking over the lease.
    if active_count > 0 then
        if exists (
            select 1
              from agent_graph_fanout_permit permit
             where permit.thread_id = selected_thread_id
               and permit.command_id = selected_command_id
               and permit.item_key = selected_item_key
               and permit.status in ('QUEUED', 'GRANTED')
               and (
                   permit.graph_lease_owner_id <> selected_graph_owner_id
                   or permit.graph_lease_fencing_token <> selected_graph_fence
               )
        ) or not exists (
            select 1
              from agent_graph_lease lease
             where lease.thread_id = selected_thread_id
               and lease.command_id = selected_command_id
               and lease.owner_id = selected_graph_owner_id
               and (
                   (
                       lease.fencing_token = selected_graph_fence
                       and (
                           lease.released_at is not null
                           or lease.lease_expires_at <= clock_timestamp()
                       )
                   )
                   or (
                       lease.fencing_token = selected_graph_fence + 1
                       and lease.cancelled_at is not null
                       and lease.cancelled_by_command_id = selected_command_id
                   )
               )
        ) then
            raise exception using
                errcode = 'P0001',
                message = 'GRAPH_FANOUT_BINDING_CONFLICT';
        end if;
    end if;

    update agent_graph_fanout_permit permit
       set status = case
               when permit.status = 'QUEUED' then 'CANCELLED'
               else 'RELEASED'
           end,
           cancelled_at = case
               when permit.status = 'QUEUED' then clock_timestamp()
               else null
           end,
           released_at = case
               when permit.status = 'GRANTED' then clock_timestamp()
               else null
           end,
           revision = permit.revision + 1
     where permit.thread_id = selected_thread_id
       and permit.command_id = selected_command_id
       and permit.item_key = selected_item_key
       and permit.graph_lease_owner_id = selected_graph_owner_id
       and permit.graph_lease_fencing_token = selected_graph_fence
       and permit.status in ('QUEUED', 'GRANTED');
    get diagnostics updated_count = row_count;

    if exists (
        select 1
          from agent_graph_fanout_permit permit
         where permit.thread_id = selected_thread_id
           and permit.command_id = selected_command_id
           and permit.item_key = selected_item_key
           and permit.status not in (
               'RELEASED', 'CANCELLED', 'EXPIRED', 'TIMED_OUT', 'ORPHANED'
           )
    ) then
        raise exception using
            errcode = '23514',
            message = 'parallel command provider permit is not terminal';
    end if;

    if updated_count > 0 then
        perform agent_graph_dispatch_fanout_permits();
    end if;

    return query
    select permit.*
      from agent_graph_fanout_permit permit
     where permit.thread_id = selected_thread_id
       and permit.command_id = selected_command_id
       and permit.item_key = selected_item_key
     order by permit.request_id;
end;
$function$;
