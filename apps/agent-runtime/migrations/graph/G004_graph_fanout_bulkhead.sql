-- Durable, database-owned fair admission for Graph fan-out work.

create sequence agent_graph_fanout_queue_sequence as bigint;

create table agent_graph_fanout_config (
    config_key varchar(32) primary key,
    enabled boolean not null,
    room_limit integer not null,
    tenant_limit integer not null,
    global_limit integer not null,
    room_queue_limit integer not null,
    tenant_queue_limit integer not null,
    global_queue_limit integer not null,
    permit_lease_seconds integer not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint ck_agent_graph_fanout_config_key check (config_key = 'signed-synthetic'),
    constraint ck_agent_graph_fanout_limits check (
        enabled
        and room_limit between 1 and 8
        and room_limit <= tenant_limit
        and tenant_limit <= global_limit
        and room_queue_limit >= 1
        and room_queue_limit <= tenant_queue_limit
        and tenant_queue_limit <= global_queue_limit
        and permit_lease_seconds between 5 and 30
    )
);

insert into agent_graph_fanout_config (
    config_key, enabled, room_limit, tenant_limit, global_limit,
    room_queue_limit, tenant_queue_limit, global_queue_limit, permit_lease_seconds
) values ('signed-synthetic', true, 8, 16, 32, 100, 128, 256, 20);

