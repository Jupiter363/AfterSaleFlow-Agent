-- Graph command, thread, result, lease, attempt, and durable nonce foundation.

create table graph_thread_registry (
    thread_id varchar(39) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    actor_scope_json jsonb not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    shared_session boolean not null default false,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    lifecycle_status varchar(32) not null default 'ACTIVE',
    cognitive_revision bigint not null default 0,
    last_checkpoint_ns varchar(128),
    last_checkpoint_id varchar(128),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    retired_at timestamptz,
    constraint ck_graph_thread_id
        check (thread_id ~ '^grt\.v1\.[0-9a-f]{32}$'),
    constraint ck_graph_thread_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_graph_thread_epoch check (room_epoch >= 0),
    constraint ck_graph_thread_actor_scope
        check (
            jsonb_typeof(actor_scope_json) = 'object'
            and octet_length(actor_scope_json::text) <= 4096
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_graph_thread_shared_scope
        check (not shared_session or room_type = 'HEARING'),
    constraint ck_graph_thread_lifecycle
        check (lifecycle_status in ('ACTIVE', 'RETIRED', 'CANCELLED')),
    constraint ck_graph_thread_revision check (cognitive_revision >= 0),
    constraint ck_graph_thread_retired
        check (
            (lifecycle_status = 'RETIRED' and retired_at is not null)
            or (lifecycle_status <> 'RETIRED' and retired_at is null)
        ),
    constraint uq_graph_thread_scope
        unique (
            tenant_surrogate,
            case_id,
            room_type,
            room_epoch,
            actor_scope_hash,
            agent_session_id,
            graph_key,
            graph_version,
            checkpoint_schema_version
        )
);

create table agent_graph_command (
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_schema_version varchar(128) not null,
    request_json jsonb not null,
    request_hash varchar(64) not null,
    execution_mode varchar(32) not null default 'SHADOW',
    room_epoch bigint not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    prompt_version varchar(128) not null,
    model_profile_id varchar(128) not null,
    output_schema_version varchar(128) not null,
    policy_version varchar(128) not null,
    guardrail_version varchar(128) not null,
    tool_policy_version varchar(128) not null,
    deadline_at timestamptz not null,
    status varchar(32) not null default 'REGISTERED',
    attempt_count integer not null default 0,
    fencing_token bigint,
    start_checkpoint_ns varchar(128),
    start_checkpoint_id varchar(128),
    committed_checkpoint_ns varchar(128),
    committed_checkpoint_id varchar(128),
    result_ref varchar(512),
    result_hash varchar(64),
    error_code varchar(128),
    error_classification varchar(64),
    registered_at timestamptz not null default clock_timestamp(),
    started_at timestamptz,
    result_checkpointed_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    aborted_at timestamptz,
    updated_at timestamptz not null default clock_timestamp(),
    command_revision bigint not null default 0,
    primary key (thread_id, command_id),
    constraint fk_agent_graph_command_thread
        foreign key (thread_id) references graph_thread_registry(thread_id) on delete restrict,
    constraint uq_agent_graph_command_hash_binding
        unique (thread_id, command_id, request_hash),
    constraint ck_agent_graph_command_payload
        check (
            jsonb_typeof(request_json) = 'object'
            and octet_length(request_json::text) <= 65536
            and request_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_command_mode check (execution_mode = 'SHADOW'),
    constraint ck_agent_graph_command_epoch check (room_epoch >= 0),
    constraint ck_agent_graph_command_status
        check (
            status in (
                'REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED',
                'COMPLETED', 'CANCELLED', 'ABORTED'
            )
        ),
    constraint ck_agent_graph_command_attempts check (attempt_count >= 0),
    constraint ck_agent_graph_command_fence
        check (fencing_token is null or fencing_token >= 1),
    constraint ck_agent_graph_command_result
        check (
            (result_hash is null and result_ref is null)
            or (result_hash ~ '^[0-9a-f]{64}$' and result_ref is not null)
        ),
    constraint ck_agent_graph_command_revision check (command_revision >= 0),
    constraint ck_agent_graph_command_terminal_time
        check (
            (status = 'COMPLETED' and completed_at is not null and result_hash is not null)
            or (status = 'CANCELLED' and cancelled_at is not null)
            or (status = 'ABORTED' and aborted_at is not null)
            or status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
        ),
    constraint ck_agent_graph_command_checkpoint_binding
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
        )
);

create index idx_agent_graph_command_status_deadline
    on agent_graph_command(status, deadline_at);
create index idx_agent_graph_command_version
    on agent_graph_command(graph_key, graph_version, checkpoint_schema_version, status);

create table agent_graph_command_attempt (
    attempt_id varchar(64) primary key,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    attempt_no integer not null,
    owner_id varchar(128) not null,
    fencing_token bigint not null,
    attempt_status varchar(32) not null,
    provider_call_count integer not null default 0,
    error_code varchar(128),
    error_classification varchar(64),
    started_at timestamptz not null default clock_timestamp(),
    last_heartbeat_at timestamptz not null default clock_timestamp(),
    completed_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_graph_attempt_command
        foreign key (thread_id, command_id)
        references agent_graph_command(thread_id, command_id) on delete restrict,
    constraint uq_agent_graph_attempt_number unique (thread_id, command_id, attempt_no),
    constraint uq_agent_graph_attempt_fence unique (thread_id, command_id, fencing_token),
    constraint ck_agent_graph_attempt_number check (attempt_no >= 1),
    constraint ck_agent_graph_attempt_fence check (fencing_token >= 1),
    constraint ck_agent_graph_attempt_calls check (provider_call_count >= 0),
    constraint ck_agent_graph_attempt_status
        check (
            attempt_status in (
                'EXECUTING', 'CHECKPOINTED', 'COMPLETED',
                'FAILED', 'LEASE_LOST', 'CANCELLED'
            )
        ),
    constraint ck_agent_graph_attempt_terminal_time
        check (
            (attempt_status in ('COMPLETED', 'FAILED', 'LEASE_LOST', 'CANCELLED')
                and completed_at is not null)
            or (attempt_status in ('EXECUTING', 'CHECKPOINTED') and completed_at is null)
        )
);

create index idx_agent_graph_attempt_active
    on agent_graph_command_attempt(thread_id, command_id, attempt_status, attempt_no desc);

create table agent_graph_result (
    result_id varchar(64) primary key,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    execution_mode varchar(32) not null default 'SHADOW',
    result_schema_version varchar(128) not null,
    checkpoint_ns varchar(128) not null,
    checkpoint_id varchar(128) not null,
    cognitive_revision bigint not null,
    terminal_status varchar(32) not null,
    result_json jsonb not null,
    result_ref varchar(512) not null,
    result_hash varchar(64) not null,
    usage_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_graph_result_command_hash
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash) on delete restrict,
    constraint uq_agent_graph_result_command unique (thread_id, command_id),
    constraint uq_agent_graph_result_hash_binding
        unique (thread_id, command_id, result_hash),
    constraint ck_agent_graph_result_mode check (execution_mode = 'SHADOW'),
    constraint ck_agent_graph_result_revision check (cognitive_revision >= 0),
    constraint ck_agent_graph_result_status
        check (terminal_status in ('COMPLETED', 'NEEDS_INPUT', 'NEEDS_REVIEW', 'FAILED')),
    constraint ck_agent_graph_result_payload
        check (
            jsonb_typeof(result_json) = 'object'
            and octet_length(result_json::text) <= 65536
            and result_hash ~ '^[0-9a-f]{64}$'
            and jsonb_typeof(usage_json) = 'object'
            and octet_length(usage_json::text) <= 16384
        )
);

