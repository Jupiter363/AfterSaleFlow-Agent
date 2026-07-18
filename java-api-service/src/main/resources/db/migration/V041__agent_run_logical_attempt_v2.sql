-- Expand AgentRun into a logical-run ledger with ordered execution attempts.
-- V1 rows and writers remain valid; V2 stays dormant until the runtime selector enables it.

alter table agent_run
    add column tenant_surrogate varchar(128) default 'legacy-default',
    add column protocol varchar(32) default 'agent_stream.v1',
    add column logical_idempotency_key varchar(128),
    add column executor_kind varchar(32) default 'LEGACY_WORKER',
    add column finalization_status varchar(32) default 'UNCOMMITTED',
    add column room_epoch_id varchar(64),
    add column room_type varchar(32),
    add column room_epoch bigint default 0,
    add column process_revision bigint default 0,
    add column fencing_token bigint default 0,
    add column request_hash varchar(64),
    add column attempt_limit integer default 1,
    add column deadline_at timestamptz,
    add column result_ready_attempt_id varchar(128),
    add column committed_attempt_id varchar(128),
    add column final_result_hash varchar(64),
    add column committed_manifest_id varchar(64),
    add column committed_manifest_hash varchar(64),
    add column final_stream_sequence_no bigint,
    add column finalized_at timestamptz,
    add column logical_version bigint not null default 0;

update agent_run
set tenant_surrogate = coalesce(tenant_surrogate, 'legacy-default'),
    protocol = coalesce(protocol, 'agent_stream.v1'),
    logical_idempotency_key = coalesce(logical_idempotency_key, 'legacy:' || id),
    executor_kind = coalesce(executor_kind, 'LEGACY_WORKER'),
    finalization_status = case
        when run_status = 'COMPLETED' then 'LEGACY_COMMITTED'
        else coalesce(finalization_status, 'UNCOMMITTED')
    end,
    room_epoch = coalesce(room_epoch, 0),
    process_revision = coalesce(process_revision, 0),
    fencing_token = coalesce(fencing_token, 0),
    request_hash = coalesce(
        request_hash,
        case
            when stream_request_hash ~ '^[0-9a-f]{64}$' then stream_request_hash
            else null
        end
    ),
    attempt_limit = coalesce(attempt_limit, 1),
    result_ready_attempt_id = case
        when run_status = 'COMPLETED' then id
        else result_ready_attempt_id
    end,
    committed_attempt_id = case
        when run_status = 'COMPLETED' then id
        else committed_attempt_id
    end;

alter table agent_run
    alter column tenant_surrogate set not null,
    alter column protocol set not null,
    alter column logical_idempotency_key set not null,
    alter column executor_kind set not null,
    alter column finalization_status set not null,
    alter column room_epoch set not null,
    alter column process_revision set not null,
    alter column fencing_token set not null,
    alter column attempt_limit set not null;

