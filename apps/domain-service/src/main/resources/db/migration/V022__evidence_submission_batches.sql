alter table evidence_item
    add column if not exists submission_status varchar(32) not null default 'SUBMITTED',
    add column if not exists submitted_at timestamptz,
    add column if not exists submission_batch_id varchar(64);

create index if not exists idx_evidence_item_submission
    on evidence_item(case_id, submitted_by_role, submitted_by_id, submission_status)
    where deleted_at is null;

create table if not exists evidence_submission_batch (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    actor_role varchar(32) not null,
    actor_id varchar(128) not null,
    evidence_ids_json jsonb not null,
    batch_note text,
    submit_status varchar(32) not null,
    room_message_id varchar(64),
    idempotency_key varchar(128) not null,
    submitted_at timestamptz not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint uq_evidence_submission_batch_idem unique(case_id, idempotency_key)
);

create index if not exists idx_evidence_submission_batch_case_actor
    on evidence_submission_batch(case_id, actor_role, actor_id, submitted_at);