create index idx_agent_graph_result_checkpoint
    on agent_graph_result(thread_id, checkpoint_ns, checkpoint_id);

create table agent_graph_lease (
    thread_id varchar(39) primary key,
    command_id varchar(128) not null,
    owner_id varchar(128) not null,
    fencing_token bigint not null,
    lease_expires_at timestamptz not null,
    acquired_at timestamptz not null default clock_timestamp(),
    renewed_at timestamptz not null default clock_timestamp(),
    released_at timestamptz,
    cancelled_at timestamptz,
    cancelled_by_command_id varchar(128),
    lease_revision bigint not null default 0,
    constraint fk_agent_graph_lease_command
        foreign key (thread_id, command_id)
        references agent_graph_command(thread_id, command_id) on delete restrict,
    constraint fk_agent_graph_lease_cancellation_command
        foreign key (thread_id, cancelled_by_command_id)
        references agent_graph_command(thread_id, command_id) on delete restrict,
    constraint ck_agent_graph_lease_fence check (fencing_token >= 1),
    constraint ck_agent_graph_lease_revision check (lease_revision >= 0),
    constraint ck_agent_graph_lease_window
        check (
            acquired_at <= renewed_at
            and lease_expires_at > renewed_at
            and lease_expires_at <= renewed_at + interval '30 seconds'
        ),
    constraint ck_agent_graph_lease_cancel
        check (
            (
                (cancelled_at is null and cancelled_by_command_id is null)
                or (cancelled_at is not null and cancelled_by_command_id is not null)
            )
            and not (released_at is not null and cancelled_at is not null)
        )
);

