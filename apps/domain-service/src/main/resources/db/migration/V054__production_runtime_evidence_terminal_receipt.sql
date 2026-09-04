create table production_runtime_evidence_terminal_receipt (
    receipt_id varchar(128) primary key,
    receipt_hash varchar(64) not null unique,
    request_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    initiator_completion_id varchar(128) not null,
    respondent_completion_id varchar(128) not null,
    dossier_id varchar(64) not null,
    dossier_version integer not null,
    hearing_room_id varchar(64) not null,
    hearing_deadline_at timestamptz not null,
    process_revision bigint not null,
    room_revision bigint not null,
    receipt_canonical_bytes bytea not null,
    committed_at timestamptz not null,
    constraint uq_production_runtime_evidence_terminal_epoch unique (case_id, room_epoch),
    constraint ck_production_runtime_evidence_terminal_hashes check (
        receipt_hash ~ '^[0-9a-f]{64}$' and request_hash ~ '^[0-9a-f]{64}$'
        and fencing_token > 0 and process_revision >= 0 and room_revision >= 0
        and octet_length(receipt_canonical_bytes) between 2 and 65536
    )
);

create trigger trg_production_runtime_evidence_terminal_receipt_immutable
before update or delete on production_runtime_evidence_terminal_receipt
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_evidence_terminal_receipt_no_truncate
before truncate on production_runtime_evidence_terminal_receipt
for each statement execute function reject_production_runtime_append_only_mutation();
revoke all on production_runtime_evidence_terminal_receipt from public;
