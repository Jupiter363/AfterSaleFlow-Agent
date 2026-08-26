-- Atomically terminalize every provider permit owned by one exact parallel command/frame set.
-- Runtime callers retain SELECT-only access to fanout tables; mutation stays behind this
-- SECURITY DEFINER boundary so failure cleanup cannot bypass the fanout lock discipline.

create function agent_graph_terminalize_command_fanout_permits(
    selected_thread_id varchar,
    selected_command_id varchar,
    selected_item_key varchar
)
returns setof agent_graph_fanout_permit
language plpgsql
security definer
set search_path from current
as $function$
declare
    selected_count integer;
    updated_count integer;
begin
    perform pg_advisory_xact_lock(
        hashtextextended('agent-graph-fanout-admission', 0)
    );

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