create index idx_agent_graph_lease_expiry
    on agent_graph_lease(lease_expires_at) where released_at is null;

create table agent_graph_invocation_nonce (
    issuer varchar(128) not null,
    key_id varchar(128) not null,
    jti varchar(128) not null,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    request_hash varchar(64) not null,
    issued_at timestamptz not null,
    token_expires_at timestamptz not null,
    retained_until timestamptz not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (issuer, key_id, jti),
    constraint fk_agent_graph_nonce_command_hash
        foreign key (thread_id, command_id, request_hash)
        references agent_graph_command(thread_id, command_id, request_hash) on delete restrict,
    constraint ck_agent_graph_nonce_hash check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_agent_graph_nonce_identity
        check (
            issuer ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and jti ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_agent_graph_nonce_times
        check (
            token_expires_at > issued_at
            and token_expires_at <= issued_at + interval '60 seconds'
            and retained_until >= issued_at + interval '24 hours'
        )
);

create index idx_agent_graph_nonce_retention on agent_graph_invocation_nonce(retained_until);

create function guard_agent_graph_nonce_delete()
returns trigger
language plpgsql
as $function$
begin
    if old.retained_until > statement_timestamp() then
        raise exception using errcode = '23514', message = 'graph invocation nonce is retained';
    end if;
    return old;
end;
$function$;

create trigger trg_guard_agent_graph_nonce_delete
before delete on agent_graph_invocation_nonce
for each row execute function guard_agent_graph_nonce_delete();

create function reject_agent_graph_nonce_update()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514', message = 'graph invocation nonces are immutable';
end;
$function$;

create trigger trg_reject_agent_graph_nonce_update
before update on agent_graph_invocation_nonce
for each row execute function reject_agent_graph_nonce_update();

create function guard_graph_thread_update()
returns trigger
language plpgsql
as $function$
begin
    if row(
        new.thread_id, new.tenant_surrogate, new.case_id, new.room_type,
        new.room_epoch, new.actor_scope_json, new.actor_scope_hash,
        new.agent_session_id, new.shared_session, new.graph_key,
        new.graph_version, new.checkpoint_schema_version
    ) is distinct from row(
        old.thread_id, old.tenant_surrogate, old.case_id, old.room_type,
        old.room_epoch, old.actor_scope_json, old.actor_scope_hash,
        old.agent_session_id, old.shared_session, old.graph_key,
        old.graph_version, old.checkpoint_schema_version
    ) then
        raise exception using errcode = '23514',
            message = 'graph thread identity and version bindings are immutable';
    end if;
    if new.cognitive_revision < old.cognitive_revision then
        raise exception using errcode = '23514',
            message = 'graph cognitive revision cannot decrease';
    end if;
    if old.lifecycle_status <> 'ACTIVE' and new.lifecycle_status <> old.lifecycle_status then
        raise exception using errcode = '23514',
            message = 'terminal graph thread lifecycle cannot reactivate';
    end if;
    if row(new.last_checkpoint_ns, new.last_checkpoint_id)
        is distinct from row(old.last_checkpoint_ns, old.last_checkpoint_id)
        and new.cognitive_revision <= old.cognitive_revision then
        raise exception using errcode = '23514',
            message = 'thread checkpoint change must advance cognitive revision';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_graph_thread_update
before update on graph_thread_registry
for each row execute function guard_graph_thread_update();

create function guard_agent_graph_command_update()
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
        or (old.status = 'EXECUTING' and new.status in ('RESULT_CHECKPOINTED', 'CANCELLED', 'ABORTED'))
        or (old.status = 'RESULT_CHECKPOINTED' and new.status = 'COMPLETED')
    );
    if not transition_allowed then
        raise exception using errcode = '23514', message = 'illegal graph command transition';
    end if;
    if old.result_hash is not null and row(new.result_hash, new.result_ref)
        is distinct from row(old.result_hash, old.result_ref) then
        raise exception using errcode = '23514', message = 'graph result binding is immutable';
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

create trigger trg_guard_agent_graph_command_update
before update on agent_graph_command
for each row execute function guard_agent_graph_command_update();