alter table agent_run
    add constraint ck_agent_run_protocol_v2
        check (protocol in ('agent_stream.v1', 'agent-stream.v2')),
    add constraint ck_agent_run_executor_kind_v2
        check (executor_kind in ('LEGACY_WORKER', 'TEMPORAL_ACTIVITY')),
    add constraint ck_agent_run_finalization_status_v2
        check (finalization_status in ('UNCOMMITTED', 'LEGACY_COMMITTED', 'COMMITTED')),
    add constraint ck_agent_run_room_type_v2
        check (room_type is null or room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    add constraint ck_agent_run_revision_v2
        check (room_epoch >= 0 and process_revision >= 0 and fencing_token >= 0),
    add constraint ck_agent_run_attempt_limit_v2
        check (attempt_limit between 1 and 100),
    add constraint ck_agent_run_request_hash_v2
        check (request_hash is null or request_hash ~ '^[0-9a-f]{64}$'),
    add constraint ck_agent_run_final_hashes_v2
        check (
            (final_result_hash is null or final_result_hash ~ '^[0-9a-f]{64}$')
            and (committed_manifest_hash is null or committed_manifest_hash ~ '^[0-9a-f]{64}$')
        ),
    add constraint ck_agent_run_final_sequence_v2
        check (final_stream_sequence_no is null or final_stream_sequence_no >= 0),
    add constraint ck_agent_run_formal_commit_v2
        check (
            finalization_status <> 'COMMITTED'
            or (
                committed_attempt_id is not null
                and final_result_hash is not null
                and committed_manifest_id is not null
                and committed_manifest_hash is not null
                and final_stream_sequence_no is not null
                and finalized_at is not null
            )
        );

create unique index uq_agent_run_logical_idempotency_v2
    on agent_run(case_id, logical_idempotency_key)
    where case_id is not null;

create index idx_agent_run_v2_executor_state
    on agent_run(protocol, executor_kind, run_status, created_at);

create table agent_run_attempt (
    id varchar(128) primary key,
    agent_run_id varchar(64) not null,
    attempt_no bigint not null,
    attempt_status varchar(32) not null,
    executor_kind varchar(32) not null,
    provider varchar(128),
    model_profile_id varchar(128),
    model_version varchar(128),
    graph_key varchar(128),
    graph_version varchar(128),
    checkpoint_schema_version varchar(128),
    checkpoint_id varchar(128),
    prompt_version varchar(128),
    output_schema_version varchar(128),
    policy_version varchar(128),
    guardrail_version varchar(128),
    request_hash varchar(64),
    result_hash varchar(64),
    result_json jsonb,
    input_tokens bigint,
    output_tokens bigint,
    total_tokens bigint,
    latency_ms bigint,
    error_code varchar(128),
    error_retryable boolean,
    public_output_emitted boolean not null default false,
    final_frame_observed boolean not null default false,
    last_sequence_no bigint not null default 0,
    last_heartbeat_at timestamptz,
    started_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    attempt_version bigint not null default 0,
    created_by varchar(128) not null,
    constraint fk_agent_run_attempt_run
        foreign key (agent_run_id) references agent_run(id) on delete cascade,
    constraint uq_agent_run_attempt_number
        unique (agent_run_id, attempt_no),
    constraint uq_agent_run_attempt_identity
        unique (id, agent_run_id),
    constraint ck_agent_run_attempt_number
        check (attempt_no >= 1),
    constraint ck_agent_run_attempt_status
        check (attempt_status in (
            'PENDING', 'RUNNING', 'RESULT_READY', 'COMPLETED',
            'FAILED', 'ABORTED', 'CANCELLED'
        )),
    constraint ck_agent_run_attempt_executor
        check (executor_kind in ('LEGACY_WORKER', 'TEMPORAL_ACTIVITY')),
    constraint ck_agent_run_attempt_hashes
        check (
            (request_hash is null or request_hash ~ '^[0-9a-f]{64}$')
            and (result_hash is null or result_hash ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_agent_run_attempt_usage
        check (
            (input_tokens is null or input_tokens >= 0)
            and (output_tokens is null or output_tokens >= 0)
            and (total_tokens is null or total_tokens >= 0)
            and (latency_ms is null or latency_ms >= 0)
        ),
    constraint ck_agent_run_attempt_progress
        check (last_sequence_no >= 0)
);

create index idx_agent_run_attempt_active
    on agent_run_attempt(agent_run_id, attempt_status, attempt_no desc);

insert into agent_run_attempt (
    id, agent_run_id, attempt_no, attempt_status, executor_kind,
    model_version, prompt_version, request_hash, result_json,
    total_tokens, latency_ms, error_code, error_retryable,
    public_output_emitted, final_frame_observed, last_sequence_no,
    last_heartbeat_at, started_at, completed_at, created_at, updated_at,
    created_by
)
select
    run.id,
    run.id,
    1,
    case
        when run.run_status = 'COMPLETED' then 'COMPLETED'
        when run.run_status = 'FAILED' then 'FAILED'
        when run.run_status = 'RUNNING' then 'RUNNING'
        else 'PENDING'
    end,
    'LEGACY_WORKER',
    run.model,
    run.prompt_version,
    case
        when run.stream_request_hash ~ '^[0-9a-f]{64}$' then run.stream_request_hash
        else null
    end,
    run.stream_result_json,
    run.token_usage,
    run.latency_ms,
    run.error_code,
    run.error_retryable,
    exists (
        select 1
        from agent_run_stream_event event
        where event.agent_run_id = run.id
          and event.event_type = 'visible_delta'
    ),
    exists (
        select 1
        from agent_run_stream_event event
        where event.agent_run_id = run.id
          and event.event_type = 'final'
    ),
    coalesce((
        select max(event.sequence_no)
        from agent_run_stream_event event
        where event.agent_run_id = run.id
    ), 0),
    run.updated_at,
    run.started_at,
    run.completed_at,
    run.created_at,
    run.updated_at,
    run.created_by
from agent_run run;

alter table agent_run
    add constraint fk_agent_run_result_ready_attempt_v2
        foreign key (result_ready_attempt_id, id)
        references agent_run_attempt(id, agent_run_id)
        deferrable initially deferred,
    add constraint fk_agent_run_committed_attempt_v2
        foreign key (committed_attempt_id, id)
        references agent_run_attempt(id, agent_run_id)
        deferrable initially deferred;

alter table agent_run_stream_event
    add column agent_run_attempt_id varchar(128),
    add column stream_protocol varchar(32) not null default 'agent_stream.v1',
    add column audience varchar(32),
    add column payload_hash varchar(64);

update agent_run_stream_event
set agent_run_attempt_id = agent_run_id,
    stream_protocol = 'agent_stream.v1';

alter table agent_run_stream_event
    add constraint fk_agent_run_stream_event_attempt_v2
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id)
        on delete cascade,
    add constraint ck_agent_run_stream_protocol_v2
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2')),
    add constraint ck_agent_run_stream_audience_v2
        check (audience is null or audience in ('USER', 'MERCHANT', 'PLATFORM_REVIEWER', 'SYSTEM')),
    add constraint ck_agent_run_stream_payload_hash_v2
        check (payload_hash is null or payload_hash ~ '^[0-9a-f]{64}$'),
    add constraint ck_agent_run_stream_v2_binding
        check (
            stream_protocol <> 'agent-stream.v2'
            or (agent_run_attempt_id is not null and payload_hash is not null)
        );

alter table agent_run_stream_event
    drop constraint ck_agent_run_stream_event_type;

alter table agent_run_stream_event
    add constraint ck_agent_run_stream_event_type
        check (event_type in (
            'start', 'attempt_started', 'visible_delta', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        ));

alter table agent_run_stream_event
    drop constraint uq_agent_run_stream_event_sequence;

-- Preserve the legacy object name and invariant while allowing each V2 attempt
-- to start its own sequence at zero.
create unique index uq_agent_run_stream_event_sequence
    on agent_run_stream_event(agent_run_id, sequence_no)
    where stream_protocol = 'agent_stream.v1';

create unique index uq_agent_run_stream_event_attempt_sequence_v2
    on agent_run_stream_event(agent_run_id, agent_run_attempt_id, sequence_no)
    where stream_protocol = 'agent-stream.v2';

create index idx_agent_run_stream_event_v2_replay
    on agent_run_stream_event(agent_run_id, agent_run_attempt_id, sequence_no);

-- A rolled-back V1 binary does not know the expanded columns. These triggers
-- fill deterministic legacy identity and keep its single attempt readable.
create or replace function apply_agent_run_v2_legacy_defaults()
returns trigger
language plpgsql
as $$
begin
    new.tenant_surrogate := coalesce(new.tenant_surrogate, 'legacy-default');
    new.protocol := coalesce(new.protocol, 'agent_stream.v1');
    new.logical_idempotency_key := coalesce(new.logical_idempotency_key, 'legacy:' || new.id);
    new.executor_kind := coalesce(new.executor_kind, 'LEGACY_WORKER');
    new.finalization_status := coalesce(new.finalization_status, 'UNCOMMITTED');
    new.room_epoch := coalesce(new.room_epoch, 0);
    new.process_revision := coalesce(new.process_revision, 0);
    new.fencing_token := coalesce(new.fencing_token, 0);
    new.attempt_limit := coalesce(new.attempt_limit, 1);
    if new.protocol = 'agent_stream.v1' and new.run_status = 'COMPLETED' then
        new.finalization_status := 'LEGACY_COMMITTED';
        new.result_ready_attempt_id := new.id;
        new.committed_attempt_id := new.id;
    end if;
    return new;
end;
$$;

create trigger trg_agent_run_v2_legacy_defaults
    before insert or update on agent_run
    for each row execute function apply_agent_run_v2_legacy_defaults();

create or replace function sync_agent_run_v1_attempt()
returns trigger
language plpgsql
as $$
declare
    affected_rows integer;
begin
    if new.protocol <> 'agent_stream.v1' then
        return new;
    end if;

    insert into agent_run_attempt (
        id, agent_run_id, attempt_no, attempt_status, executor_kind,
        model_version, prompt_version, request_hash, result_json,
        total_tokens, latency_ms, error_code, error_retryable,
        last_heartbeat_at, started_at, completed_at, created_at, updated_at,
        created_by
    ) values (
        new.id,
        new.id,
        1,
        case
            when new.run_status = 'COMPLETED' then 'COMPLETED'
            when new.run_status = 'FAILED' then 'FAILED'
            when new.run_status = 'RUNNING' then 'RUNNING'
            else 'PENDING'
        end,
        'LEGACY_WORKER',
        new.model,
        new.prompt_version,
        case
            when new.stream_request_hash ~ '^[0-9a-f]{64}$' then new.stream_request_hash
            else null
        end,
        new.stream_result_json,
        new.token_usage,
        new.latency_ms,
        new.error_code,
        new.error_retryable,
        new.updated_at,
        new.started_at,
        new.completed_at,
        new.created_at,
        new.updated_at,
        new.created_by
    )
    on conflict (id) do update
    set attempt_status = excluded.attempt_status,
        model_version = excluded.model_version,
        result_json = excluded.result_json,
        total_tokens = excluded.total_tokens,
        latency_ms = excluded.latency_ms,
        error_code = excluded.error_code,
        error_retryable = excluded.error_retryable,
        last_heartbeat_at = excluded.last_heartbeat_at,
        completed_at = excluded.completed_at,
        updated_at = excluded.updated_at
    where agent_run_attempt.agent_run_id = excluded.agent_run_id;

    get diagnostics affected_rows = row_count;
    if affected_rows <> 1 then
        raise exception 'legacy AgentRun attempt identity conflicts with another logical run';
    end if;

    return new;
end;
$$;

create trigger trg_agent_run_v1_attempt_insert
    after insert on agent_run
    for each row execute function sync_agent_run_v1_attempt();

create trigger trg_agent_run_v1_attempt_update
    after update of run_status, model, stream_result_json, token_usage,
        latency_ms, error_code, error_retryable, updated_at
    on agent_run
    for each row execute function sync_agent_run_v1_attempt();

create or replace function bind_agent_run_v1_stream_attempt()
returns trigger
language plpgsql
as $$
begin
    if new.stream_protocol = 'agent_stream.v1' and new.agent_run_attempt_id is null then
        new.agent_run_attempt_id := new.agent_run_id;
    end if;
    return new;
end;
$$;

create trigger trg_agent_run_stream_v1_attempt_binding
    before insert on agent_run_stream_event
    for each row execute function bind_agent_run_v1_stream_attempt();

-- Keep the reviewer-only demo purge explicit and leaf-first. The function is
-- already large and owned by V040, so amend only its two V2 insertion points.
do $migration$
declare
    purge_definition text;
begin
    select pg_get_functiondef(
        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
    ) into purge_definition;

    if position('delete from agent_run where case_id = p_case_id;' in purge_definition) = 0 then
        raise exception 'V041 could not locate the AgentRun demo purge step';
    end if;

    purge_definition := replace(
        purge_definition,
        'delete from agent_run where case_id = p_case_id;',
        'delete from agent_run_attempt
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run where case_id = p_case_id;'
    );
    purge_definition := replace(
        purge_definition,
        '''agent_runs'', (select count(*) from agent_run where case_id = p_case_id),',
        '''agent_runs'', (select count(*) from agent_run where case_id = p_case_id),
        ''agent_run_attempts'', (
            select count(*) from agent_run_attempt
            where agent_run_id in (
                select id from agent_run where case_id = p_case_id
            )
        ),'
    );
    execute purge_definition;
end;
$migration$;

comment on table agent_run_attempt
    is 'Ordered physical attempts for one logical AgentRun; allocation is serialized by locking agent_run.';
