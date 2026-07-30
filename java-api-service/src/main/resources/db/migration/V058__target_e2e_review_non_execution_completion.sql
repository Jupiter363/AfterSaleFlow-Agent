-- Immutable Java disposition for a formal Review decision which authorizes no execution.
-- The canonical payload is the replay authority after the command and room transition commit.
create table target_e2e_review_non_execution_completion (
    receipt_id varchar(64) primary key,
    schema_version varchar(96) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    case_workflow_id varchar(128) not null,
    case_workflow_run_id varchar(128) not null,
    decision_type varchar(32) not null,
    decision_record_id varchar(64) not null,
    decision_record_hash varchar(64) not null,
    command_id varchar(128) not null,
    admission_id varchar(64) not null,
    activation_id varchar(64) not null,
    command_hash varchar(64) not null,
    command_envelope_hash varchar(64) not null,
    source_room_epoch bigint not null,
    source_fencing_token bigint not null,
    source_process_revision bigint not null,
    source_room_revision bigint not null,
    terminal_process_revision bigint not null,
    terminal_room_revision bigint not null,
    next_evidence_epoch_id varchar(64),
    next_evidence_room_id varchar(64),
    next_evidence_room_epoch bigint,
    next_evidence_fencing_token bigint,
    next_evidence_process_revision bigint,
    next_evidence_room_revision bigint,
    next_evidence_workflow_id varchar(128),
    next_evidence_deadline_at timestamptz,
    receipt_canonical_json text not null,
    receipt_sha256 varchar(64) not null,
    committed_at timestamptz not null,
    committed_by varchar(128) not null,
    constraint uq_target_review_non_execution_admission unique (admission_id),
    constraint uq_target_review_non_execution_command unique (activation_id, command_id),
    constraint fk_target_review_non_execution_admission
        foreign key (admission_id) references target_e2e_command_admission(admission_id),
    constraint ck_target_review_non_execution_shape check (
        schema_version = 'target-review-non-execution-disposition.v1'
        and decision_type in ('REJECT', 'REQUEST_MORE_EVIDENCE', 'ESCALATE_MANUAL')
        and decision_record_hash ~ '^[0-9a-f]{64}$'
        and command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and receipt_sha256 ~ '^[0-9a-f]{64}$'
        and source_room_epoch between 1 and 9007199254740991
        and source_fencing_token between 1 and 9007199254740991
        and source_process_revision between 0 and 9007199254740991
        and source_room_revision between 0 and 9007199254740991
        and terminal_process_revision = source_process_revision + 1
        and terminal_room_revision = source_room_revision + 1
        and octet_length(receipt_canonical_json) between 2 and 262144
        and receipt_canonical_json::jsonb is not null
        and (
            (decision_type = 'REQUEST_MORE_EVIDENCE'
             and next_evidence_epoch_id is not null
             and next_evidence_room_id is not null
             and next_evidence_room_epoch between 1 and 9007199254740991
             and next_evidence_fencing_token between 1 and 9007199254740991
             and next_evidence_process_revision = terminal_process_revision
             and next_evidence_room_revision = 0
             and next_evidence_workflow_id is not null
             and next_evidence_deadline_at > committed_at)
            or
            (decision_type in ('REJECT', 'ESCALATE_MANUAL')
             and next_evidence_epoch_id is null
             and next_evidence_room_id is null
             and next_evidence_room_epoch is null
             and next_evidence_fencing_token is null
             and next_evidence_process_revision is null
             and next_evidence_room_revision is null
             and next_evidence_workflow_id is null
             and next_evidence_deadline_at is null)
        )
    )
);

create index idx_target_review_non_execution_case
    on target_e2e_review_non_execution_completion(case_id, source_room_epoch);

create trigger trg_target_review_non_execution_immutable
before update or delete on target_e2e_review_non_execution_completion
for each row execute function reject_target_e2e_append_only_mutation();

create trigger trg_target_review_non_execution_no_truncate
before truncate on target_e2e_review_non_execution_completion
for each statement execute function reject_target_e2e_append_only_mutation();

revoke all on target_e2e_review_non_execution_completion from public;