create function guard_agent_graph_attempt_update()
returns trigger
language plpgsql
as $function$
declare
    transition_allowed boolean;
begin
    if row(
        new.attempt_id, new.thread_id, new.command_id, new.attempt_no,
        new.owner_id, new.fencing_token, new.started_at, new.created_at
    ) is distinct from row(
        old.attempt_id, old.thread_id, old.command_id, old.attempt_no,
        old.owner_id, old.fencing_token, old.started_at, old.created_at
    ) then
        raise exception using errcode = '23514',
            message = 'graph command attempt identity is immutable';
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

create trigger trg_guard_agent_graph_attempt_update
before update on agent_graph_command_attempt
for each row execute function guard_agent_graph_attempt_update();

create function reject_agent_graph_result_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514', message = 'graph results are immutable';
end;
$function$;

create trigger trg_reject_agent_graph_result_mutation
before update or delete on agent_graph_result
for each row execute function reject_agent_graph_result_mutation();

create function guard_agent_graph_lease_update()
returns trigger
language plpgsql
as $function$
declare
    identity_changed boolean;
    cancelling boolean;
    taking_over boolean;
begin
    if new.fencing_token < old.fencing_token then
        raise exception using errcode = '23514', message = 'lease fence cannot decrease';
    end if;
    identity_changed := row(new.owner_id, new.command_id)
        is distinct from row(old.owner_id, old.command_id);
    cancelling := old.cancelled_at is null and new.cancelled_at is not null;
    taking_over := new.fencing_token = old.fencing_token + 1
        and new.cancelled_at is null
        and new.cancelled_by_command_id is null
        and new.released_at is null
        and (
            old.lease_expires_at <= clock_timestamp()
            or old.cancelled_at is not null
            or old.released_at is not null
        );

    if cancelling and (
        new.fencing_token <> old.fencing_token + 1
        or identity_changed
        or old.released_at is not null
    ) then
        raise exception using errcode = '23514',
            message = 'lease cancellation must fence one active owner exactly once';
    end if;
    if not cancelling and not taking_over
        and new.fencing_token <> old.fencing_token then
        raise exception using errcode = '23514',
            message = 'only cancellation or eligible takeover may increment the fence';
    end if;
    if identity_changed and not taking_over then
        raise exception using errcode = '23514',
            message = 'lease identity can change only during takeover';
    end if;
    if not taking_over and new.acquired_at <> old.acquired_at then
        raise exception using errcode = '23514',
            message = 'lease renewal cannot rewrite acquisition time';
    end if;
    if taking_over and new.acquired_at < old.renewed_at then
        raise exception using errcode = '23514',
            message = 'lease takeover acquisition cannot move backwards';
    end if;
    if new.renewed_at < old.renewed_at then
        raise exception using errcode = '23514', message = 'lease renewal cannot move backwards';
    end if;
    if old.released_at is not null and not taking_over
        and new.released_at is distinct from old.released_at then
        raise exception using errcode = '23514', message = 'lease release is forward-only';
    end if;
    if old.cancelled_at is not null and not taking_over
        and row(new.cancelled_at, new.cancelled_by_command_id)
        is distinct from row(old.cancelled_at, old.cancelled_by_command_id) then
        raise exception using errcode = '23514', message = 'lease cancellation is forward-only';
    end if;
    if old.released_at is not null and not taking_over and new is distinct from old then
        raise exception using errcode = '23514',
            message = 'released lease can change only through fenced takeover';
    end if;
    if old.cancelled_at is not null and not taking_over and new is distinct from old then
        raise exception using errcode = '23514',
            message = 'cancelled lease can change only through fenced takeover';
    end if;
    if new.lease_revision < old.lease_revision then
        raise exception using errcode = '23514', message = 'lease revision cannot decrease';
    end if;
    return new;
end;
$function$;

create function guard_agent_graph_lease_insert()
returns trigger
language plpgsql
as $function$
begin
    if new.fencing_token <> 1 then
        raise exception using errcode = '23514',
            message = 'first graph lease fencing token must be one';
    end if;
    if new.released_at is not null or new.cancelled_at is not null then
        raise exception using errcode = '23514',
            message = 'new graph lease must be active';
    end if;
    return new;
end;
$function$;

create trigger trg_guard_agent_graph_lease_insert
before insert on agent_graph_lease
for each row execute function guard_agent_graph_lease_insert();

create trigger trg_guard_agent_graph_lease_update
before update on agent_graph_lease
for each row execute function guard_agent_graph_lease_update();
