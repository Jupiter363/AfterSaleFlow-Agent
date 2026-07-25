-- Additive stream-delivery storage for bounded migration and retention work.
-- The existing agent_run_stream_event table remains the default reader/writer store.
-- These rows describe delivery only; they never grant formal business-completion authority.

create table agent_run_stream_event_identity (
    event_id varchar(64) primary key,
    stream_protocol varchar(32) not null default 'agent_stream.v1',
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    sequence_no bigint not null,
    canonical_payload_sha256 varchar(64) not null,
    recorded_at timestamptz not null default clock_timestamp(),
    registered_by varchar(128) not null,
    constraint fk_stream_event_identity_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id),
    constraint uq_stream_event_identity_sequence
        unique (
            stream_protocol, agent_run_id, agent_run_attempt_id, sequence_no
        ),
    constraint uq_stream_event_identity_hwm
        unique (
            event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
            sequence_no, recorded_at
        ),
    constraint uq_stream_event_identity_exact
        unique (
            event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
            sequence_no, recorded_at, canonical_payload_sha256
        ),
    constraint ck_stream_event_identity_protocol
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2')),
    constraint ck_stream_event_identity_sequence
        check (sequence_no >= 0),
    constraint ck_stream_event_identity_hash
        check (canonical_payload_sha256 ~ '^[0-9a-f]{64}$')
);

create function stamp_stream_event_identity_recorded_at()
returns trigger
language plpgsql
as $$
begin
    -- Callers may supply the source event time separately, but never the
    -- partition-routing timestamp used by the delivery authority.
    new.recorded_at := clock_timestamp();
    return new;
end;
$$;

create trigger trg_stream_event_identity_recorded_at
    before insert on agent_run_stream_event_identity
    for each row execute function stamp_stream_event_identity_recorded_at();

create table agent_run_stream_event_delivery (
    event_id varchar(64) not null,
    stream_protocol varchar(32) not null default 'agent_stream.v1',
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    sequence_no bigint not null,
    event_type varchar(32) not null,
    payload_json jsonb not null,
    canonical_payload_sha256 varchar(64) not null,
    audience varchar(32),
    actor_id varchar(128),
    audience_actor_ids_json jsonb not null default '[]'::jsonb,
    source_event_created_at timestamptz not null,
    recorded_at timestamptz not null,
    source_store varchar(64) not null default 'agent_run_stream_event',
    recorded_by varchar(128) not null,
    primary key (event_id, recorded_at),
    constraint fk_stream_event_delivery_identity
        foreign key (
            event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
            sequence_no, recorded_at, canonical_payload_sha256
        ) references agent_run_stream_event_identity(
            event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
            sequence_no, recorded_at, canonical_payload_sha256
        ),
    constraint ck_stream_event_delivery_protocol
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2')),
    constraint ck_stream_event_delivery_sequence
        check (sequence_no >= 0),
    constraint ck_stream_event_delivery_type
        check (event_type in (
            'start', 'attempt_started', 'visible_delta', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        )),
    constraint ck_stream_event_delivery_hash
        check (canonical_payload_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_stream_event_delivery_audience
        check (
            audience is null
            or audience in ('USER', 'MERCHANT', 'PLATFORM_REVIEWER', 'SYSTEM')
        ),
    constraint ck_stream_event_delivery_actor_ids
        check (
            jsonb_typeof(audience_actor_ids_json) = 'array'
            and jsonb_array_length(audience_actor_ids_json) <= 128
            and octet_length(audience_actor_ids_json::text) <= 32768
        ),
    constraint ck_stream_event_delivery_source
        check (source_store in ('agent_run_stream_event', 'DUAL_WRITE'))
) partition by range (recorded_at);

-- Partition sizes are selected from measured release evidence. The default partition
-- makes the expand safe before those release-only partitions are provisioned.
create table agent_run_stream_event_delivery_default
    partition of agent_run_stream_event_delivery default;

create index idx_stream_event_delivery_replay
    on agent_run_stream_event_delivery(
        stream_protocol, agent_run_id, agent_run_attempt_id, sequence_no
    );

create index idx_stream_event_delivery_recorded_at
    on agent_run_stream_event_delivery(recorded_at);

create table agent_run_stream_delivery_high_watermark (
    stream_protocol varchar(32) not null default 'agent_stream.v1',
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    highest_contiguous_sequence_no bigint not null default -1,
    highest_event_id varchar(64),
    highest_event_recorded_at timestamptz,
    updated_at timestamptz not null default clock_timestamp(),
    watermark_version bigint not null default 0,
    primary key (stream_protocol, agent_run_id, agent_run_attempt_id),
    constraint fk_stream_delivery_hwm_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id),
    constraint fk_stream_delivery_hwm_event
        foreign key (
            highest_event_id, stream_protocol, agent_run_id,
            agent_run_attempt_id, highest_contiguous_sequence_no,
            highest_event_recorded_at
        ) references agent_run_stream_event_identity(
            event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
            sequence_no, recorded_at
        ),
    constraint ck_stream_delivery_hwm_protocol
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2')),
    constraint ck_stream_delivery_hwm_value
        check (highest_contiguous_sequence_no >= -1),
    constraint ck_stream_delivery_hwm_event_binding
        check (
            (
                highest_contiguous_sequence_no = -1
                and highest_event_id is null
                and highest_event_recorded_at is null
            )
            or (
                highest_contiguous_sequence_no >= 0
                and highest_event_id is not null
                and highest_event_recorded_at is not null
            )
        ),
    constraint ck_stream_delivery_hwm_version
        check (watermark_version >= 0)
);

