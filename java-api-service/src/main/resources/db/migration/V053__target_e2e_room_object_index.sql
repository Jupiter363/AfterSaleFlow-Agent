-- Opaque object references for non-Intake target commands and proposal outputs.
-- Python only receives the external URN; the MinIO location is never derivable from it.
create table target_e2e_room_object_index (
    object_ref varchar(512) primary key,
    object_kind varchar(24) not null,
    activation_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    command_id varchar(128) not null,
    logical_run_id varchar(128) not null,
    attempt_id varchar(128) not null,
    artifact_id varchar(128) not null,
    schema_version varchar(128) not null,
    content_sha256 varchar(64) not null,
    size_bytes bigint not null,
    storage_bucket varchar(128) not null,
    storage_key varchar(512) not null,
    checkpoint_ns varchar(128),
    checkpoint_id varchar(128),
    cognitive_revision bigint,
    created_at timestamptz not null default current_timestamp,
    constraint uq_target_e2e_room_object_storage unique (storage_bucket, storage_key),
    constraint uq_target_e2e_room_proposal_identity unique (
        activation_id, tenant_surrogate, case_id, room_type, room_epoch,
        room_fencing_token, command_id, logical_run_id, attempt_id,
        artifact_id, schema_version, checkpoint_ns, checkpoint_id, cognitive_revision),
    constraint ck_target_e2e_room_object_index_shape check (
        object_kind in ('COMMAND_INPUT', 'MANIFEST_ASSET', 'PROPOSAL')
        and room_type in ('EVIDENCE', 'HEARING', 'REVIEW')
        and object_ref ~ '^urn:target-e2e:(object|proposal):'
        and content_sha256 ~ '^[0-9a-f]{64}$'
        and size_bytes between 1 and 524288
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and (object_kind = 'PROPOSAL') = (checkpoint_id is not null)
        and (object_kind = 'PROPOSAL') = (cognitive_revision is not null)
    )
);

create index ix_target_e2e_room_object_admitted_ref on target_e2e_room_object_index (
    activation_id, tenant_surrogate, case_id, room_type, room_epoch,
    room_fencing_token, command_id, object_ref);
create index ix_target_e2e_room_object_proposal_lookup on target_e2e_room_object_index (
    activation_id, tenant_surrogate, case_id, room_type, room_epoch,
    room_fencing_token, command_id, logical_run_id, attempt_id, artifact_id, schema_version);

create trigger trg_target_e2e_room_object_index_immutable
before update or delete on target_e2e_room_object_index
for each row execute function reject_target_e2e_append_only_mutation();
create trigger trg_target_e2e_room_object_index_no_truncate
before truncate on target_e2e_room_object_index
for each statement execute function reject_target_e2e_append_only_mutation();
revoke all on target_e2e_room_object_index from public;
