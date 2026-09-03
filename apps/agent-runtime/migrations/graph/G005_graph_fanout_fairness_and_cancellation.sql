-- Additive fairness and cancellation hardening for the durable Graph fan-out bulkhead.

select pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));

create sequence agent_graph_fanout_turn_sequence as bigint;

create table agent_graph_fanout_permit_owner_generation (
    request_id varchar(128) not null,
    permit_owner_id varchar(128) not null,
    registered_at timestamptz not null default clock_timestamp(),
    primary key (request_id, permit_owner_id),
    constraint fk_agent_graph_fanout_owner_generation_permit
        foreign key (request_id)
        references agent_graph_fanout_permit(request_id) on delete restrict,
    constraint ck_agent_graph_fanout_owner_generation_owner
        check (permit_owner_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

insert into agent_graph_fanout_permit_owner_generation (
    request_id, permit_owner_id, registered_at
)
select request_id, permit_owner_id, enqueued_at
  from agent_graph_fanout_permit;

select setval(
    'agent_graph_fanout_turn_sequence',
    (select coalesce(max(last_granted_sequence), 0) + 1
       from agent_graph_fanout_tenant_turn),
    false
);

with missing_turn as (
    select permit.tenant_key, min(permit.queue_sequence) as first_queue_sequence
      from agent_graph_fanout_permit permit
      left join agent_graph_fanout_tenant_turn tenant_turn
        on tenant_turn.tenant_key = permit.tenant_key
     where permit.status = 'QUEUED'
       and tenant_turn.tenant_key is null
     group by permit.tenant_key
)
insert into agent_graph_fanout_tenant_turn (
    tenant_key, last_granted_sequence, updated_at
)
select tenant_key, nextval('agent_graph_fanout_turn_sequence'), clock_timestamp()
  from missing_turn
 order by first_queue_sequence, tenant_key;

create function agent_graph_register_fanout_tenant_turn(
    selected_tenant_key varchar,
    selected_request_id varchar
)
returns void
language plpgsql
set search_path from current
as $function$
begin
    if exists (
        select 1 from agent_graph_fanout_permit queued
         where queued.status = 'QUEUED'
           and queued.tenant_key = selected_tenant_key
           and queued.request_id <> selected_request_id
    ) then
        insert into agent_graph_fanout_tenant_turn (
            tenant_key, last_granted_sequence, updated_at
        ) values (
            selected_tenant_key,
            nextval('agent_graph_fanout_turn_sequence'),
            clock_timestamp()
        ) on conflict (tenant_key) do nothing;
    else
        insert into agent_graph_fanout_tenant_turn (
            tenant_key, last_granted_sequence, updated_at
        ) values (
            selected_tenant_key,
            nextval('agent_graph_fanout_turn_sequence'),
            clock_timestamp()
        ) on conflict (tenant_key) do update
        set last_granted_sequence = excluded.last_granted_sequence,
            updated_at = excluded.updated_at;
    end if;
    return;
end;
$function$;

create or replace function agent_graph_dispatch_fanout_permits()
returns void
language plpgsql
set search_path from current
as $function$
declare
    config agent_graph_fanout_config%rowtype;
    candidate varchar(128);
    candidate_tenant varchar(128);
    graph_expiry timestamptz;
begin
    select * into strict config
      from agent_graph_fanout_config
     where config_key = 'signed-synthetic'
     for update;
    if not config.enabled then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_DISABLED';
    end if;

    update agent_graph_fanout_permit
       set status = 'TIMED_OUT', revision = revision + 1
     where status = 'QUEUED' and wait_deadline_at <= clock_timestamp();
    update agent_graph_fanout_permit
       set status = 'EXPIRED', revision = revision + 1
     where status = 'GRANTED' and lease_expires_at <= clock_timestamp();
    update agent_graph_fanout_permit permit
       set status = 'ORPHANED', revision = revision + 1
     where permit.status = 'QUEUED'
       and not exists (
           select 1 from agent_graph_lease lease
            where lease.thread_id = permit.thread_id
              and lease.command_id = permit.command_id
              and lease.owner_id = permit.graph_lease_owner_id
              and lease.fencing_token = permit.graph_lease_fencing_token
              and lease.released_at is null and lease.cancelled_at is null
              and lease.lease_expires_at > clock_timestamp()
       );

    loop
        exit when (
            select count(*) from agent_graph_fanout_permit
             where status = 'GRANTED' and lease_expires_at > clock_timestamp()
        ) >= config.global_limit;

        select permit.request_id, permit.tenant_key, lease.lease_expires_at
          into candidate, candidate_tenant, graph_expiry
          from agent_graph_fanout_permit permit
          join agent_graph_lease lease
            on lease.thread_id = permit.thread_id
           and lease.command_id = permit.command_id
           and lease.owner_id = permit.graph_lease_owner_id
           and lease.fencing_token = permit.graph_lease_fencing_token
           and lease.released_at is null and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
          join agent_graph_fanout_tenant_turn tenant_turn
            on tenant_turn.tenant_key = permit.tenant_key
         where permit.status = 'QUEUED'
           and permit.wait_deadline_at > clock_timestamp()
           and (
               select count(*) from agent_graph_fanout_permit active
                where active.status = 'GRANTED'
                  and active.lease_expires_at > clock_timestamp()
                  and active.tenant_key = permit.tenant_key
           ) < config.tenant_limit
           and (
               select count(*) from agent_graph_fanout_permit active
                where active.status = 'GRANTED'
                  and active.lease_expires_at > clock_timestamp()
                  and active.tenant_key = permit.tenant_key
                  and active.room_key = permit.room_key
           ) < config.room_limit
           and not exists (
               select 1 from agent_graph_fanout_permit earlier
                where earlier.status = 'QUEUED'
                  and earlier.wait_deadline_at > clock_timestamp()
                  and earlier.tenant_key = permit.tenant_key
                  and earlier.room_key = permit.room_key
                  and earlier.queue_sequence < permit.queue_sequence
           )
         order by tenant_turn.last_granted_sequence,
                  permit.queue_sequence,
                  permit.tenant_key,
                  permit.room_key,
                  permit.request_id
         for update of permit, lease skip locked
         limit 1;
        exit when candidate is null;

        update agent_graph_fanout_permit
           set status = 'GRANTED',
               permit_fencing_token = permit_fencing_token + 1,
               granted_at = clock_timestamp(),
               renewed_at = clock_timestamp(),
               lease_expires_at = least(
                   graph_expiry,
                   clock_timestamp() + make_interval(secs => config.permit_lease_seconds)
               ),
               revision = revision + 1
         where request_id = candidate;
        insert into agent_graph_fanout_tenant_turn (
            tenant_key, last_granted_sequence, updated_at
        ) values (
            candidate_tenant,
            nextval('agent_graph_fanout_turn_sequence'),
            clock_timestamp()
        )
        on conflict (tenant_key) do update
        set last_granted_sequence = excluded.last_granted_sequence,
            updated_at = excluded.updated_at;
        candidate := null;
    end loop;
end;
$function$;

create or replace function agent_graph_acquire_fanout_permit(
    selected_request_id varchar,
    selected_tenant_key varchar,
    selected_room_key varchar,
    selected_item_key varchar,
    selected_thread_id varchar,
    selected_command_id varchar,
    selected_graph_owner_id varchar,
    selected_graph_fence bigint,
    selected_permit_owner_id varchar,
    selected_wait_seconds double precision,
    allow_expired_takeover boolean default false
)
returns agent_graph_fanout_permit
language plpgsql
security definer
set search_path from current
as $function$
declare
    config agent_graph_fanout_config%rowtype;
    existing agent_graph_fanout_permit%rowtype;
    result agent_graph_fanout_permit%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));
    perform agent_graph_assert_current_fanout_lease(
        selected_thread_id, selected_command_id, selected_graph_owner_id, selected_graph_fence
    );
    if not exists (
        select 1 from graph_thread_registry thread
         where thread.thread_id = selected_thread_id
           and thread.tenant_surrogate = selected_tenant_key
           and concat(thread.case_id, ':', thread.room_type, ':', thread.room_epoch)
               = selected_room_key
    ) then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_SCOPE_FORGED';
    end if;
    select * into strict config from agent_graph_fanout_config
     where config_key = 'signed-synthetic' for update;
    if selected_wait_seconds <= 0 or selected_wait_seconds > 30 then
        raise exception using errcode = '22023', message = 'GRAPH_FANOUT_WAIT_INVALID';
    end if;
    perform agent_graph_dispatch_fanout_permits();
    select * into existing from agent_graph_fanout_permit
     where request_id = selected_request_id for update;
    if found then
        if row(existing.tenant_key, existing.room_key, existing.item_key)
            is distinct from row(selected_tenant_key, selected_room_key, selected_item_key) then
            raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_BINDING_CONFLICT';
        end if;
        if existing.status in ('EXPIRED', 'RELEASED', 'TIMED_OUT', 'ORPHANED')
            and allow_expired_takeover then
            if exists (
                select 1 from agent_graph_fanout_permit_owner_generation owner_generation
                 where owner_generation.request_id = selected_request_id
                   and owner_generation.permit_owner_id = selected_permit_owner_id
            ) then
                raise exception using
                    errcode = 'P0001',
                    message = 'GRAPH_FANOUT_TAKEOVER_OWNER_REUSED';
            end if;
            perform agent_graph_assert_fanout_queue_capacity(
                selected_tenant_key, selected_room_key
            );
            update agent_graph_fanout_permit
               set thread_id = selected_thread_id,
                   command_id = selected_command_id,
                   graph_lease_owner_id = selected_graph_owner_id,
                   graph_lease_fencing_token = selected_graph_fence,
                   permit_owner_id = selected_permit_owner_id,
                   status = 'QUEUED',
                   queue_sequence = nextval('agent_graph_fanout_queue_sequence'),
                   enqueued_at = clock_timestamp(),
                   wait_deadline_at = clock_timestamp()
                       + make_interval(secs => selected_wait_seconds),
                   granted_at = null, renewed_at = null, lease_expires_at = null,
                   released_at = null, cancelled_at = null, revision = revision + 1
             where request_id = selected_request_id;
            insert into agent_graph_fanout_permit_owner_generation (
                request_id, permit_owner_id
            ) values (selected_request_id, selected_permit_owner_id);
            perform agent_graph_register_fanout_tenant_turn(
                selected_tenant_key, selected_request_id
            );
        elsif row(existing.thread_id, existing.command_id,
                  existing.graph_lease_owner_id, existing.graph_lease_fencing_token,
                  existing.permit_owner_id)
            is distinct from row(selected_thread_id, selected_command_id,
                  selected_graph_owner_id, selected_graph_fence, selected_permit_owner_id) then
            raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_BINDING_CONFLICT';
        elsif existing.status not in ('QUEUED', 'GRANTED') then
            raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_TERMINAL';
        end if;
    else
        perform agent_graph_assert_fanout_queue_capacity(
            selected_tenant_key, selected_room_key
        );
        insert into agent_graph_fanout_permit (
            request_id, tenant_key, room_key, item_key, thread_id, command_id,
            graph_lease_owner_id, graph_lease_fencing_token, permit_owner_id,
            wait_deadline_at
        ) values (
            selected_request_id, selected_tenant_key, selected_room_key, selected_item_key,
            selected_thread_id, selected_command_id, selected_graph_owner_id,
            selected_graph_fence, selected_permit_owner_id,
            clock_timestamp() + make_interval(secs => selected_wait_seconds)
        );
        insert into agent_graph_fanout_permit_owner_generation (
            request_id, permit_owner_id
        ) values (selected_request_id, selected_permit_owner_id);
        perform agent_graph_register_fanout_tenant_turn(
            selected_tenant_key, selected_request_id
        );
    end if;
    perform agent_graph_dispatch_fanout_permits();
    select * into strict result from agent_graph_fanout_permit
     where request_id = selected_request_id;
    return result;