create function enforce_stream_delivery_high_watermark()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' then
        if new.highest_contiguous_sequence_no <> -1
           or new.highest_event_id is not null
           or new.highest_event_recorded_at is not null
           or new.watermark_version <> 0 then
            raise exception using errcode = '23514',
                message = 'delivery high-watermark must start before sequence zero';
        end if;
        new.updated_at := clock_timestamp();
        return new;
    end if;

    if (new.stream_protocol, new.agent_run_id, new.agent_run_attempt_id)
       is distinct from
       (old.stream_protocol, old.agent_run_id, old.agent_run_attempt_id) then
        raise exception using errcode = '23514',
            message = 'delivery high-watermark identity is immutable';
    end if;
    if new.highest_contiguous_sequence_no < old.highest_contiguous_sequence_no then
        raise exception using errcode = '23514',
            message = 'delivery high-watermark cannot regress';
    end if;
    if new.highest_contiguous_sequence_no = old.highest_contiguous_sequence_no then
        if (new.highest_event_id, new.highest_event_recorded_at)
           is distinct from
           (old.highest_event_id, old.highest_event_recorded_at) then
            raise exception using errcode = '23514',
                message = 'delivery high-watermark event binding is immutable';
        end if;
        new.watermark_version := old.watermark_version;
        new.updated_at := old.updated_at;
        return new;
    end if;

    if exists (
        select 1
          from generate_series(
              old.highest_contiguous_sequence_no + 1,
              new.highest_contiguous_sequence_no
          ) as expected(sequence_no)
         where not exists (
             select 1
               from agent_run_stream_event_delivery event
              where event.stream_protocol = new.stream_protocol
                and event.agent_run_id = new.agent_run_id
                and event.agent_run_attempt_id = new.agent_run_attempt_id
                and event.sequence_no = expected.sequence_no
         )
    ) then
        raise exception using errcode = '23514',
            message = 'delivery high-watermark cannot advance across a gap';
    end if;

    new.watermark_version := old.watermark_version + 1;
    new.updated_at := clock_timestamp();
    return new;
end;
$$;

create trigger trg_stream_delivery_high_watermark_guard
    before insert or update on agent_run_stream_delivery_high_watermark
    for each row execute function enforce_stream_delivery_high_watermark();

create trigger trg_stream_delivery_high_watermark_no_delete
    before delete or truncate on agent_run_stream_delivery_high_watermark
    for each statement execute function reject_append_only_mutation();

