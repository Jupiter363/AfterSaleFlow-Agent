alter table evidence_item
    drop constraint if exists uq_evidence_item_hash_source;

create unique index if not exists uq_evidence_item_hash_source_active
    on evidence_item(case_id, file_hash, source_type)
    where deleted_at is null;
