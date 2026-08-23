-- The shared room coordinate contract is zero-based. Review is the source room for Outcome,
-- so its first durable epoch is 0 just like the first Intake, Evidence, and Hearing epochs.

alter table target_e2e_outcome_completion_fact
    drop constraint ck_target_e2e_outcome_completion_shape;

alter table target_e2e_outcome_completion_fact
    add constraint ck_target_e2e_outcome_completion_shape check (
      outcome_epoch >= 0 and fencing_token >= 1 and revision >= 1
      and committed_event_sequence >= 1
      and human_receipt_hash ~ '^[0-9a-f]{64}$'
      and payload_hash ~ '^[0-9a-f]{64}$'
      and fact_kind in (
        'OPERATION_COMMAND',
        'OPERATION_RECEIPT',
        'CLOSURE_RECEIPT',
        'EVALUATION_RECEIPT'
      )
    );

alter table target_e2e_review_non_execution_completion
    drop constraint ck_target_review_non_execution_shape;

alter table target_e2e_review_non_execution_completion
    add constraint ck_target_review_non_execution_shape check (
        schema_version = 'target-review-non-execution-disposition.v1'
        and decision_type in ('REJECT', 'REQUEST_MORE_EVIDENCE', 'ESCALATE_MANUAL')
        and decision_record_hash ~ '^[0-9a-f]{64}$'
        and command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and receipt_sha256 ~ '^[0-9a-f]{64}$'
        and source_room_epoch between 0 and 9007199254740991
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
    );