create table agent_graph_fanout_tenant_turn (
    tenant_key varchar(128) primary key,
    last_granted_sequence bigint not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint ck_agent_graph_fanout_tenant_key
        check (tenant_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    constraint ck_agent_graph_fanout_turn check (last_granted_sequence > 0)
);

create table agent_graph_fanout_permit (
    request_id varchar(128) primary key,
    tenant_key varchar(128) not null,
    room_key varchar(128) not null,
    item_key varchar(128) not null,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    graph_lease_owner_id varchar(128) not null,
    graph_lease_fencing_token bigint not null,
    permit_owner_id varchar(128) not null,
    permit_fencing_token bigint not null default 0,
    status varchar(32) not null default 'QUEUED',
    queue_sequence bigint not null default nextval('agent_graph_fanout_queue_sequence'),
    enqueued_at timestamptz not null default clock_timestamp(),
    wait_deadline_at timestamptz not null,
    granted_at timestamptz,
    renewed_at timestamptz,
    lease_expires_at timestamptz,
    released_at timestamptz,
    cancelled_at timestamptz,
    revision bigint not null default 0,
    constraint fk_agent_graph_fanout_thread
        foreign key (thread_id) references graph_thread_registry(thread_id) on delete restrict,
    constraint fk_agent_graph_fanout_command
        foreign key (thread_id, command_id)
        references agent_graph_command(thread_id, command_id) on delete restrict,
    constraint ck_agent_graph_fanout_request_id
        check (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    constraint ck_agent_graph_fanout_keys check (
        tenant_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and room_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and item_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and graph_lease_owner_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and permit_owner_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    constraint ck_agent_graph_fanout_fences check (
        graph_lease_fencing_token > 0 and permit_fencing_token >= 0
    ),
    constraint ck_agent_graph_fanout_status check (
        status in (
            'QUEUED', 'GRANTED', 'RELEASED', 'CANCELLED',
            'EXPIRED', 'TIMED_OUT', 'ORPHANED'
        )
    ),
    constraint ck_agent_graph_fanout_times check (
        wait_deadline_at > enqueued_at
        and (
            (status = 'QUEUED' and granted_at is null and renewed_at is null
                and lease_expires_at is null and released_at is null and cancelled_at is null)
            or (status = 'GRANTED' and granted_at is not null and renewed_at is not null
                and lease_expires_at > renewed_at and released_at is null and cancelled_at is null)
            or (status = 'RELEASED' and granted_at is not null and released_at is not null
                and cancelled_at is null)
            or (status = 'CANCELLED' and cancelled_at is not null and released_at is null)
            or (status = 'EXPIRED' and granted_at is not null and lease_expires_at is not null
                and released_at is null and cancelled_at is null)
            or (status = 'TIMED_OUT' and granted_at is null and released_at is null
                and cancelled_at is null)
            or (status = 'ORPHANED' and granted_at is null and released_at is null
                and cancelled_at is null)
        )
    ),
    constraint ck_agent_graph_fanout_revision check (revision >= 0)
);

create index idx_agent_graph_fanout_queue
    on agent_graph_fanout_permit(status, queue_sequence) where status = 'QUEUED';
create index idx_agent_graph_fanout_active_tenant
    on agent_graph_fanout_permit(tenant_key, lease_expires_at) where status = 'GRANTED';
create index idx_agent_graph_fanout_active_room
    on agent_graph_fanout_permit(tenant_key, room_key, lease_expires_at)
    where status = 'GRANTED';
create unique index uq_agent_graph_fanout_logical_active
    on agent_graph_fanout_permit(thread_id, command_id, item_key)
    where status in ('QUEUED', 'GRANTED');

create function agent_graph_assert_current_fanout_lease(
    selected_thread_id varchar,
    selected_command_id varchar,
    selected_owner_id varchar,
    selected_fencing_token bigint
)
returns timestamptz
language plpgsql
set search_path from current
as $function$
declare
    graph_expiry timestamptz;
begin
    select lease_expires_at
      into graph_expiry
      from agent_graph_lease
     where thread_id = selected_thread_id
       and command_id = selected_command_id
       and owner_id = selected_owner_id
       and fencing_token = selected_fencing_token
       and released_at is null
       and cancelled_at is null
       and lease_expires_at > clock_timestamp()
     for share;
    if graph_expiry is null then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_GRAPH_LEASE_LOST';
    end if;
    return graph_expiry;
end;
$function$;

create function agent_graph_dispatch_fanout_permits()
returns void
language plpgsql
set search_path from current
as $function$
declare
    config agent_graph_fanout_config%rowtype;
    candidate varchar(128);
    candidate_tenant varchar(128);
    candidate_sequence bigint;
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

        select permit.request_id, permit.tenant_key, permit.queue_sequence, lease.lease_expires_at
          into candidate, candidate_tenant, candidate_sequence, graph_expiry
          from agent_graph_fanout_permit permit
          join agent_graph_lease lease
            on lease.thread_id = permit.thread_id
           and lease.command_id = permit.command_id
           and lease.owner_id = permit.graph_lease_owner_id
           and lease.fencing_token = permit.graph_lease_fencing_token
           and lease.released_at is null and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
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
         order by permit.queue_sequence
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
        ) values (candidate_tenant, candidate_sequence, clock_timestamp())
        on conflict (tenant_key) do update
        set last_granted_sequence = excluded.last_granted_sequence,
            updated_at = excluded.updated_at;
        candidate := null;
    end loop;
end;
$function$;

create function agent_graph_assert_fanout_queue_capacity(
    selected_tenant_key varchar,
    selected_room_key varchar
)
returns void
language plpgsql
set search_path from current
as $function$
declare config agent_graph_fanout_config%rowtype;
begin
    select * into strict config from agent_graph_fanout_config
     where config_key = 'signed-synthetic';
    if (select count(*) from agent_graph_fanout_permit where status = 'QUEUED')
        >= config.global_queue_limit then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_QUEUE_GLOBAL';
    end if;
    if (select count(*) from agent_graph_fanout_permit
         where status = 'QUEUED' and tenant_key = selected_tenant_key)
        >= config.tenant_queue_limit then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_QUEUE_TENANT';
    end if;
    if (select count(*) from agent_graph_fanout_permit
         where status = 'QUEUED' and tenant_key = selected_tenant_key
           and room_key = selected_room_key) >= config.room_queue_limit then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_QUEUE_ROOM';
    end if;
end;
$function$;

create function agent_graph_acquire_fanout_permit(
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
    end if;
    perform agent_graph_dispatch_fanout_permits();
    select * into strict result from agent_graph_fanout_permit
     where request_id = selected_request_id;
    return result;
end;
$function$;

create function agent_graph_renew_fanout_permit(
    selected_request_id varchar, selected_permit_fence bigint,
    selected_thread_id varchar, selected_command_id varchar,
    selected_graph_owner_id varchar, selected_graph_fence bigint,
    selected_permit_owner_id varchar
)
returns agent_graph_fanout_permit
language plpgsql security definer set search_path from current
as $function$
declare
    config agent_graph_fanout_config%rowtype;
    graph_expiry timestamptz;
    result agent_graph_fanout_permit%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));
    graph_expiry := agent_graph_assert_current_fanout_lease(
        selected_thread_id, selected_command_id, selected_graph_owner_id, selected_graph_fence
    );
    select * into strict config from agent_graph_fanout_config
     where config_key = 'signed-synthetic' for update;
    update agent_graph_fanout_permit
       set renewed_at = clock_timestamp(),
           lease_expires_at = least(
               graph_expiry,
               clock_timestamp() + make_interval(secs => config.permit_lease_seconds)
           ), revision = revision + 1
     where request_id = selected_request_id and status = 'GRANTED'
       and permit_fencing_token = selected_permit_fence
       and permit_owner_id = selected_permit_owner_id
       and thread_id = selected_thread_id and command_id = selected_command_id
       and graph_lease_owner_id = selected_graph_owner_id
       and graph_lease_fencing_token = selected_graph_fence
       and lease_expires_at > clock_timestamp()
     returning * into result;
    if not found then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_LOST';
    end if;
    return result;
end;
$function$;

create function agent_graph_finish_fanout_permit(
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
    perform agent_graph_assert_current_fanout_lease(
        selected_thread_id, selected_command_id, selected_graph_owner_id, selected_graph_fence
    );
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
       and lease_expires_at > clock_timestamp()
     returning * into result;
    if not found then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_LOST';
    end if;
    perform agent_graph_dispatch_fanout_permits();
    return result;
end;
$function$;

create function agent_graph_cancel_queued_fanout_permit(
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
    perform agent_graph_assert_current_fanout_lease(
        selected_thread_id, selected_command_id, selected_graph_owner_id, selected_graph_fence
    );
    update agent_graph_fanout_permit
       set status = 'CANCELLED', cancelled_at = clock_timestamp(), revision = revision + 1
     where request_id = selected_request_id and status = 'QUEUED'
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

create function agent_graph_validate_fanout_recovery(
    selected_request_id varchar, selected_permit_fence bigint,
    selected_thread_id varchar, selected_command_id varchar,
    selected_graph_owner_id varchar, selected_graph_fence bigint,
    selected_permit_owner_id varchar
)
returns agent_graph_fanout_permit
language plpgsql security definer set search_path from current
as $function$
declare result agent_graph_fanout_permit%rowtype;
begin
    perform pg_advisory_xact_lock(hashtextextended('agent-graph-fanout-admission', 0));
    perform agent_graph_assert_current_fanout_lease(
        selected_thread_id, selected_command_id, selected_graph_owner_id, selected_graph_fence
    );
    select * into result from agent_graph_fanout_permit
     where request_id = selected_request_id and status = 'GRANTED'
       and permit_fencing_token = selected_permit_fence
       and permit_owner_id = selected_permit_owner_id
       and thread_id = selected_thread_id and command_id = selected_command_id
       and graph_lease_owner_id = selected_graph_owner_id
       and graph_lease_fencing_token = selected_graph_fence
       and lease_expires_at > clock_timestamp()
     for update;
    if not found then
        raise exception using errcode = 'P0001', message = 'GRAPH_FANOUT_PERMIT_LOST';
    end if;
    return result;
end;
$function$;

create function reject_agent_graph_fanout_delete()
returns trigger language plpgsql as $function$
begin
    raise exception using errcode = '23514', message = 'Graph fanout permit rows are durable';
end;
$function$;

create trigger trg_reject_agent_graph_fanout_delete
before delete on agent_graph_fanout_permit
for each row execute function reject_agent_graph_fanout_delete();
