-- Hearing Intake V4 cut-over. Historical V1 rows remain immutable, while every
-- newly reachable Intake action and formal authority is version-exact.

alter table hearing_flow_action
    drop constraint ck_hearing_flow_action_schema,
    add constraint ck_hearing_flow_action_schema
        check (
            (action_type = 'QUESTION_SET'
                and schema_version in ('hearing_question_set.v1', 'hearing_question_set.v4'))
            or
            (action_type = 'ANSWER_BUNDLE'
                and schema_version in (
                    'hearing_answer_bundle.v1',
                    'hearing_party_statement.v1',
                    'hearing_answer_bundle.v4'
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
            (action_type = 'ANSWER_BUNDLE'
                and participant_id is not null
                and length(trim(participant_id)) > 0
                and participant_role in ('USER', 'MERCHANT')
                and (
                    (schema_version = 'hearing_answer_bundle.v4'
                        and submission_status = 'SUBMITTED')
                    or
                    (schema_version <> 'hearing_answer_bundle.v4'
                        and submission_status in ('SUBMITTED', 'AUTO_TIMEOUT'))
                )
                and agent_run_id is null)
            or
            (action_type = 'EVIDENCE_BATCH'
                and participant_id is not null
                and length(trim(participant_id)) > 0
                and participant_role in ('USER', 'MERCHANT')
                and submission_status in ('SUBMITTED', 'AUTO_TIMEOUT')
                and agent_run_id is null)
        ),
    add constraint ck_hearing_flow_action_intake_v4_shape
        check (
            schema_version not in ('hearing_question_set.v4', 'hearing_answer_bundle.v4')
            or (
                (schema_version = 'hearing_question_set.v4'
                    and action_type = 'QUESTION_SET'
                    and payload_json ?& array[
                        'question_set_id', 'question_set_hash',
                        'formal_issue_catalog_hash', 'case_id',
                        'source_matrix_id', 'source_matrix_version',
                        'source_matrix_hash', 'prelude_authority_hash', 'questions'
                    ]
                    and payload_json ->> 'question_set_id' = id
                    and payload_json ->> 'question_set_hash' = content_hash
                    and jsonb_typeof(payload_json -> 'questions') = 'array'
                    and jsonb_array_length(payload_json -> 'questions') between 1 and 5)
                or
                (schema_version = 'hearing_answer_bundle.v4'
                    and action_type = 'ANSWER_BUNDLE'
                    and submission_status = 'SUBMITTED'
                    and payload_json ?& array[
                        'answer_bundle_id', 'answer_bundle_hash',
                        'question_set_id', 'question_set_hash',
                        'formal_issue_catalog_hash', 'participant_id',
                        'participant_role', 'submission_status',
                        'answer_units', 'source_message_ids'
                    ]
                    and payload_json ->> 'answer_bundle_id' = id
                    and payload_json ->> 'answer_bundle_hash' = content_hash
                    and payload_json ->> 'participant_id' = participant_id
                    and payload_json ->> 'participant_role' = participant_role
                    and payload_json ->> 'submission_status' = 'SUBMITTED'
                    and jsonb_typeof(payload_json -> 'answer_units') = 'array'
                    and jsonb_array_length(payload_json -> 'answer_units') between 1 and 5
                    and jsonb_typeof(payload_json -> 'source_message_ids') = 'array')
            )
        );

create function enforce_hearing_intake_v4_action_insert()
returns trigger
language plpgsql
as $$
begin
    if (new.action_type = 'QUESTION_SET'
            and new.schema_version <> 'hearing_question_set.v4')
        or (new.action_type = 'ANSWER_BUNDLE'
            and new.schema_version <> 'hearing_answer_bundle.v4') then
        raise exception using
            errcode = '23514',
            message = format(
                'HEARING_INTAKE_V4_LEGACY_INSERT_FORBIDDEN: %s/%s',
                new.action_type,
                new.schema_version
            );
    end if;
    return new;
end;
$$;

create trigger trg_hearing_flow_action_intake_v4_insert
    before insert on hearing_flow_action
    for each row execute function enforce_hearing_intake_v4_action_insert();

create table hearing_issue_state_set (
    id varchar(128) primary key,
    case_id varchar(64) not null,
    flow_instance_id varchar(64) not null,
    source_stage_id varchar(64) not null,
    agent_run_id varchar(64) not null,
    schema_version varchar(64) not null,
    transition_set_id varchar(128) not null,
    transition_hash varchar(64) not null,
    question_set_id varchar(128) not null,
    question_set_hash varchar(64) not null,
    user_answer_bundle_id varchar(128) not null,
    user_answer_bundle_hash varchar(64) not null,
    merchant_answer_bundle_id varchar(128) not null,
    merchant_answer_bundle_hash varchar(64) not null,
    matrix_id varchar(128) not null,
    matrix_version integer not null,
    matrix_hash varchar(64) not null,
    payload_json jsonb not null,
    content_hash varchar(64) not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_issue_state_flow
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id) on delete cascade,
    constraint fk_hearing_issue_state_stage
        foreign key (source_stage_id, flow_instance_id, case_id)
        references hearing_flow_stage(id, flow_instance_id, case_id) on delete cascade,
    constraint fk_hearing_issue_state_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_hearing_issue_state_flow unique (flow_instance_id),
    constraint uq_hearing_issue_state_matrix unique (case_id, matrix_id, matrix_version),
    constraint ck_hearing_issue_state_schema
        check (schema_version = 'hearing_issue_state_set.v4'),
    constraint ck_hearing_issue_state_hashes
        check (
            transition_hash ~ '^[0-9a-f]{64}$'
            and question_set_hash ~ '^[0-9a-f]{64}$'
            and user_answer_bundle_hash ~ '^[0-9a-f]{64}$'
            and merchant_answer_bundle_hash ~ '^[0-9a-f]{64}$'
            and matrix_hash ~ '^[0-9a-f]{64}$'
            and content_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_hearing_issue_state_payload
        check (
            jsonb_typeof(payload_json) = 'object'
            and payload_json ->> 'schema_version' = schema_version
            and payload_json ->> 'issue_state_set_id' = id
            and payload_json ->> 'content_hash' = content_hash
            and payload_json ->> 'transition_set_id' = transition_set_id
            and payload_json ->> 'transition_hash' = transition_hash
            and payload_json ->> 'question_set_id' = question_set_id
            and payload_json ->> 'question_set_hash' = question_set_hash
            and payload_json #>> '{answer_bundle_ids,0}' = user_answer_bundle_id
            and payload_json #>> '{answer_bundle_hashes,0}' = user_answer_bundle_hash
            and payload_json #>> '{answer_bundle_ids,1}' = merchant_answer_bundle_id
            and payload_json #>> '{answer_bundle_hashes,1}' = merchant_answer_bundle_hash
            and payload_json ->> 'matrix_id' = matrix_id
            and (payload_json ->> 'matrix_version')::integer = matrix_version
            and payload_json ->> 'matrix_hash' = matrix_hash
            and jsonb_typeof(payload_json -> 'issues') = 'array'
            and jsonb_array_length(payload_json -> 'issues') between 1 and 10
        )
);

create trigger trg_hearing_issue_state_set_append_only
    before update or delete or truncate on hearing_issue_state_set
    for each statement execute function reject_append_only_mutation();

create table hearing_public_frame_binding_v4 (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    flow_instance_id varchar(64) not null,
    receipt_id varchar(64) not null,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    public_frame_row_id varchar(64) not null,
    frame_id varchar(128) not null,
    frame_sequence integer not null,
    frame_type varchar(64) not null,
    authority_ref varchar(128) not null,
    public_text_sha256 varchar(64) not null,
    message_id varchar(64) not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_v4_frame_flow
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id) on delete cascade,
    constraint fk_hearing_v4_frame_receipt
        foreign key (receipt_id) references hearing_domain_receipt(receipt_id),
    constraint fk_hearing_v4_frame_source
        foreign key (public_frame_row_id) references agent_run_public_frame(id),
    constraint fk_hearing_v4_frame_message
        foreign key (message_id) references room_message(id),
    constraint uq_hearing_v4_frame_receipt_sequence unique (receipt_id, frame_sequence),
    constraint uq_hearing_v4_frame_attempt_sequence
        unique (agent_run_id, agent_run_attempt_id, frame_sequence),
    constraint uq_hearing_v4_frame_message unique (message_id),
    constraint ck_hearing_v4_frame_identity
        check (
            frame_sequence between 1 and 11
            and length(trim(frame_id)) > 0
            and length(trim(frame_type)) > 0
            and length(trim(authority_ref)) > 0
            and public_text_sha256 ~ '^[0-9a-f]{64}$'
            and created_by = 'hearing-flow-v2'
        )
);

create index idx_hearing_issue_state_case
    on hearing_issue_state_set(case_id, matrix_version);

create index idx_hearing_v4_frame_replay
    on hearing_public_frame_binding_v4(agent_run_id, agent_run_attempt_id, frame_sequence);

create trigger trg_hearing_public_frame_binding_v4_append_only
    before update or delete or truncate on hearing_public_frame_binding_v4
    for each statement execute function reject_append_only_mutation();
