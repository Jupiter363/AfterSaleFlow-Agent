-- Durable lifecycle and public replay events for model streaming.  The existing
-- agent_run table remains the system-of-record for both legacy completed runs
-- and the new asynchronous streaming runs.

alter table agent_run
    add column stream_operation varchar(64),
    add column room_id varchar(64),
    add column stream_endpoint varchar(256),
    add column stream_request_json jsonb not null default '{}'::jsonb,
    add column stream_request_hash varchar(64),
    add column stream_result_json jsonb,
    add column stream_audience_json jsonb not null default '[]'::jsonb,
    add column stream_audience_actor_ids_json jsonb not null default '[]'::jsonb,
    add column stream_idempotency_key varchar(128),
    add column stream_request_id varchar(128),
    add column error_code varchar(128),
    add column error_message text,
    add column error_retryable boolean,
    add column updated_at timestamptz not null default now();

create unique index uq_agent_run_stream_idempotency
    on agent_run(case_id, stream_idempotency_key)
    where stream_idempotency_key is not null;

create index idx_agent_run_stream_recovery
    on agent_run(run_status, created_at)
    where stream_operation is not null;

create table agent_run_stream_event (
    id varchar(64) primary key,
    agent_run_id varchar(64) not null,
    sequence_no bigint not null,
    event_type varchar(32) not null,
    payload_json jsonb not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_agent_run_stream_event_run
        foreign key (agent_run_id) references agent_run(id) on delete cascade,
    constraint uq_agent_run_stream_event_sequence
        unique (agent_run_id, sequence_no),
    constraint ck_agent_run_stream_event_type
        check (event_type in ('start', 'visible_delta', 'usage', 'final', 'error')),
    constraint ck_agent_run_stream_event_sequence
        check (sequence_no >= 0)
);

create index idx_agent_run_stream_event_replay
    on agent_run_stream_event(agent_run_id, sequence_no);