-- One call claims/verifies the global identity, writes the partition target, and
-- advances only the highest contiguous sequence. PostgreSQL supplies recorded_at.
create function record_agent_run_stream_delivery(
    p_event_id varchar,
    p_stream_protocol varchar,
    p_agent_run_id varchar,
    p_agent_run_attempt_id varchar,
    p_sequence_no bigint,
    p_event_type varchar,
    p_payload_json jsonb,
    p_canonical_payload_sha256 varchar,
    p_audience varchar,
    p_actor_id varchar,
    p_audience_actor_ids_json jsonb,
    p_source_event_created_at timestamptz,
    p_source_store varchar,
    p_recorded_by varchar
)
returns table (
    was_inserted boolean,
    authoritative_recorded_at timestamptz,
    highest_contiguous_sequence_no bigint
)
language plpgsql
as $$
declare
    claimed agent_run_stream_event_identity%rowtype;
    inserted_count integer;
    previous_high_watermark bigint;
    next_high_watermark bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'agent-run-stream-delivery:' || p_stream_protocol || ':' ||
        p_agent_run_id || ':' || p_agent_run_attempt_id,
        0
    ));

    insert into agent_run_stream_event_identity (
        event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
        sequence_no, canonical_payload_sha256, registered_by
    ) values (
        p_event_id, p_stream_protocol, p_agent_run_id, p_agent_run_attempt_id,
        p_sequence_no, p_canonical_payload_sha256, p_recorded_by
    )
    on conflict (event_id) do nothing;
    get diagnostics inserted_count = row_count;

    select registry.*
      into claimed
      from agent_run_stream_event_identity registry
     where registry.event_id = p_event_id
     for update;

    if not found
       or (
           claimed.stream_protocol, claimed.agent_run_id,
           claimed.agent_run_attempt_id, claimed.sequence_no,
           claimed.canonical_payload_sha256
       ) is distinct from (
           p_stream_protocol, p_agent_run_id, p_agent_run_attempt_id,
           p_sequence_no, p_canonical_payload_sha256
       ) then
        raise exception using errcode = '23514',
            message = 'stream event identity or canonical payload hash conflicts';
    end if;

    insert into agent_run_stream_event_delivery (
        event_id, stream_protocol, agent_run_id, agent_run_attempt_id,
        sequence_no, event_type, payload_json, canonical_payload_sha256,
        audience, actor_id, audience_actor_ids_json,
        source_event_created_at, recorded_at, source_store, recorded_by
    ) values (
        claimed.event_id, claimed.stream_protocol, claimed.agent_run_id,
        claimed.agent_run_attempt_id, claimed.sequence_no, p_event_type,
        p_payload_json, claimed.canonical_payload_sha256, p_audience, p_actor_id,
        coalesce(p_audience_actor_ids_json, '[]'::jsonb),
        coalesce(p_source_event_created_at, claimed.recorded_at),
        claimed.recorded_at, coalesce(p_source_store, 'agent_run_stream_event'),
        p_recorded_by
    )
    on conflict (event_id, recorded_at) do nothing;

    if not exists (
        select 1
          from agent_run_stream_event_delivery event
         where event.event_id = claimed.event_id
           and event.recorded_at = claimed.recorded_at
           and (
               event.stream_protocol, event.agent_run_id,
               event.agent_run_attempt_id, event.sequence_no, event.event_type,
               event.payload_json, event.canonical_payload_sha256,
               event.audience, event.actor_id, event.audience_actor_ids_json,
               event.source_event_created_at, event.source_store
           ) is not distinct from (
               claimed.stream_protocol, claimed.agent_run_id,
               claimed.agent_run_attempt_id, claimed.sequence_no, p_event_type,
               p_payload_json, claimed.canonical_payload_sha256,
               p_audience, p_actor_id,
               coalesce(p_audience_actor_ids_json, '[]'::jsonb),
               coalesce(p_source_event_created_at, claimed.recorded_at),
               coalesce(p_source_store, 'agent_run_stream_event')
           )
    ) then
        raise exception using errcode = '23514',
            message = 'partitioned stream delivery row conflicts with immutable identity';
    end if;

    insert into agent_run_stream_delivery_high_watermark (
        stream_protocol, agent_run_id, agent_run_attempt_id
    ) values (
        claimed.stream_protocol, claimed.agent_run_id, claimed.agent_run_attempt_id
    )
    on conflict (stream_protocol, agent_run_id, agent_run_attempt_id) do nothing;

    select watermark.highest_contiguous_sequence_no
      into previous_high_watermark
      from agent_run_stream_delivery_high_watermark watermark
     where watermark.stream_protocol = claimed.stream_protocol
       and watermark.agent_run_id = claimed.agent_run_id
       and watermark.agent_run_attempt_id = claimed.agent_run_attempt_id
     for update;

    with recursive contiguous(sequence_no) as (
        select previous_high_watermark + 1
         where exists (
             select 1
               from agent_run_stream_event_delivery event
              where event.stream_protocol = claimed.stream_protocol
                and event.agent_run_id = claimed.agent_run_id
                and event.agent_run_attempt_id = claimed.agent_run_attempt_id
                and event.sequence_no = previous_high_watermark + 1
         )
        union all
        select contiguous.sequence_no + 1
          from contiguous
         where exists (
             select 1
               from agent_run_stream_event_delivery event
              where event.stream_protocol = claimed.stream_protocol
                and event.agent_run_id = claimed.agent_run_id
                and event.agent_run_attempt_id = claimed.agent_run_attempt_id
                and event.sequence_no = contiguous.sequence_no + 1
         )
    )
    select coalesce(max(contiguous.sequence_no), previous_high_watermark)
      into next_high_watermark
      from contiguous;

    if next_high_watermark > previous_high_watermark then
        update agent_run_stream_delivery_high_watermark watermark
           set highest_contiguous_sequence_no = next_high_watermark,
               highest_event_id = terminal.event_id,
               highest_event_recorded_at = terminal.recorded_at
          from agent_run_stream_event_identity terminal
         where watermark.stream_protocol = claimed.stream_protocol
           and watermark.agent_run_id = claimed.agent_run_id
           and watermark.agent_run_attempt_id = claimed.agent_run_attempt_id
           and terminal.stream_protocol = watermark.stream_protocol
           and terminal.agent_run_id = watermark.agent_run_id
           and terminal.agent_run_attempt_id = watermark.agent_run_attempt_id
           and terminal.sequence_no = next_high_watermark;
    end if;

    return query
    select inserted_count = 1, claimed.recorded_at, next_high_watermark;
