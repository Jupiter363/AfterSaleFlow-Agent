-- Atomic, replay-exact binding between one formal Hearing receipt and its public transcript facts.

alter table case_room_epoch
    add constraint uq_hearing_public_epoch_authority unique (
        id, tenant_surrogate, case_id, room_id, room_type,
        room_epoch, writer_mode, fencing_token
    );

alter table hearing_domain_receipt
    add constraint uq_hearing_public_receipt_authority unique (
        receipt_id, receipt_hash, tenant_surrogate, case_id, flow_instance_id,
        epoch_id, room_type, hearing_epoch, writer_mode, fencing_token,
        source_stage, source_stage_sequence, source_process_revision,
        source_room_revision, stage_code, stage_sequence,
        process_revision, room_revision, committed_at
    );

alter table room_message
    add constraint uq_hearing_public_message_authority unique (
        id, case_id, room_id, sequence_no, idempotency_key
    );

alter table case_timeline_event
    add constraint uq_hearing_public_event_authority unique (
        id, case_id, room_id, sequence_no, event_key
    );

create table hearing_public_transcript_binding (
    schema_version varchar(64) not null,
    id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_id varchar(64) not null,
    room_type varchar(32) not null,
    flow_instance_id varchar(64) not null,
    epoch_id varchar(64) not null,
    hearing_epoch bigint not null,
    writer_mode varchar(16) not null,
    fencing_token bigint not null,
    receipt_id varchar(64) not null,
    receipt_hash varchar(64) not null,
    source_stage varchar(64) not null,
    source_stage_sequence integer not null,
    source_process_revision bigint not null,
    source_room_revision bigint not null,
    result_stage varchar(64) not null,
    result_stage_sequence integer not null,
    process_revision bigint not null,
    room_revision bigint not null,
    ordinal integer not null,
    message_stage varchar(64) not null,
    message_stage_sequence integer not null,
    publication_key varchar(128) not null,
    message_id varchar(64) not null,
    message_sequence_no bigint not null,
    message_sha256 varchar(64) not null,
    event_id varchar(64) not null,
    event_sequence_no bigint not null,
    event_key varchar(128) not null,
    event_sha256 varchar(64) not null,
    committed_at timestamptz not null,
    binding_sha256 varchar(64) not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_public_epoch_authority
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_id, room_type,
            hearing_epoch, writer_mode, fencing_token
        ) references case_room_epoch(
            id, tenant_surrogate, case_id, room_id, room_type,
            room_epoch, writer_mode, fencing_token
        ),
    constraint fk_hearing_public_receipt_authority
        foreign key (
            receipt_id, receipt_hash, tenant_surrogate, case_id, flow_instance_id,
            epoch_id, room_type, hearing_epoch, writer_mode, fencing_token,
            source_stage, source_stage_sequence, source_process_revision,
            source_room_revision, result_stage, result_stage_sequence,
            process_revision, room_revision, committed_at
        ) references hearing_domain_receipt(
            receipt_id, receipt_hash, tenant_surrogate, case_id, flow_instance_id,
            epoch_id, room_type, hearing_epoch, writer_mode, fencing_token,
            source_stage, source_stage_sequence, source_process_revision,
            source_room_revision, stage_code, stage_sequence,
            process_revision, room_revision, committed_at
        ),
    constraint fk_hearing_public_message_authority
        foreign key (
            message_id, case_id, room_id, message_sequence_no, publication_key
        ) references room_message(
            id, case_id, room_id, sequence_no, idempotency_key
        ),
    constraint fk_hearing_public_event_authority
        foreign key (
            event_id, case_id, room_id, event_sequence_no, event_key
        ) references case_timeline_event(
            id, case_id, room_id, sequence_no, event_key
        ),
    constraint uq_hearing_public_receipt_ordinal unique (receipt_id, ordinal),
    constraint uq_hearing_public_receipt_publication unique (receipt_id, publication_key),
    constraint uq_hearing_public_case_publication unique (case_id, publication_key),
    constraint uq_hearing_public_message unique (message_id),
    constraint uq_hearing_public_event unique (event_id),
    constraint ck_hearing_public_schema
        check (schema_version = 'hearing-public-transcript-binding.v1'),
    constraint ck_hearing_public_coordinates
        check (
            room_type = 'HEARING'
            and writer_mode = 'TEMPORAL'
            and hearing_epoch >= 0
            and fencing_token > 0
            and source_process_revision >= 0
            and source_room_revision >= 0
            and process_revision = source_process_revision + 1
            and room_revision = source_room_revision + 1
        ),
    constraint ck_hearing_public_stages
        check (
            hearing_flow_stage_sequence_v2(source_stage) = source_stage_sequence
            and hearing_flow_stage_sequence_v2(result_stage) = result_stage_sequence
            and result_stage_sequence in (source_stage_sequence, source_stage_sequence + 1)
            and hearing_flow_stage_sequence_v2(message_stage) = message_stage_sequence
            and message_stage in (source_stage, result_stage)
        ),
    constraint ck_hearing_public_order
        check (
            ordinal between 0 and 31
            and message_sequence_no > 0
            and event_sequence_no > 0
            and publication_key like
                ('hearing-v2:' || message_stage_sequence::varchar || ':%')
        ),
    constraint ck_hearing_public_identity
        check (
            publication_key ~ '^hearing-v2:[1-9][0-9]*:[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
            and message_sha256 ~ '^[0-9a-f]{64}$'
            and event_sha256 ~ '^[0-9a-f]{64}$'
            and binding_sha256 ~ '^[0-9a-f]{64}$'
            and created_by = 'hearing-flow-v2'
        )
);

create index idx_hearing_public_receipt
    on hearing_public_transcript_binding(receipt_id, ordinal);
create index idx_hearing_public_case_room
    on hearing_public_transcript_binding(case_id, room_id, message_sequence_no);

create trigger trg_hearing_public_transcript_binding_append_only
    before update or delete or truncate on hearing_public_transcript_binding
    for each statement
    execute function reject_append_only_mutation();