end;
$function$;

create or replace function agent_graph_finish_fanout_permit(
    selected_request_id varchar, selected_permit_fence bigint,
    selected_thread_id varchar, selected_command_id varchar,
    selected_graph_owner_id varchar, selected_graph_fence bigint,
    selected_permit_owner_id varchar, cancel_permit boolean
)
returns agent_graph_fanout_permit
language plpgsql security definer set search_path from current
as $function$
declare result agent_graph_fanout_permit%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));
    update agent_graph_fanout_permit
       set status = case when cancel_permit then 'CANCELLED' else 'RELEASED' end,
           cancelled_at = case when cancel_permit then clock_timestamp() else null end,
           released_at = case when cancel_permit then null else clock_timestamp() end,
           revision = revision + 1
     where request_id = selected_request_id
       and status = 'GRANTED' and permit_fencing_token = selected_permit_fence
       and permit_owner_id = selected_permit_owner_id
       and thread_id = selected_thread_id and command_id = selected_command_id
       and graph_lease_owner_id = selected_graph_owner_id
       and graph_lease_fencing_token = selected_graph_fence
     returning * into result;
    if not found then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_LOST';
    end if;
    perform agent_graph_dispatch_fanout_permits();
    return result;
end;
$function$;

create function agent_graph_cancel_or_release_fanout_permit(
    selected_request_id varchar, selected_thread_id varchar, selected_command_id varchar,
    selected_graph_owner_id varchar, selected_graph_fence bigint,
    selected_permit_owner_id varchar
)
returns agent_graph_fanout_permit
language plpgsql security definer set search_path from current
as $function$
declare result agent_graph_fanout_permit%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));
    update agent_graph_fanout_permit
       set status = case when status = 'QUEUED' then 'CANCELLED' else 'RELEASED' end,
           cancelled_at = case when status = 'QUEUED' then clock_timestamp() else null end,
           released_at = case when status = 'GRANTED' then clock_timestamp() else null end,
           revision = revision + 1
     where request_id = selected_request_id
       and status in ('QUEUED', 'GRANTED')
       and permit_owner_id = selected_permit_owner_id
       and thread_id = selected_thread_id and command_id = selected_command_id
       and graph_lease_owner_id = selected_graph_owner_id
       and graph_lease_fencing_token = selected_graph_fence
     returning * into result;
    if not found then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_LOST';
    end if;
    perform agent_graph_dispatch_fanout_permits();
    return result;
end;
$function$;

create function reject_agent_graph_fanout_owner_generation_mutation()
returns trigger language plpgsql as $function$
begin
    raise exception using
        errcode = '23514',
        message = 'Graph fanout permit owner generations are append-only';
end;
$function$;

create trigger trg_reject_agent_graph_fanout_owner_generation_mutation
before update or delete on agent_graph_fanout_permit_owner_generation
for each row execute function reject_agent_graph_fanout_owner_generation_mutation();