end;
$$;

create table agent_run_stream_backfill_cursor (
    backfill_id varchar(64) primary key,
    schema_version varchar(64) not null default 'agent-stream-backfill-cursor.v1',
    source_store varchar(64) not null default 'agent_run_stream_event',
    source_upper_bound_created_at timestamptz not null,
    source_upper_bound_event_id varchar(64) not null,
    last_source_created_at timestamptz,
    last_source_event_id varchar(64),
    batch_limit integer not null default 500,
    cursor_status varchar(16) not null default 'PENDING',
    processed_count bigint not null default 0,
    conflict_count bigint not null default 0,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    created_by varchar(128) not null,
    constraint ck_stream_backfill_cursor_schema
        check (schema_version = 'agent-stream-backfill-cursor.v1'),
    constraint ck_stream_backfill_cursor_source
        check (source_store = 'agent_run_stream_event'),
    constraint ck_stream_backfill_cursor_pair
        check (
            (last_source_created_at is null and last_source_event_id is null)
            or (last_source_created_at is not null and last_source_event_id is not null)
        ),
    constraint ck_stream_backfill_cursor_bound
        check (
            last_source_created_at is null
            or (last_source_created_at, last_source_event_id)
                <= (source_upper_bound_created_at, source_upper_bound_event_id)
        ),
    constraint ck_stream_backfill_cursor_batch
        check (batch_limit between 1 and 1000),
    constraint ck_stream_backfill_cursor_status
        check (cursor_status in ('PENDING', 'RUNNING', 'FAILED', 'COMPLETE')),
    constraint ck_stream_backfill_cursor_counts
        check (processed_count >= 0 and conflict_count >= 0)
);

create function enforce_stream_backfill_cursor_progress()
returns trigger
language plpgsql
as $$
declare
    traversed_rows bigint;
