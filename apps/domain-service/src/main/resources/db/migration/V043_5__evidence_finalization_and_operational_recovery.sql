-- Phase 5 Wave B additive Evidence receipt and recovery authority.
-- This migration creates no runtime registration, formal sink, allocation, or traffic switch.

create sequence case_evidence_finalization_fencing_token_seq
    as bigint
    minvalue 1000000001
    start with 1000000001
    increment by 1
    no cycle;

create table case_evidence_current_authority_snapshot (
    authority_snapshot_hash varchar(64) primary key,
    graph_binding_id varchar(128) not null,
    runtime_mode varchar(32) not null,
    agent_profile_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_id varchar(128) not null,
    room_epoch bigint not null,
    java_room_fencing_token bigint not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    participant_id varchar(128) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    source_revision bigint not null,
    process_revision bigint not null,
    room_revision bigint not null,
    current_fact_ids_json jsonb not null,
    current_source_refs_json jsonb not null,
    is_current boolean not null,
    recorded_at timestamptz not null,
    constraint fk_evidence_authority_graph_binding
        foreign key (graph_binding_id)
        references case_evidence_graph_binding(binding_id),
    constraint ck_evidence_authority_synthetic_only
        check (
            runtime_mode = 'SIGNED_SYNTHETIC_SHADOW'
            and tenant_surrogate like 'TENANT_P5_SYNTHETIC_%'
            and case_id like 'CASE_P5_SYNTHETIC_%'
            and room_epoch >= 0
            and java_room_fencing_token > 0
            and source_revision > 0
            and process_revision >= 0
            and room_revision >= 0
        ),
    constraint ck_evidence_authority_hashes
        check (
            authority_snapshot_hash ~ '^[0-9a-f]{64}$'
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_authority_fact_refs
        check (
            jsonb_typeof(current_fact_ids_json) = 'array'
            and jsonb_array_length(current_fact_ids_json) between 0 and 512
            and octet_length(current_fact_ids_json::text) <= 65536
        ),
    constraint ck_evidence_authority_source_refs
        check (
            jsonb_typeof(current_source_refs_json) = 'array'
            and jsonb_array_length(current_source_refs_json) between 0 and 512
            and octet_length(current_source_refs_json::text) <= 65536
        )
);

create unique index uq_evidence_current_authority_scope
    on case_evidence_current_authority_snapshot (
        tenant_surrogate, case_id, room_id, room_epoch, java_room_fencing_token
    )
    where is_current;

create unique index uq_evidence_one_current_authority
    on case_evidence_current_authority_snapshot (tenant_surrogate, case_id, room_id)
    where is_current;

create table case_evidence_finalization_receipt (
    receipt_id varchar(128) primary key,
    receipt_hash varchar(64) not null unique,
    schema_version varchar(128) not null,
    operation_type varchar(32) not null,
    operation_key varchar(512) not null,
    request_hash varchar(64) not null,
    result_hash varchar(64) not null,
    commit_scope varchar(64) not null,
    status varchar(16) not null,
    formal_domain_write boolean not null,
    formal_sink_eligible boolean not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_id varchar(128) not null,
    graph_binding_id varchar(128) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    source_revision bigint not null,
    process_revision bigint not null,
    room_revision bigint not null,
    operation_binding_json jsonb not null,
    merge_count integer not null,
    domain_event_ids_json jsonb not null,
    outbox_ids_json jsonb not null,
    hearing_opened boolean not null,
    committed_at timestamptz not null,
    committed_at_epoch_second bigint not null,
    committed_at_nano integer not null,
    authority_snapshot_hash varchar(64) not null,
    constraint fk_evidence_finalization_authority
        foreign key (authority_snapshot_hash)
        references case_evidence_current_authority_snapshot(authority_snapshot_hash),
    constraint fk_evidence_finalization_graph_binding
        foreign key (graph_binding_id)
        references case_evidence_graph_binding(binding_id),
    constraint uq_evidence_finalization_semantic_operation
        unique (tenant_surrogate, operation_key),
    constraint ck_evidence_finalization_synthetic_only
        check (
            schema_version = 'evidence-finalization-receipt.v1'
            and operation_type = 'BATCH_MERGE'
            and commit_scope = 'ISOLATED_SYNTHETIC_LEDGER'
            and status = 'COMMITTED'
            and not formal_domain_write
            and not formal_sink_eligible
            and tenant_surrogate like 'TENANT_P5_SYNTHETIC_%'
            and case_id like 'CASE_P5_SYNTHETIC_%'
            and room_id <> ''
            and room_epoch >= 0
            and fencing_token > 0
            and source_revision > 0
            and process_revision >= 0
            and room_revision >= 0
            and merge_count = 0
            and not hearing_opened
        ),
    constraint ck_evidence_finalization_hashes
        check (
            receipt_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and result_hash ~ '^[0-9a-f]{64}$'
            and authority_snapshot_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_finalization_operation_binding
        check (
            operation_key like 'evidence.batch.merge:%'
            and jsonb_typeof(operation_binding_json) = 'object'
            and operation_binding_json ?& array[
                'manifest_hash', 'dossier_target_version', 'proposal_hash',
                'logical_run_id', 'command_id', 'attempt_id', 'thread_id'
            ]
            and octet_length(operation_binding_json::text) <= 4096
        ),
    constraint ck_evidence_finalization_no_effects
        check (
            domain_event_ids_json = '[]'::jsonb
            and outbox_ids_json = '[]'::jsonb
        ),
    constraint ck_evidence_finalization_exact_committed_at
        check (
            committed_at_nano between 0 and 999999999
            and committed_at_epoch_second
                = floor(extract(epoch from committed_at))::bigint
            and committed_at_nano / 1000
                = mod(extract(microseconds from committed_at)::bigint, 1000000)
        )
);

create index idx_evidence_finalization_case_epoch
    on case_evidence_finalization_receipt (
        tenant_surrogate, case_id, room_epoch, fencing_token, committed_at
    );

create table case_evidence_finalization_receipt_load_binding (
    receipt_id varchar(128) not null,
    receipt_hash varchar(64) not null,
    load_receipt_id varchar(128) not null,
    load_receipt_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_epoch bigint not null,
    evidence_id varchar(128) not null,
    item_hash varchar(64) not null,
    manifest_hash varchar(64) not null,
    java_room_fencing_token bigint not null,
    graph_lease_fencing_token bigint not null,
    bound_at timestamptz not null,
    primary key (receipt_id, load_receipt_id),
    constraint fk_evidence_finalization_load_receipt_id
        foreign key (receipt_id)
        references case_evidence_finalization_receipt(receipt_id),
    constraint fk_evidence_finalization_load_receipt_hash
        foreign key (receipt_hash)
        references case_evidence_finalization_receipt(receipt_hash),
    constraint fk_evidence_finalization_actual_load_id
        foreign key (load_receipt_id)
        references case_evidence_asset_load_receipt(receipt_id),
    constraint fk_evidence_finalization_actual_load_hash
        foreign key (load_receipt_hash)
        references case_evidence_asset_load_receipt(receipt_hash),
    constraint ck_evidence_finalization_load_hashes
        check (
            receipt_hash ~ '^[0-9a-f]{64}$'
            and load_receipt_hash ~ '^[0-9a-f]{64}$'
            and item_hash ~ '^[0-9a-f]{64}$'
            and manifest_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_finalization_load_fences
        check (
            room_epoch >= 0
            and java_room_fencing_token > 0
            and graph_lease_fencing_token > 0
            and java_room_fencing_token <> graph_lease_fencing_token
        )
);

create unique index uq_evidence_finalization_one_load_use
    on case_evidence_finalization_receipt_load_binding (load_receipt_id);

create table case_evidence_terminal_summary (
    receipt_id varchar(128) primary key,
    receipt_hash varchar(64) not null unique,
    schema_version varchar(128) not null,
    summary_hash varchar(64) not null unique,
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_epoch bigint not null,
    java_room_fencing_token bigint not null,
    graph_lease_fencing_token bigint not null,
    java_finalization_fencing_token bigint not null unique,
    source_revision bigint not null,
    process_revision bigint not null,
    room_revision bigint not null,
    authority_snapshot_hash varchar(64) not null,
    graph_thread_id varchar(64) not null,
    manifest_hash varchar(64) not null,
    proposal_hash varchar(64) not null,
    result_hash varchar(64) not null,
    current_fact_ids_json jsonb not null,
    current_source_refs_json jsonb not null,
    committed_at timestamptz not null,
    committed_at_epoch_second bigint not null,
    committed_at_nano integer not null,
    constraint fk_evidence_terminal_receipt_id
        foreign key (receipt_id)
        references case_evidence_finalization_receipt(receipt_id),
    constraint fk_evidence_terminal_receipt_hash
        foreign key (receipt_hash)
        references case_evidence_finalization_receipt(receipt_hash),
    constraint fk_evidence_terminal_authority
        foreign key (authority_snapshot_hash)
        references case_evidence_current_authority_snapshot(authority_snapshot_hash),
    constraint ck_evidence_terminal_synthetic_only
        check (
            schema_version = 'evidence-terminal-summary.v1'
            and tenant_surrogate like 'TENANT_P5_SYNTHETIC_%'
            and case_id like 'CASE_P5_SYNTHETIC_%'
            and room_epoch >= 0
            and source_revision > 0
            and process_revision >= 0
            and room_revision >= 0
        ),
    constraint ck_evidence_terminal_hashes
        check (
            summary_hash ~ '^[0-9a-f]{64}$'
            and receipt_hash ~ '^[0-9a-f]{64}$'
            and authority_snapshot_hash ~ '^[0-9a-f]{64}$'
            and manifest_hash ~ '^[0-9a-f]{64}$'
            and proposal_hash ~ '^[0-9a-f]{64}$'
            and result_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_terminal_graph_thread
        check (graph_thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'),
    constraint ck_evidence_terminal_distinct_fences
        check (
            java_room_fencing_token > 0
            and graph_lease_fencing_token > 0
            and java_finalization_fencing_token > 0
            and java_room_fencing_token <> graph_lease_fencing_token
            and java_room_fencing_token <> java_finalization_fencing_token
            and graph_lease_fencing_token <> java_finalization_fencing_token
        ),
    constraint ck_evidence_terminal_fact_refs
        check (
            jsonb_typeof(current_fact_ids_json) = 'array'
            and jsonb_array_length(current_fact_ids_json) between 0 and 512
            and octet_length(current_fact_ids_json::text) <= 65536
        ),
    constraint ck_evidence_terminal_source_refs
        check (
            jsonb_typeof(current_source_refs_json) = 'array'
            and jsonb_array_length(current_source_refs_json) between 0 and 512
            and octet_length(current_source_refs_json::text) <= 65536
        ),
    constraint ck_evidence_terminal_exact_committed_at
        check (
            committed_at_nano between 0 and 999999999
            and committed_at_epoch_second
                = floor(extract(epoch from committed_at))::bigint
            and committed_at_nano / 1000
                = mod(extract(microseconds from committed_at)::bigint, 1000000)
        )
);

create index idx_evidence_terminal_case_epoch
    on case_evidence_terminal_summary (
        tenant_surrogate, case_id, room_epoch, java_room_fencing_token, committed_at
    );

create table case_evidence_operational_recovery (
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_id varchar(128) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    java_room_fencing_token bigint not null,
    source_revision bigint not null,
    process_revision bigint not null,
    room_revision bigint not null,
    runtime_mode varchar(32) not null,
    java_signed_synthetic boolean not null,
    formal_sink_eligible boolean not null,
    temporal_evidence_allocation boolean not null,
    authority_snapshot_hash varchar(64) not null,
    graph_binding_id varchar(128) not null,
    is_current boolean not null,
    updated_at timestamptz not null,
    primary key (
        tenant_surrogate, case_id, room_id, room_epoch, java_room_fencing_token
    ),
    constraint fk_evidence_recovery_authority
        foreign key (authority_snapshot_hash)
        references case_evidence_current_authority_snapshot(authority_snapshot_hash),
    constraint fk_evidence_recovery_graph_binding
        foreign key (graph_binding_id)
        references case_evidence_graph_binding(binding_id),
    constraint ck_evidence_recovery_engineering_only
        check (
            room_type = 'EVIDENCE'
            and runtime_mode in ('DISABLED', 'SIGNED_SYNTHETIC_SHADOW')
            and tenant_surrogate like 'TENANT_P5_SYNTHETIC_%'
            and case_id like 'CASE_P5_SYNTHETIC_%'
            and room_epoch >= 0
            and java_room_fencing_token > 0
            and source_revision > 0
            and process_revision >= 0
            and room_revision >= 0
            and not formal_sink_eligible
            and not temporal_evidence_allocation
            and (runtime_mode = 'DISABLED' or java_signed_synthetic)
            and authority_snapshot_hash ~ '^[0-9a-f]{64}$'
        )
);

create unique index uq_evidence_operational_recovery_current
    on case_evidence_operational_recovery (tenant_surrogate, case_id)
    where is_current;

create or replace function enforce_evidence_finalization_load_binding()
returns trigger
language plpgsql
as $$
declare
    finalization record;
    actual_load record;
    graph_binding record;
begin
    select finalization_receipt.tenant_surrogate, finalization_receipt.case_id,
           finalization_receipt.room_epoch, finalization_receipt.fencing_token,
           finalization_receipt.receipt_hash,
           finalization_receipt.operation_binding_json ->> 'manifest_hash' as manifest_hash,
           finalization_receipt.graph_binding_id,
           authority.graph_binding_id as authority_graph_binding_id
      into finalization
      from case_evidence_finalization_receipt finalization_receipt
      join case_evidence_current_authority_snapshot authority
        on authority.authority_snapshot_hash
            = finalization_receipt.authority_snapshot_hash
     where finalization_receipt.receipt_id = new.receipt_id
     for key share of finalization_receipt, authority;

    select graph_binding_id, receipt_hash, evidence_id, item_hash, manifest_hash,
           java_room_fencing_token, graph_lease_fencing_token
      into actual_load
      from case_evidence_asset_load_receipt
     where receipt_id = new.load_receipt_id
     for key share;

    select tenant_surrogate, case_id, room_epoch
      into graph_binding
      from case_evidence_graph_binding
     where binding_id = actual_load.graph_binding_id
     for key share;

    if not found
        or finalization.receipt_hash is distinct from new.receipt_hash
        or actual_load.receipt_hash is distinct from new.load_receipt_hash
        or finalization.tenant_surrogate is distinct from new.tenant_surrogate
        or finalization.case_id is distinct from new.case_id
        or finalization.room_epoch is distinct from new.room_epoch
        or finalization.fencing_token is distinct from new.java_room_fencing_token
        or finalization.manifest_hash is distinct from new.manifest_hash
        or actual_load.evidence_id is distinct from new.evidence_id
        or actual_load.item_hash is distinct from new.item_hash
        or actual_load.manifest_hash is distinct from new.manifest_hash
        or actual_load.java_room_fencing_token is distinct from new.java_room_fencing_token
        or actual_load.graph_lease_fencing_token is distinct from new.graph_lease_fencing_token
        or finalization.graph_binding_id is distinct from finalization.authority_graph_binding_id
        or actual_load.graph_binding_id is distinct from finalization.graph_binding_id
        or graph_binding.tenant_surrogate is distinct from new.tenant_surrogate
        or graph_binding.case_id is distinct from new.case_id
        or graph_binding.room_epoch is distinct from new.room_epoch
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence finalization load binding is outside receipt authority';
    end if;
    return new;
end;
$$;

create or replace function enforce_evidence_finalization_receipt_authority()
returns trigger
language plpgsql
as $$
declare
    authority record;
begin
    select authority_snapshot.tenant_surrogate, authority_snapshot.case_id,
           authority_snapshot.room_id, authority_snapshot.room_epoch,
           authority_snapshot.java_room_fencing_token,
           authority_snapshot.source_revision, authority_snapshot.process_revision,
           authority_snapshot.room_revision, authority_snapshot.graph_binding_id,
           graph_binding.thread_id, graph_binding.manifest_hash,
           graph_binding.writer_mode, graph_binding.formal_sink_eligible
      into authority
      from case_evidence_current_authority_snapshot authority_snapshot
      join case_evidence_graph_binding graph_binding
        on graph_binding.binding_id = authority_snapshot.graph_binding_id
     where authority_snapshot.authority_snapshot_hash = new.authority_snapshot_hash
     for key share of authority_snapshot, graph_binding;

    if not found
        or authority.tenant_surrogate is distinct from new.tenant_surrogate
        or authority.case_id is distinct from new.case_id
        or authority.room_id is distinct from new.room_id
        or authority.room_epoch is distinct from new.room_epoch
        or authority.java_room_fencing_token is distinct from new.fencing_token
        or authority.source_revision is distinct from new.source_revision
        or authority.process_revision is distinct from new.process_revision
        or authority.room_revision is distinct from new.room_revision
        or authority.graph_binding_id is distinct from new.graph_binding_id
        or authority.thread_id
            is distinct from new.operation_binding_json ->> 'thread_id'
        or authority.manifest_hash
            is distinct from new.operation_binding_json ->> 'manifest_hash'
        or authority.writer_mode is distinct from 'SHADOW'
        or authority.formal_sink_eligible
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence finalization receipt is outside current authority scope';
    end if;
    return new;
end;
$$;

create or replace function enforce_evidence_operational_recovery_authority()
returns trigger
language plpgsql
as $$
declare
    authority record;
begin
    select tenant_surrogate, case_id, room_id, room_epoch,
           java_room_fencing_token, source_revision, process_revision,
           room_revision, graph_binding_id, is_current, runtime_mode
      into authority
      from case_evidence_current_authority_snapshot
     where authority_snapshot_hash = new.authority_snapshot_hash
     for key share;

    if not found
        or authority.tenant_surrogate is distinct from new.tenant_surrogate
        or authority.case_id is distinct from new.case_id
        or authority.room_id is distinct from new.room_id
        or authority.room_epoch is distinct from new.room_epoch
        or authority.java_room_fencing_token
            is distinct from new.java_room_fencing_token
        or authority.source_revision is distinct from new.source_revision
        or authority.process_revision is distinct from new.process_revision
        or authority.room_revision is distinct from new.room_revision
        or authority.graph_binding_id is distinct from new.graph_binding_id
        or not authority.is_current
        or authority.runtime_mode is distinct from new.runtime_mode
        or new.runtime_mode is distinct from 'SIGNED_SYNTHETIC_SHADOW'
        or not new.java_signed_synthetic
        or new.formal_sink_eligible
        or new.temporal_evidence_allocation
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence operational recovery is outside current authority scope';
    end if;
    return new;
end;
$$;

create or replace function enforce_evidence_authority_snapshot_binding()
returns trigger
language plpgsql
as $$
declare
    graph_binding record;
    java_room record;
begin
    select tenant_surrogate, case_id, room_epoch, java_room_fencing_token,
           actor_scope_hash, agent_session_id, thread_id, manifest_hash,
           writer_mode, formal_sink_eligible
      into graph_binding
      from case_evidence_graph_binding
     where binding_id = new.graph_binding_id
     for key share;

    -- V043_4 intentionally has no Java room/revision columns. Bind the immutable
    -- Graph row to the independently authoritative Java room epoch before it can
    -- authorize a receipt or sidecar.
    select tenant_surrogate, case_id, room_id, room_type, room_epoch,
           process_revision, room_revision, fencing_token, writer_mode
      into java_room
      from case_room_epoch
     where tenant_surrogate = new.tenant_surrogate
       and case_id = new.case_id
       and room_id = new.room_id
       and room_type = 'EVIDENCE'
       and room_epoch = new.room_epoch
       and process_revision = new.process_revision
       and room_revision = new.room_revision
       and fencing_token = new.java_room_fencing_token
      for key share;

    if not found
        or graph_binding.tenant_surrogate is distinct from new.tenant_surrogate
        or graph_binding.case_id is distinct from new.case_id
        or graph_binding.room_epoch is distinct from new.room_epoch
        or graph_binding.java_room_fencing_token
            is distinct from new.java_room_fencing_token
        or graph_binding.actor_scope_hash is distinct from new.actor_scope_hash
        or graph_binding.agent_session_id is distinct from new.agent_session_id
        or graph_binding.thread_id is null
        or graph_binding.manifest_hash is null
        or graph_binding.writer_mode is distinct from 'SHADOW'
        or graph_binding.formal_sink_eligible
        or java_room.tenant_surrogate is distinct from new.tenant_surrogate
        or java_room.case_id is distinct from new.case_id
        or java_room.room_id is distinct from new.room_id
        or java_room.room_type is distinct from 'EVIDENCE'
        or java_room.room_epoch is distinct from new.room_epoch
        or java_room.process_revision is distinct from new.process_revision
        or java_room.room_revision is distinct from new.room_revision
        or java_room.fencing_token is distinct from new.java_room_fencing_token
        or java_room.writer_mode is distinct from 'SHADOW'
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence authority snapshot is outside its Graph binding scope';
    end if;
    return new;
end;
$$;

create or replace function enforce_evidence_terminal_summary_binding()
returns trigger
language plpgsql
as $$
declare
    finalization record;
begin
    select receipt_hash, tenant_surrogate, case_id, room_epoch, fencing_token,
           source_revision, process_revision, room_revision, result_hash,
           authority_snapshot_hash, operation_binding_json, committed_at,
           committed_at_epoch_second, committed_at_nano
      into finalization
      from case_evidence_finalization_receipt
     where receipt_id = new.receipt_id
     for key share;

    if not found
        or finalization.receipt_hash is distinct from new.receipt_hash
        or finalization.tenant_surrogate is distinct from new.tenant_surrogate
        or finalization.case_id is distinct from new.case_id
        or finalization.room_epoch is distinct from new.room_epoch
        or finalization.fencing_token is distinct from new.java_room_fencing_token
        or finalization.source_revision is distinct from new.source_revision
        or finalization.process_revision is distinct from new.process_revision
        or finalization.room_revision is distinct from new.room_revision
        or finalization.result_hash is distinct from new.result_hash
        or finalization.authority_snapshot_hash is distinct from new.authority_snapshot_hash
        or finalization.operation_binding_json ->> 'manifest_hash'
            is distinct from new.manifest_hash
        or finalization.operation_binding_json ->> 'proposal_hash'
            is distinct from new.proposal_hash
        or finalization.operation_binding_json ->> 'thread_id'
            is distinct from new.graph_thread_id
        or finalization.committed_at is distinct from new.committed_at
        or finalization.committed_at_epoch_second
            is distinct from new.committed_at_epoch_second
        or finalization.committed_at_nano is distinct from new.committed_at_nano
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence terminal summary is outside committed receipt authority';
    end if;
    return new;
end;
$$;

create or replace function restrict_evidence_authority_snapshot_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception using
            errcode = '23514',
            message = 'Evidence authority snapshots cannot be deleted';
    end if;
    if not old.is_current
        or new.is_current
        or (to_jsonb(new) - 'is_current') is distinct from (to_jsonb(old) - 'is_current')
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence authority snapshots are immutable except current retirement';
    end if;
    return new;
end;
$$;

create or replace function reject_evidence_finalization_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '23514',
        message = 'Evidence finalization receipts and sidecars are append-only';
end;
$$;

create constraint trigger trg_evidence_finalization_load_scope
    after insert on case_evidence_finalization_receipt_load_binding
    deferrable initially immediate
    for each row execute function enforce_evidence_finalization_load_binding();

create constraint trigger trg_evidence_finalization_receipt_authority
    after insert on case_evidence_finalization_receipt
    deferrable initially immediate
    for each row execute function enforce_evidence_finalization_receipt_authority();

create constraint trigger trg_evidence_authority_snapshot_scope
    after insert on case_evidence_current_authority_snapshot
    deferrable initially immediate
    for each row execute function enforce_evidence_authority_snapshot_binding();

create constraint trigger trg_evidence_terminal_summary_scope
    after insert on case_evidence_terminal_summary
    deferrable initially immediate
    for each row execute function enforce_evidence_terminal_summary_binding();

create constraint trigger trg_evidence_operational_recovery_authority
    after insert or update on case_evidence_operational_recovery
    deferrable initially immediate
    for each row execute function enforce_evidence_operational_recovery_authority();

create trigger trg_evidence_authority_snapshot_restrict
    before update or delete on case_evidence_current_authority_snapshot
    for each row execute function restrict_evidence_authority_snapshot_mutation();

create trigger trg_evidence_authority_snapshot_no_truncate
    before truncate on case_evidence_current_authority_snapshot
    for each statement execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_finalization_receipt_immutable
    before update or delete on case_evidence_finalization_receipt
    for each row execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_finalization_receipt_no_truncate
    before truncate on case_evidence_finalization_receipt
    for each statement execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_finalization_load_immutable
    before update or delete on case_evidence_finalization_receipt_load_binding
    for each row execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_finalization_load_no_truncate
    before truncate on case_evidence_finalization_receipt_load_binding
    for each statement execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_terminal_summary_immutable
    before update or delete on case_evidence_terminal_summary
    for each row execute function reject_evidence_finalization_mutation();

create trigger trg_evidence_terminal_summary_no_truncate
    before truncate on case_evidence_terminal_summary
    for each statement execute function reject_evidence_finalization_mutation();
