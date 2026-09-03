-- Party terminal actions are owned by the stable case participant identity.
-- participant_role remains an immutable audit/display snapshot.
alter table hearing_flow_action
    add column participant_id varchar(128);

-- The V2 action ledger is append-only at runtime. Migration-time backfill is
-- limited to the new identity column and leaves payloads and hashes untouched.
alter table hearing_flow_action
    disable trigger trg_hearing_flow_action_append_only;

update hearing_flow_action action
set participant_id = case action.participant_role
    when 'USER' then dispute.user_id
    when 'MERCHANT' then dispute.merchant_id
end
from fulfillment_dispute_case dispute
where action.case_id = dispute.id
  and action.action_type in ('ANSWER_BUNDLE', 'EVIDENCE_BATCH');

alter table hearing_flow_action
    enable trigger trg_hearing_flow_action_append_only;

drop index if exists uq_hearing_flow_action_party;

create unique index uq_hearing_flow_action_party
    on hearing_flow_action(stage_id, action_type, participant_id)
    where participant_id is not null;

alter table hearing_flow_action
    drop constraint ck_hearing_flow_action_schema,
    add constraint ck_hearing_flow_action_schema
        check (
            (action_type = 'QUESTION_SET'
                and schema_version = 'hearing_question_set.v1')
            or
            (action_type = 'ANSWER_BUNDLE'
                and schema_version in (
                    'hearing_answer_bundle.v1',
                    'hearing_party_statement.v1'
                ))
            or
            (action_type = 'EVIDENCE_REQUEST_SET'
                and schema_version = 'hearing_evidence_request_set.v1')
            or
            (action_type = 'EVIDENCE_BATCH'
                and schema_version = 'hearing_evidence_batch.v1')
        ),
    drop constraint ck_hearing_flow_action_actor_shape,
    add constraint ck_hearing_flow_action_actor_shape
        check (
            (action_type in ('QUESTION_SET', 'EVIDENCE_REQUEST_SET')
                and participant_id is null
                and participant_role is null
                and submission_status is null
                and agent_run_id is not null)
            or
            (action_type in ('ANSWER_BUNDLE', 'EVIDENCE_BATCH')
                and participant_id is not null
                and length(trim(participant_id)) > 0
                and participant_role in ('USER', 'MERCHANT')
                and submission_status in ('SUBMITTED', 'AUTO_TIMEOUT')
                and agent_run_id is null)
        ),
    drop constraint ck_hearing_flow_action_party_payload,
    add constraint ck_hearing_flow_action_party_payload
        check (
            action_type not in ('ANSWER_BUNDLE', 'EVIDENCE_BATCH')
            or (
                payload_json ? 'participant_role'
                and payload_json ? 'submission_status'
                and payload_json ->> 'participant_role' = participant_role
                and payload_json ->> 'submission_status' = submission_status
                and (
                    not (payload_json ? 'participant_id')
                    or payload_json ->> 'participant_id' = participant_id
                )
                and (
                    schema_version <> 'hearing_party_statement.v1'
                    or (
                        payload_json ? 'participant_id'
                        and payload_json ? 'statement_text'
                    )
                )
            )
        );