begin
    if tg_op = 'INSERT' then
        if new.cursor_status <> 'PENDING'
           or new.last_source_created_at is not null
           or new.last_source_event_id is not null
           or new.processed_count <> 0
           or new.conflict_count <> 0 then
            raise exception using errcode = '23514',
                message = 'backfill cursor must start pending at the bounded snapshot origin';
        end if;
        new.updated_at := clock_timestamp();
        return new;
    end if;
    if (
        new.backfill_id, new.schema_version, new.source_store,
        new.source_upper_bound_created_at, new.source_upper_bound_event_id,
        new.batch_limit, new.created_at, new.created_by
    ) is distinct from (
        old.backfill_id, old.schema_version, old.source_store,
        old.source_upper_bound_created_at, old.source_upper_bound_event_id,
        old.batch_limit, old.created_at, old.created_by
    ) then
        raise exception using errcode = '23514',
            message = 'backfill cursor identity and bounded source snapshot are immutable';
    end if;
    if old.last_source_created_at is not null and (
        new.last_source_created_at is null
        or (new.last_source_created_at, new.last_source_event_id)
            < (old.last_source_created_at, old.last_source_event_id)
    ) then
        raise exception using errcode = '23514',
            message = 'backfill cursor cannot regress';
    end if;
    if new.processed_count < old.processed_count
       or new.conflict_count < old.conflict_count then
        raise exception using errcode = '23514',
            message = 'backfill cursor counters cannot regress';
    end if;
    if old.cursor_status = 'PENDING'
       and new.cursor_status not in ('PENDING', 'RUNNING', 'FAILED') then
        raise exception using errcode = '23514',
            message = 'backfill cursor has an invalid state transition';
    end if;
    if old.cursor_status = 'RUNNING'
       and new.cursor_status not in ('RUNNING', 'FAILED', 'COMPLETE') then
        raise exception using errcode = '23514',
            message = 'backfill cursor has an invalid state transition';
    end if;
    if old.cursor_status = 'FAILED'
       and new.cursor_status not in ('RUNNING', 'FAILED') then
        raise exception using errcode = '23514',
            message = 'backfill cursor has an invalid state transition';
    end if;
    if old.cursor_status = 'COMPLETE' and new.cursor_status <> 'COMPLETE' then
        raise exception using errcode = '23514',
            message = 'completed backfill cursor cannot reopen';
    end if;
    if old.cursor_status = 'COMPLETE'
       and (
           new.last_source_created_at, new.last_source_event_id,
           new.processed_count, new.conflict_count
       ) is distinct from (
           old.last_source_created_at, old.last_source_event_id,
           old.processed_count, old.conflict_count
       ) then
        raise exception using errcode = '23514',
            message = 'completed backfill cursor progress is immutable';
    end if;
    if (new.last_source_created_at, new.last_source_event_id)
       is distinct from
       (old.last_source_created_at, old.last_source_event_id) then
        select count(*)
          into traversed_rows
          from agent_run_stream_event source_event
         where (
                 old.last_source_created_at is null
                 or (source_event.created_at, source_event.id)
                    > (old.last_source_created_at, old.last_source_event_id)
               )
           and (source_event.created_at, source_event.id)
               <= (new.last_source_created_at, new.last_source_event_id)
           and (source_event.created_at, source_event.id)
               <= (
                   new.source_upper_bound_created_at,
                   new.source_upper_bound_event_id
               );
        if traversed_rows < 1
           or traversed_rows > new.batch_limit
           or new.processed_count - old.processed_count <> traversed_rows then
            raise exception using errcode = '23514',
                message = 'backfill cursor advance must match one bounded source batch';
        end if;
        if exists (
            select 1
              from agent_run_stream_event source_event
             where (
                     old.last_source_created_at is null
                     or (source_event.created_at, source_event.id)
                        > (old.last_source_created_at, old.last_source_event_id)
                   )
               and (source_event.created_at, source_event.id)
                   <= (new.last_source_created_at, new.last_source_event_id)
               and (source_event.created_at, source_event.id)
                   <= (
                       new.source_upper_bound_created_at,
                       new.source_upper_bound_event_id
                   )
               and not exists (
                   select 1
                     from agent_run_stream_event_identity registry
                     join agent_run_stream_event_delivery target
                       on target.event_id = registry.event_id
                      and target.recorded_at = registry.recorded_at
                    where registry.event_id = source_event.id
                      and registry.stream_protocol = source_event.stream_protocol
                      and registry.agent_run_id = source_event.agent_run_id
                      and registry.agent_run_attempt_id =
                          source_event.agent_run_attempt_id
                      and registry.sequence_no = source_event.sequence_no
                      and target.event_type = source_event.event_type
                      and target.payload_json = source_event.payload_json
                      and target.source_event_created_at = source_event.created_at
                      and target.audience is not distinct from source_event.audience
                      and (
                          source_event.payload_hash is null
                          or registry.canonical_payload_sha256 =
                              source_event.payload_hash
                      )
               )
        ) then
            raise exception using errcode = '23514',
                message = 'backfill cursor requires matching immutable target delivery rows';
        end if;
    elsif new.processed_count <> old.processed_count then
        raise exception using errcode = '23514',
            message = 'backfill processed count requires cursor progress';
    end if;
    if new.cursor_status = 'COMPLETE'
       and (
           (new.last_source_created_at, new.last_source_event_id)
               is distinct from (
                   new.source_upper_bound_created_at,
                   new.source_upper_bound_event_id
               )
           or new.conflict_count <> 0
       ) then
        raise exception using errcode = '23514',
            message = 'backfill completion requires the exact conflict-free source bound';
    end if;
    new.updated_at := clock_timestamp();
    return new;
end;
$$;

create trigger trg_stream_backfill_cursor_progress
    before insert or update on agent_run_stream_backfill_cursor
    for each row execute function enforce_stream_backfill_cursor_progress();

create trigger trg_stream_backfill_cursor_no_delete
    before delete or truncate on agent_run_stream_backfill_cursor
    for each statement execute function reject_append_only_mutation();

