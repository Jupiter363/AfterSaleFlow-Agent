-- Durable, Java-owned parser delivery for admitted text/plain and text/markdown evidence.
-- A logical outbox row is created with the upload transaction; the stored-object consumer later
-- produces one immutable authority row in the same transaction that marks the item parsed.

alter table evidence_item
    add constraint uq_evidence_item_id_case unique (id, case_id);

create table evidence_parse_outbox (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    evidence_id varchar(64) not null,
    file_sha256 varchar(64) not null,
    content_type varchar(128) not null,
    file_size bigint not null,
    parser_version varchar(128) not null,
    source_bucket varchar(128) not null,
    source_object_key varchar(512) not null,
    request_key varchar(128) not null,
    outbox_status varchar(32) not null,
    available_at timestamptz not null,
    attempt_count integer not null default 0,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    last_error_code varchar(64),
    last_error_detail text,
    applied_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint fk_evidence_parse_outbox_item
        foreign key (evidence_id, case_id) references evidence_item(id, case_id),
    constraint uq_evidence_parse_outbox_logical
        unique (evidence_id, file_sha256, parser_version),
    constraint uq_evidence_parse_outbox_request_key unique (request_key),
    constraint uq_evidence_parse_outbox_coordinate
        unique (
            id, case_id, evidence_id, file_sha256, content_type, file_size,
            parser_version, source_bucket, source_object_key
        ),
    constraint ck_evidence_parse_outbox_source
        check (
            file_sha256 ~ '^[0-9a-f]{64}$'
            and request_key ~ '^[0-9a-f]{64}$'
            and content_type in ('text/plain', 'text/markdown')
            and file_size between 1 and 26214400
            and parser_version = 'java-stored-utf8.v1'
            and length(source_bucket) > 0
            and length(source_object_key) > 0
        ),
    constraint ck_evidence_parse_outbox_state
        check (
            outbox_status in ('PENDING', 'IN_FLIGHT', 'APPLIED', 'FAILED')
            and attempt_count >= 0
            and (
                (outbox_status = 'IN_FLIGHT'
                    and lease_owner is not null
                    and lease_expires_at is not null
                    and applied_at is null)
                or (outbox_status = 'PENDING'
                    and lease_owner is null
                    and lease_expires_at is null
                    and applied_at is null)
                or (outbox_status = 'FAILED'
                    and lease_owner is null
                    and lease_expires_at is null
                    and applied_at is null)
                or (outbox_status = 'APPLIED'
                    and lease_owner is null
                    and lease_expires_at is null
                    and applied_at is not null)
            )
        )
);

create index idx_evidence_parse_outbox_claimable
    on evidence_parse_outbox(outbox_status, available_at, lease_expires_at, id);

create table evidence_content_authority (
    id varchar(64) primary key,
    parse_outbox_id varchar(64) not null,
    case_id varchar(64) not null,
    evidence_id varchar(64) not null,
    file_sha256 varchar(64) not null,
    content_type varchar(128) not null,
    file_size bigint not null,
    parser_version varchar(128) not null,
    source_bucket varchar(128) not null,
    source_object_key varchar(512) not null,
    parsed_content_sha256 varchar(64) not null,
    parsed_text text not null,
    parsed_byte_length bigint not null,
    completed_at timestamptz not null,
    status varchar(32) not null,
    created_at timestamptz not null,
    constraint fk_evidence_content_authority_outbox
        foreign key (
            parse_outbox_id, case_id, evidence_id, file_sha256, content_type, file_size,
            parser_version, source_bucket, source_object_key
        ) references evidence_parse_outbox(
            id, case_id, evidence_id, file_sha256, content_type, file_size,
            parser_version, source_bucket, source_object_key
        ),
    constraint uq_evidence_content_authority_outbox unique (parse_outbox_id),
    constraint uq_evidence_content_authority_logical
        unique (evidence_id, file_sha256, parser_version),
    constraint ck_evidence_content_authority_shape
        check (
            file_sha256 ~ '^[0-9a-f]{64}$'
            and parsed_content_sha256 ~ '^[0-9a-f]{64}$'
            and content_type in ('text/plain', 'text/markdown')
            and file_size between 1 and 26214400
            and parser_version = 'java-stored-utf8.v1'
            and parsed_byte_length between 1 and 1000000
            and octet_length(parsed_text) = parsed_byte_length
            and length(parsed_text) > 0
            and status = 'SUCCEEDED'
        )
);

create index idx_evidence_content_authority_lookup
    on evidence_content_authority(evidence_id, file_sha256, parser_version);

create trigger trg_evidence_content_authority_append_only
    before update or delete or truncate on evidence_content_authority
    for each statement
    execute function reject_append_only_mutation();
