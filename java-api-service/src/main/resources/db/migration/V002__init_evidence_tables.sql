create table evidence_dossier (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    dossier_status varchar(32) not null,
    dossier_version integer not null default 1,
    summary_json jsonb not null default '{}'::jsonb,
    timeline_json jsonb not null default '[]'::jsonb,
    matrix_summary_json jsonb not null default '{}'::jsonb,
    built_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_evidence_dossier_case
        foreign key (case_id) references fulfillment_case(id),
    constraint uq_evidence_dossier_case unique (case_id)
);

create index idx_evidence_dossier_status on evidence_dossier(dossier_status);

create table evidence_item (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    dossier_id varchar(64) not null,
    evidence_type varchar(64) not null,
    source_type varchar(64) not null,
    submitted_by_role varchar(32) not null,
    submitted_by_id varchar(128) not null,
    file_bucket varchar(128),
    file_object_key varchar(512),
    file_hash varchar(128),
    original_filename varchar(512),
    content_type varchar(128),
    file_size bigint,
    parsed_text text,
    parse_status varchar(32) not null,
    visibility varchar(32) not null,
    desensitized boolean not null default false,
    metadata_json jsonb not null default '{}'::jsonb,
    extraction_json jsonb not null default '{}'::jsonb,
    occurred_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_evidence_item_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_evidence_item_dossier
        foreign key (dossier_id) references evidence_dossier(id),
    constraint uq_evidence_item_hash_source
        unique (case_id, file_hash, source_type),
    constraint ck_evidence_item_file_size
        check (file_size is null or file_size >= 0)
);

create index idx_evidence_item_case_id on evidence_item(case_id);
create index idx_evidence_item_dossier_id on evidence_item(dossier_id);
create index idx_evidence_item_parse_status on evidence_item(parse_status);

create table claim_issue_evidence_matrix (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    claim_id varchar(64) not null,
    issue_id varchar(64) not null,
    evidence_id varchar(64),
    relation_type varchar(32) not null,
    support_strength numeric(5,4),
    support_json jsonb not null default '{}'::jsonb,
    conflict_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_matrix_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_matrix_claim
        foreign key (claim_id) references party_claim(id),
    constraint fk_matrix_issue
        foreign key (issue_id) references issue(id),
    constraint fk_matrix_evidence
        foreign key (evidence_id) references evidence_item(id),
    constraint uq_matrix_relation
        unique (claim_id, issue_id, evidence_id, relation_type),
    constraint ck_matrix_strength
        check (support_strength is null or (support_strength >= 0 and support_strength <= 1))
);

create index idx_matrix_case_id on claim_issue_evidence_matrix(case_id);
create index idx_matrix_issue_id on claim_issue_evidence_matrix(issue_id);

create table evidence_request (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    issue_id varchar(64),
    requested_from_role varchar(32) not null,
    requested_from_id varchar(128) not null,
    request_status varchar(32) not null,
    requested_items_json jsonb not null default '[]'::jsonb,
    rationale_json jsonb not null default '{}'::jsonb,
    due_at timestamptz,
    responded_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_evidence_request_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_evidence_request_issue
        foreign key (issue_id) references issue(id)
);

create index idx_evidence_request_case_status on evidence_request(case_id, request_status);
create index idx_evidence_request_due_at on evidence_request(due_at);

create table party_submission (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    evidence_request_id varchar(64),
    submitted_by_role varchar(32) not null,
    submitted_by_id varchar(128) not null,
    submission_type varchar(64) not null,
    submission_text text,
    submission_json jsonb not null default '{}'::jsonb,
    attachment_ids_json jsonb not null default '[]'::jsonb,
    accepted_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_party_submission_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_party_submission_request
        foreign key (evidence_request_id) references evidence_request(id)
);

create index idx_party_submission_case_id on party_submission(case_id);
create index idx_party_submission_request_id on party_submission(evidence_request_id);