create table agent_run_stream_archive_manifest (
    manifest_id varchar(64) primary key,
    schema_version varchar(64) not null default 'agent-stream-archive-manifest.v1',
    manifest_sha256 varchar(64) not null,
    target_partition_name varchar(128) not null,
    partition_range_start timestamptz not null,
    partition_range_end timestamptz not null,
    stream_protocol varchar(32) not null,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    first_sequence_no bigint not null,
    last_sequence_no bigint not null,
    event_count bigint not null,
    canonical_events_sha256 varchar(64) not null,
    object_uri varchar(1024) not null,
    object_version varchar(256) not null,
    object_sha256 varchar(64) not null,
    terminal_event_id varchar(64),
    terminal_event_sha256 varchar(64),
    agent_execution_manifest_id varchar(64),
    agent_execution_manifest_sha256 varchar(64),
    authority_scope varchar(32) not null default 'DELIVERY_STORAGE_ONLY',
    formal_business_authority boolean not null default false,
    created_at timestamptz not null default clock_timestamp(),
    created_by varchar(128) not null,
    constraint uq_stream_archive_manifest_hash
        unique (manifest_id, manifest_sha256),
    constraint fk_stream_archive_manifest_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id),
    constraint ck_stream_archive_manifest_schema
        check (schema_version = 'agent-stream-archive-manifest.v1'),
    constraint ck_stream_archive_manifest_partition
        check (
            length(btrim(target_partition_name)) between 1 and 128
            and partition_range_end > partition_range_start
        ),
    constraint ck_stream_archive_manifest_protocol
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2')),
    constraint ck_stream_archive_manifest_sequence
        check (
            first_sequence_no >= 0
            and last_sequence_no >= first_sequence_no
            and event_count = last_sequence_no - first_sequence_no + 1
        ),
    constraint ck_stream_archive_manifest_hashes
        check (
            manifest_sha256 ~ '^[0-9a-f]{64}$'
            and canonical_events_sha256 ~ '^[0-9a-f]{64}$'
            and object_sha256 ~ '^[0-9a-f]{64}$'
            and (terminal_event_sha256 is null
                 or terminal_event_sha256 ~ '^[0-9a-f]{64}$')
            and (agent_execution_manifest_sha256 is null
                 or agent_execution_manifest_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_stream_archive_manifest_uri
        check (object_uri ~ '^(s3|minio|urn):'),
    constraint ck_stream_archive_manifest_terminal_pair
        check (
            (terminal_event_id is null and terminal_event_sha256 is null)
            or (terminal_event_id is not null and terminal_event_sha256 is not null)
        ),
    constraint ck_stream_archive_manifest_execution_pair
        check (
            (agent_execution_manifest_id is null
             and agent_execution_manifest_sha256 is null)
            or (agent_execution_manifest_id is not null
                and agent_execution_manifest_sha256 is not null)
        ),
    constraint ck_stream_archive_manifest_authority
        check (
            authority_scope = 'DELIVERY_STORAGE_ONLY'
            and not formal_business_authority
        )
);

create table agent_run_stream_archive_receipt (
    receipt_id varchar(64) primary key,
    schema_version varchar(64) not null default 'agent-stream-archive-receipt.v1',
    receipt_sha256 varchar(64) not null unique,
    manifest_id varchar(64) not null,
    manifest_sha256 varchar(64) not null,
    target_partition_name varchar(128) not null,
    stream_protocol varchar(32) not null,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    first_sequence_no bigint not null,
    last_sequence_no bigint not null,
    event_count bigint not null,
    canonical_events_sha256 varchar(64) not null,
    object_version varchar(256) not null,
    object_sha256 varchar(64) not null,
    object_readback_sha256 varchar(64) not null,
    sequence_identity_validation_json jsonb not null default '{}'::jsonb,
    audience_cursor_validation_json jsonb not null default '{}'::jsonb,
    delivery_high_watermark bigint not null,
    hot_retention_started_at timestamptz not null,
    hot_retention_eligible_at timestamptz not null,
    receipt_status varchar(16) not null,
    authority_scope varchar(32) not null default 'DELIVERY_STORAGE_ONLY',
    formal_business_authority boolean not null default false,
    verified_at timestamptz not null,
    verified_by varchar(128) not null,
    constraint fk_stream_archive_receipt_manifest
        foreign key (manifest_id, manifest_sha256)
        references agent_run_stream_archive_manifest(manifest_id, manifest_sha256),
    constraint ck_stream_archive_receipt_schema
        check (schema_version = 'agent-stream-archive-receipt.v1'),
    constraint ck_stream_archive_receipt_hashes
        check (
            receipt_sha256 ~ '^[0-9a-f]{64}$'
            and manifest_sha256 ~ '^[0-9a-f]{64}$'
            and canonical_events_sha256 ~ '^[0-9a-f]{64}$'
            and object_sha256 ~ '^[0-9a-f]{64}$'
            and object_readback_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_stream_archive_receipt_sequence
        check (
            first_sequence_no >= 0
            and last_sequence_no >= first_sequence_no
            and event_count = last_sequence_no - first_sequence_no + 1
            and delivery_high_watermark >= last_sequence_no
        ),
    constraint ck_stream_archive_receipt_retention
        check (hot_retention_eligible_at >= hot_retention_started_at + interval '24 hours'),
    constraint ck_stream_archive_receipt_status
        check (receipt_status in ('VERIFIED', 'FAILED')),
    constraint ck_stream_archive_receipt_readback
        check (
            receipt_status <> 'VERIFIED'
            or object_readback_sha256 = object_sha256
        ),
    constraint ck_stream_archive_receipt_documents
        check (
            jsonb_typeof(sequence_identity_validation_json) = 'object'
            and jsonb_typeof(audience_cursor_validation_json) = 'object'
            and octet_length(sequence_identity_validation_json::text) <= 65536
            and octet_length(audience_cursor_validation_json::text) <= 65536
        ),
    constraint ck_stream_archive_receipt_verified_evidence
        check (
            receipt_status <> 'VERIFIED'
            or (
                sequence_identity_validation_json @> '{
                    "schema_version": "agent-stream-sequence-identity-validation.v1",
                    "status": "PASS",
                    "sequence_contiguous": true,
                    "event_identity_exact": true
                }'::jsonb
                and audience_cursor_validation_json @> '{
                    "schema_version": "agent-stream-audience-cursor-validation.v1",
                    "status": "PASS",
                    "audience_parity": true,
                    "actor_id_parity": true,
                    "cursor_parity": true
                }'::jsonb
                and delivery_high_watermark >= 0
            )
        ),
    constraint ck_stream_archive_receipt_authority
        check (
            authority_scope = 'DELIVERY_STORAGE_ONLY'
            and not formal_business_authority
        )
);

