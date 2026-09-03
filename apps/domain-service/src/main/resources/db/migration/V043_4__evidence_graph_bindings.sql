-- Phase 5 Evidence Graph authority bindings (EXPAND_ONLY).
-- The tables contain immutable hashes/references and verified actual-load metadata only.
-- They store no object bytes, Graph checkpoints, model reasoning, or formal domain result.

create table case_evidence_graph_binding (
    binding_id varchar(128) primary key,
    schema_version varchar(128) not null,
    registration_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_epoch bigint not null,
    java_room_fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    manifest_id varchar(128) not null unique,
    manifest_hash varchar(64) not null unique,
    manifest_payload_uri varchar(1024) not null unique,
    manifest_payload_sha256 varchar(64) not null unique,
    manifest_payload_size_bytes bigint not null,
    synthetic_fixture_id varchar(128) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    state_schema_version varchar(128) not null,
    assessment_output_schema_version varchar(128) not null,
    terminal_output_schema_version varchar(128) not null,
    writer_mode varchar(16) not null,
    formal_sink_eligible boolean not null,
    created_at timestamptz not null,
    binding_hash varchar(64) not null unique,
    constraint ck_evidence_graph_binding_synthetic_only
        check (
            schema_version = 'evidence-graph-binding.v1'
            and tenant_surrogate like 'TENANT_P5_SYNTHETIC_%'
            and case_id like 'CASE_P5_SYNTHETIC_%'
            and room_epoch >= 0
            and java_room_fencing_token > 0
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and writer_mode = 'SHADOW'
            and not formal_sink_eligible
        ),
    constraint ck_evidence_graph_binding_hashes
        check (
            actor_scope_hash ~ '^[0-9a-f]{64}$'
            and manifest_hash ~ '^[0-9a-f]{64}$'
            and manifest_payload_sha256 ~ '^[0-9a-f]{64}$'
            and binding_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_graph_binding_payload
        check (
            manifest_payload_uri ~ '^(s3|minio)://'
            and right(manifest_payload_uri, 70)
                = '/' || manifest_payload_sha256 || '.json'
            and manifest_payload_size_bytes between 1 and 2097152
        ),
    constraint ck_evidence_graph_binding_versions
        check (
            graph_key = 'evidence.v2'
            and state_schema_version = 'evidence-graph-state.v2'
            and assessment_output_schema_version = 'evidence-item-assessment.v1'
            and terminal_output_schema_version = 'evidence-batch-proposal.v1'
            and assessment_output_schema_version <> terminal_output_schema_version
        )
);

create unique index uq_evidence_graph_binding_private_tuple
    on case_evidence_graph_binding (
        tenant_surrogate, case_id, room_epoch, java_room_fencing_token,
        actor_scope_hash, agent_session_id, graph_key, graph_version,
        checkpoint_schema_version
    );

create index idx_evidence_graph_binding_case_epoch
    on case_evidence_graph_binding (
        tenant_surrogate, case_id, room_epoch, java_room_fencing_token, created_at
    );

create table case_evidence_asset_load_receipt (
    receipt_id varchar(128) primary key,
    receipt_hash varchar(64) not null unique,
    capability_id varchar(128) not null unique,
    capability_hash varchar(64) not null unique,
    capability_nonce varchar(128) not null unique,
    graph_binding_id varchar(128) not null,
    manifest_id varchar(128) not null,
    manifest_hash varchar(64) not null,
    evidence_id varchar(128) not null,
    item_hash varchar(64) not null,
    object_ref varchar(512) not null,
    immutable_object_version varchar(128) not null,
    object_sha256 varchar(64) not null,
    content_type varchar(128) not null,
    byte_size bigint not null,
    java_room_fencing_token bigint not null,
    graph_lease_fencing_token bigint not null,
    load_status varchar(16) not null,
    loaded_modalities_json jsonb not null,
    loaded_at timestamptz not null,
    constraint fk_evidence_asset_load_binding
        foreign key (graph_binding_id)
        references case_evidence_graph_binding(binding_id),
    constraint ck_evidence_asset_load_hashes
        check (
            receipt_hash ~ '^[0-9a-f]{64}$'
            and capability_hash ~ '^[0-9a-f]{64}$'
            and manifest_hash ~ '^[0-9a-f]{64}$'
            and item_hash ~ '^[0-9a-f]{64}$'
            and object_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_evidence_asset_load_synthetic
        check (
            object_ref ~ '^urn:synthetic-evidence:'
            and immutable_object_version <> ''
            and byte_size between 1 and 10485760
            and java_room_fencing_token > 0
            and graph_lease_fencing_token > 0
            and java_room_fencing_token <> graph_lease_fencing_token
            and load_status = 'LOADED'
        ),
    constraint ck_evidence_asset_load_modalities
        check (
            jsonb_typeof(loaded_modalities_json) = 'array'
            and jsonb_array_length(loaded_modalities_json) between 1 and 4
            and octet_length(loaded_modalities_json::text) <= 256
        )
);

create unique index uq_evidence_asset_load_object_version
    on case_evidence_asset_load_receipt (
        graph_binding_id, evidence_id, item_hash, object_ref,
        immutable_object_version, object_sha256
    );

create index idx_evidence_asset_load_manifest
    on case_evidence_asset_load_receipt (
        manifest_id, manifest_hash, evidence_id, loaded_at
    );

create or replace function enforce_evidence_asset_load_receipt_scope()
returns trigger
language plpgsql
as $$
declare
    manifest_binding record;
begin
    select manifest_id, manifest_hash, java_room_fencing_token, writer_mode,
           formal_sink_eligible
      into manifest_binding
      from case_evidence_graph_binding
     where binding_id = new.graph_binding_id
     for key share;

    if not found
        or manifest_binding.manifest_id is distinct from new.manifest_id
        or manifest_binding.manifest_hash is distinct from new.manifest_hash
        or manifest_binding.java_room_fencing_token
            is distinct from new.java_room_fencing_token
        or manifest_binding.writer_mode is distinct from 'SHADOW'
        or manifest_binding.formal_sink_eligible
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence actual-load receipt is outside its signed manifest scope';
    end if;
    return new;
end;
$$;

create function reject_evidence_graph_binding_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '23514',
        message = 'Evidence Graph authority bindings are append-only';
end;
$$;

create constraint trigger trg_evidence_asset_load_scope
    after insert on case_evidence_asset_load_receipt
    deferrable initially immediate
    for each row execute function enforce_evidence_asset_load_receipt_scope();

create trigger trg_evidence_graph_binding_immutable
    before update or delete on case_evidence_graph_binding
    for each row execute function reject_evidence_graph_binding_mutation();

create trigger trg_evidence_graph_binding_no_truncate
    before truncate on case_evidence_graph_binding
    for each statement execute function reject_evidence_graph_binding_mutation();

create trigger trg_evidence_asset_load_immutable
    before update or delete on case_evidence_asset_load_receipt
    for each row execute function reject_evidence_graph_binding_mutation();

create trigger trg_evidence_asset_load_no_truncate
    before truncate on case_evidence_asset_load_receipt
    for each statement execute function reject_evidence_graph_binding_mutation();