create function enforce_stream_archive_receipt_binding()
returns trigger
language plpgsql
as $$
declare
    manifest agent_run_stream_archive_manifest%rowtype;
    current_high_watermark bigint;
    prior_receipt_high_watermark bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(
        'agent-run-stream-archive:' || new.stream_protocol || ':' ||
        new.agent_run_id || ':' || new.agent_run_attempt_id,
        0
    ));
    select value.* into manifest
      from agent_run_stream_archive_manifest value
     where value.manifest_id = new.manifest_id
       and value.manifest_sha256 = new.manifest_sha256
     for key share;
    if not found
       or (
           new.target_partition_name, new.stream_protocol, new.agent_run_id,
           new.agent_run_attempt_id, new.first_sequence_no,
           new.last_sequence_no, new.event_count, new.canonical_events_sha256,
           new.object_version, new.object_sha256
       ) is distinct from (
           manifest.target_partition_name, manifest.stream_protocol,
           manifest.agent_run_id, manifest.agent_run_attempt_id,
           manifest.first_sequence_no, manifest.last_sequence_no,
           manifest.event_count, manifest.canonical_events_sha256,
           manifest.object_version, manifest.object_sha256
       ) then
        raise exception using errcode = '23514',
            message = 'archive receipt does not match its immutable manifest';
    end if;

    select watermark.highest_contiguous_sequence_no
      into current_high_watermark
      from agent_run_stream_delivery_high_watermark watermark
     where watermark.stream_protocol = new.stream_protocol
       and watermark.agent_run_id = new.agent_run_id
       and watermark.agent_run_attempt_id = new.agent_run_attempt_id
     for key share;
    if not found or new.delivery_high_watermark > current_high_watermark then
        raise exception using errcode = '23514',
            message = 'archive receipt cannot lead the delivery high-watermark';
    end if;

    select coalesce(max(receipt.delivery_high_watermark), -1)
      into prior_receipt_high_watermark
      from agent_run_stream_archive_receipt receipt
     where receipt.stream_protocol = new.stream_protocol
       and receipt.agent_run_id = new.agent_run_id
       and receipt.agent_run_attempt_id = new.agent_run_attempt_id;
    if new.delivery_high_watermark < prior_receipt_high_watermark then
        raise exception using errcode = '23514',
            message = 'archive receipt delivery high-watermark cannot regress';
    end if;
    return new;
end;
$$;

create trigger trg_stream_archive_receipt_binding
    before insert on agent_run_stream_archive_receipt
    for each row execute function enforce_stream_archive_receipt_binding();

create table agent_run_stream_migration_receipt (
    receipt_id varchar(64) primary key,
    schema_version varchar(64) not null default 'agent-stream-migration-receipt.v1',
    candidate_sha varchar(64) not null,
    deployment_manifest_sha256 varchar(64) not null,
    migration_version varchar(16) not null default '046',
    step_id varchar(64) not null,
    attempt_id varchar(128) not null,
    operator_identity varchar(256) not null,
    authorization_reference varchar(1024) not null,
    started_at timestamptz not null,
    ended_at timestamptz not null,
    exit_status varchar(16) not null,
    source_event_count bigint,
    target_event_count bigint,
    source_canonical_sha256 varchar(64),
    target_canonical_sha256 varchar(64),
    sequence_identity_validation_json jsonb not null default '{}'::jsonb,
    audience_cursor_validation_json jsonb not null default '{}'::jsonb,
    delivery_high_watermark bigint,
    archive_manifest_version varchar(64),
    archive_manifest_sha256 varchar(64),
    rollback_target varchar(64),
    rollback_result varchar(32),
    acceptance_status varchar(32) not null default 'PENDING_EXTERNAL',
    receipt_sha256 varchar(64) not null unique,
    authority_scope varchar(32) not null default 'DELIVERY_STORAGE_ONLY',
    formal_business_authority boolean not null default false,
    created_at timestamptz not null default clock_timestamp(),
    constraint ck_stream_migration_receipt_schema
        check (schema_version = 'agent-stream-migration-receipt.v1'),
    constraint ck_stream_migration_receipt_hashes
        check (
            candidate_sha ~ '^[0-9a-f]{40,64}$'
            and deployment_manifest_sha256 ~ '^[0-9a-f]{64}$'
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and (source_canonical_sha256 is null
                 or source_canonical_sha256 ~ '^[0-9a-f]{64}$')
            and (target_canonical_sha256 is null
                 or target_canonical_sha256 ~ '^[0-9a-f]{64}$')
            and (archive_manifest_sha256 is null
                 or archive_manifest_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_stream_migration_receipt_version
        check (migration_version = '046'),
    constraint ck_stream_migration_receipt_step
        check (step_id in (
            'EXPAND', 'BACKFILL', 'CAPTURE_DUAL_WRITE', 'PARITY_VALIDATE'
        )),
    constraint ck_stream_migration_receipt_times
        check (ended_at >= started_at),
    constraint ck_stream_migration_receipt_exit
        check (exit_status in ('SUCCEEDED', 'FAILED', 'BLOCKED')),
    constraint ck_stream_migration_receipt_counts
        check (
            (source_event_count is null or source_event_count >= 0)
            and (target_event_count is null or target_event_count >= 0)
            and (delivery_high_watermark is null or delivery_high_watermark >= -1)
        ),
    constraint ck_stream_migration_receipt_documents
        check (
            jsonb_typeof(sequence_identity_validation_json) = 'object'
            and jsonb_typeof(audience_cursor_validation_json) = 'object'
            and octet_length(sequence_identity_validation_json::text) <= 65536
            and octet_length(audience_cursor_validation_json::text) <= 65536
        ),
    constraint ck_stream_migration_receipt_acceptance
        check (acceptance_status in ('PENDING_EXTERNAL', 'REJECTED')),
    constraint ck_stream_migration_receipt_success_parity
        check (
            exit_status <> 'SUCCEEDED'
            or step_id = 'EXPAND'
            or (
                source_event_count is not null
                and target_event_count is not null
                and target_event_count = source_event_count
                and source_canonical_sha256 is not null
                and target_canonical_sha256 is not null
                and target_canonical_sha256 = source_canonical_sha256
                and delivery_high_watermark is not null
                and delivery_high_watermark >= 0
                and sequence_identity_validation_json @> '{
                    "schema_version": "agent-stream-sequence-identity-validation.v1",
                    "status": "PASS",
                    "sequence_contiguous": true,
                    "event_identity_exact": true
                }'::jsonb
                and audience_cursor_validation_json @> '{
                    "schema_version": "agent-stream-audience-cursor-validation.v1",
                    "status": "PASS",
                    "audience_parity": true,
                    "actor_id_parity": true,
                    "cursor_parity": true
                }'::jsonb
            )
        ),
    constraint ck_stream_migration_receipt_authority
        check (
            authority_scope = 'DELIVERY_STORAGE_ONLY'
            and not formal_business_authority
        )
);

create trigger trg_stream_event_identity_append_only
    before update or truncate on agent_run_stream_event_identity
    for each statement execute function reject_append_only_mutation();

create trigger trg_stream_event_identity_delete_append_only
    before delete on agent_run_stream_event_identity
    for each row execute function reject_append_only_mutation();

create trigger trg_stream_event_delivery_append_only
    before update on agent_run_stream_event_delivery
    for each row execute function reject_append_only_mutation();

create trigger trg_stream_event_delivery_delete_append_only
    before delete on agent_run_stream_event_delivery
    for each row execute function reject_append_only_mutation();

create trigger trg_stream_event_delivery_truncate_append_only
    before truncate on agent_run_stream_event_delivery
    for each statement execute function reject_append_only_mutation();

-- TRUNCATE named directly against a child does not fire the parent's statement
-- trigger, so the only partition created by V046 receives its own guard.
create trigger trg_stream_event_delivery_default_truncate_append_only
    before truncate on agent_run_stream_event_delivery_default
    for each statement execute function reject_append_only_mutation();

create trigger trg_stream_archive_manifest_append_only
    before update or truncate on agent_run_stream_archive_manifest
    for each statement execute function reject_append_only_mutation();

create trigger trg_stream_archive_manifest_delete_append_only
    before delete on agent_run_stream_archive_manifest
    for each row execute function reject_append_only_mutation();

create trigger trg_stream_archive_receipt_append_only
    before update or truncate on agent_run_stream_archive_receipt
    for each statement execute function reject_append_only_mutation();

create trigger trg_stream_archive_receipt_delete_append_only
    before delete on agent_run_stream_archive_receipt
    for each row execute function reject_append_only_mutation();

create trigger trg_stream_migration_receipt_append_only
    before update or truncate on agent_run_stream_migration_receipt
    for each statement execute function reject_append_only_mutation();

create trigger trg_stream_migration_receipt_delete_append_only
    before delete on agent_run_stream_migration_receipt
    for each row execute function reject_append_only_mutation();
