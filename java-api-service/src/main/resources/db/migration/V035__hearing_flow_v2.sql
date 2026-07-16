-- hearing_flow.v2 uses its own state and immutable action ledger. The legacy
-- hearing_round tables are intentionally untouched and are not a V2 carrier.

create table hearing_flow_instance (
    id varchar(64) primary key,
    case_id varchar(64) not null unique,
    hearing_state_id varchar(64) not null unique,
    schema_version varchar(32) not null default 'hearing_flow.v2',
    current_stage varchar(64) not null,
    stage_sequence integer not null,
    flow_status varchar(32) not null,
    shared_deadline_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_hearing_flow_instance_case
        foreign key (case_id) references fulfillment_dispute_case(id)
        on delete cascade,
    constraint fk_hearing_flow_instance_state
        foreign key (hearing_state_id) references hearing_state(id)
        on delete cascade,
    constraint uq_hearing_flow_instance_id_case unique (id, case_id),
    constraint ck_hearing_flow_instance_schema
        check (schema_version = 'hearing_flow.v2'),
    constraint ck_hearing_flow_instance_sequence
        check (stage_sequence >= 1),
    constraint ck_hearing_flow_instance_status
        check (flow_status in ('ACTIVE', 'HUMAN_REVIEW', 'CLOSED', 'FAILED')),
    constraint ck_hearing_flow_instance_stage
        check (current_stage in (
            'COURT_PREPARING',
            'CASE_INTRODUCTION',
            'EVIDENCE_INTRODUCTION',
            'INTAKE_QUESTIONS_GENERATING',
            'PARTY_ANSWERS_OPEN',
            'INTAKE_SYNTHESIZING',
            'EVIDENCE_REQUESTS_GENERATING',
            'PARTY_EVIDENCE_OPEN',
            'EVIDENCE_SYNTHESIZING',
            'DOSSIER_FREEZING',
            'JUDGE_V1_GENERATING',
            'JURY_REVIEWING',
            'JUDGE_V2_GENERATING',
            'HUMAN_REVIEW_OPEN',
            'CLOSED'
        )),
    constraint ck_hearing_flow_instance_deadline
        check (
            (current_stage in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and shared_deadline_at is not null)
            or
            (current_stage not in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and shared_deadline_at is null)
        ),
    constraint ck_hearing_flow_instance_terminal_shape
        check (
            (current_stage = 'CLOSED' and flow_status = 'CLOSED')
            or current_stage <> 'CLOSED'
        )
);

create index idx_hearing_flow_instance_stage
    on hearing_flow_instance(current_stage, flow_status);

create table hearing_flow_stage (
    id varchar(64) primary key,
    flow_instance_id varchar(64) not null,
    case_id varchar(64) not null,
    stage_code varchar(64) not null,
    stage_sequence integer not null,
    processor_role varchar(64) not null,
    stage_status varchar(32) not null,
    shared_deadline_at timestamptz,
    input_json jsonb not null default '{}'::jsonb,
    output_json jsonb not null default '{}'::jsonb,
    agent_run_id varchar(64),
    started_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_hearing_flow_stage_instance
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id)
        on delete cascade,
    constraint fk_hearing_flow_stage_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_hearing_flow_stage_sequence
        unique (flow_instance_id, stage_sequence),
    constraint uq_hearing_flow_stage_code
        unique (flow_instance_id, stage_code),
    constraint uq_hearing_flow_stage_id_instance_case
        unique (id, flow_instance_id, case_id),
    constraint ck_hearing_flow_stage_sequence
        check (stage_sequence >= 1),
    constraint ck_hearing_flow_stage_code
        check (stage_code in (
            'COURT_PREPARING',
            'CASE_INTRODUCTION',
            'EVIDENCE_INTRODUCTION',
            'INTAKE_QUESTIONS_GENERATING',
            'PARTY_ANSWERS_OPEN',
            'INTAKE_SYNTHESIZING',
            'EVIDENCE_REQUESTS_GENERATING',
            'PARTY_EVIDENCE_OPEN',
            'EVIDENCE_SYNTHESIZING',
            'DOSSIER_FREEZING',
            'JUDGE_V1_GENERATING',
            'JURY_REVIEWING',
            'JUDGE_V2_GENERATING',
            'HUMAN_REVIEW_OPEN',
            'CLOSED'
        )),
    constraint ck_hearing_flow_stage_processor
        check (processor_role in (
            'SYSTEM', 'INTAKE_OFFICER', 'EVIDENCE_CLERK',
            'PARTIES', 'PRESIDING_JUDGE', 'JURY_PANEL'
        )),
    constraint ck_hearing_flow_stage_status
        check (stage_status in (
            'PENDING', 'RUNNING', 'WAITING_PARTIES', 'COMPLETED', 'FAILED'
        )),
    constraint ck_hearing_flow_stage_deadline
        check (
            (stage_code in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and shared_deadline_at is not null)
            or
            (stage_code not in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                and shared_deadline_at is null)
        ),
    constraint ck_hearing_flow_stage_completion
        check (
            (stage_status in ('COMPLETED', 'FAILED') and completed_at is not null)
            or
            (stage_status not in ('COMPLETED', 'FAILED') and completed_at is null)
        ),
    constraint ck_hearing_flow_stage_party_wait
        check (
            stage_status <> 'WAITING_PARTIES'
            or stage_code in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
        ),
    constraint ck_hearing_flow_stage_payloads
        check (jsonb_typeof(input_json) = 'object' and jsonb_typeof(output_json) = 'object')
);

create index idx_hearing_flow_stage_current
    on hearing_flow_stage(flow_instance_id, stage_status, stage_sequence);

create index idx_hearing_flow_stage_agent_run
    on hearing_flow_stage(agent_run_id)
    where agent_run_id is not null;

create table hearing_flow_action (
    id varchar(64) primary key,
    flow_instance_id varchar(64) not null,
    stage_id varchar(64) not null,
    case_id varchar(64) not null,
    action_type varchar(32) not null,
    schema_version varchar(64) not null,
    participant_role varchar(32),
    submission_status varchar(32),
    payload_json jsonb not null,
    content_hash varchar(64) not null,
    agent_run_id varchar(64),
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_flow_action_stage
        foreign key (stage_id, flow_instance_id, case_id)
        references hearing_flow_stage(id, flow_instance_id, case_id)
        on delete cascade,
    constraint fk_hearing_flow_action_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_hearing_flow_action_id_case unique (id, case_id),
    constraint ck_hearing_flow_action_type
        check (action_type in (
            'QUESTION_SET', 'ANSWER_BUNDLE',
            'EVIDENCE_REQUEST_SET', 'EVIDENCE_BATCH'
        )),
    constraint ck_hearing_flow_action_schema
        check (
            (action_type = 'QUESTION_SET'
                and schema_version = 'hearing_question_set.v1')
            or
            (action_type = 'ANSWER_BUNDLE'
                and schema_version = 'hearing_answer_bundle.v1')
            or
            (action_type = 'EVIDENCE_REQUEST_SET'
                and schema_version = 'hearing_evidence_request_set.v1')
            or
            (action_type = 'EVIDENCE_BATCH'
                and schema_version = 'hearing_evidence_batch.v1')
        ),
    constraint ck_hearing_flow_action_payload_schema
        check (
            jsonb_typeof(payload_json) = 'object'
            and payload_json ? 'schema_version'
            and payload_json ->> 'schema_version' = schema_version
        ),
    constraint ck_hearing_flow_action_actor_shape
        check (
            (action_type in ('QUESTION_SET', 'EVIDENCE_REQUEST_SET')
                and participant_role is null
                and submission_status is null
                and agent_run_id is not null)
            or
            (action_type in ('ANSWER_BUNDLE', 'EVIDENCE_BATCH')
                and participant_role in ('USER', 'MERCHANT')
                and submission_status in ('SUBMITTED', 'AUTO_TIMEOUT')
                and agent_run_id is null)
        ),
    constraint ck_hearing_flow_action_question_count
        check (
            action_type <> 'QUESTION_SET'
            or (
                jsonb_typeof(payload_json -> 'questions') = 'array'
                and jsonb_array_length(payload_json -> 'questions') between 1 and 5
            )
        ),
    constraint ck_hearing_flow_action_party_payload
        check (
            action_type not in ('ANSWER_BUNDLE', 'EVIDENCE_BATCH')
            or (
                payload_json ? 'participant_role'
                and payload_json ? 'submission_status'
                and payload_json ->> 'participant_role' = participant_role
                and payload_json ->> 'submission_status' = submission_status
            )
        ),
    constraint ck_hearing_flow_action_hash
        check (content_hash ~ '^[0-9a-f]{64}$')
);

create unique index uq_hearing_flow_action_generated
    on hearing_flow_action(stage_id, action_type)
    where participant_role is null;

create unique index uq_hearing_flow_action_party
    on hearing_flow_action(stage_id, action_type, participant_role)
    where participant_role is not null;

create index idx_hearing_flow_action_flow
    on hearing_flow_action(flow_instance_id, action_type, created_at);

create table hearing_trial_dossier (
    id varchar(64) primary key,
    case_id varchar(64) not null unique,
    flow_instance_id varchar(64) not null unique,
    schema_version varchar(32) not null default 'trial_dossier.v1',
    case_matrix_version integer not null,
    case_matrix_hash varchar(64) not null,
    evidence_matrix_version integer not null,
    evidence_matrix_hash varchar(64) not null,
    question_set_id varchar(64) not null,
    request_set_id varchar(64) not null,
    payload_json jsonb not null,
    content_hash varchar(64) not null,
    frozen_at timestamptz not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_trial_dossier_flow
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id)
        on delete cascade,
    constraint uq_hearing_trial_dossier_id_case unique (id, case_id),
    constraint ck_hearing_trial_dossier_schema
        check (schema_version = 'trial_dossier.v1'),
    constraint ck_hearing_trial_dossier_versions
        check (case_matrix_version >= 1 and evidence_matrix_version >= 1),
    constraint ck_hearing_trial_dossier_hashes
        check (
            case_matrix_hash ~ '^[0-9a-f]{64}$'
            and evidence_matrix_hash ~ '^[0-9a-f]{64}$'
            and content_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_hearing_trial_dossier_payload
        check (
            jsonb_typeof(payload_json) = 'object'
            and payload_json ?& array[
                'schema_version', 'trial_dossier_id', 'case_id', 'frozen_at',
                'case_matrix_version', 'case_matrix_hash', 'case_fact_matrix',
                'evidence_matrix_version', 'evidence_matrix_hash',
                'fact_evidence_matrix', 'question_set_id', 'question_set',
                'answer_bundles', 'request_set_id', 'evidence_request_set',
                'evidence_batches', 'content_hash'
            ]
            and payload_json ->> 'schema_version' = 'trial_dossier.v1'
            and payload_json ->> 'trial_dossier_id' = id
            and payload_json ->> 'case_id' = case_id
            and payload_json ->> 'question_set_id' = question_set_id
            and payload_json ->> 'request_set_id' = request_set_id
            and payload_json ->> 'content_hash' = content_hash
            and (payload_json ->> 'case_matrix_version')::integer = case_matrix_version
            and payload_json ->> 'case_matrix_hash' = case_matrix_hash
            and (payload_json ->> 'evidence_matrix_version')::integer = evidence_matrix_version
            and payload_json ->> 'evidence_matrix_hash' = evidence_matrix_hash
            and payload_json #>> '{case_fact_matrix,content_hash}' = case_matrix_hash
            and payload_json #>> '{fact_evidence_matrix,content_hash}' = evidence_matrix_hash
            and payload_json #>> '{fact_evidence_matrix,matrix_status}' = 'FROZEN'
            and jsonb_typeof(payload_json -> 'answer_bundles') = 'array'
            and jsonb_array_length(payload_json -> 'answer_bundles') = 2
            and jsonb_typeof(payload_json -> 'evidence_batches') = 'array'
            and jsonb_array_length(payload_json -> 'evidence_batches') = 2
        )
);

create table hearing_flow_artifact (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    flow_instance_id varchar(64) not null,
    trial_dossier_id varchar(64) not null,
    trial_dossier_hash varchar(64) not null,
    artifact_type varchar(32) not null,
    schema_version varchar(64) not null,
    proposal_id varchar(64),
    proposal_content_hash varchar(64),
    report_id varchar(64),
    report_content_hash varchar(64),
    content_hash varchar(64) not null,
    payload_json jsonb not null,
    agent_run_id varchar(64) not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_hearing_flow_artifact_flow
        foreign key (flow_instance_id, case_id)
        references hearing_flow_instance(id, case_id)
        on delete cascade,
    constraint fk_hearing_flow_artifact_dossier
        foreign key (trial_dossier_id, case_id)
        references hearing_trial_dossier(id, case_id)
        on delete cascade,
    constraint fk_hearing_flow_artifact_proposal
        foreign key (proposal_id, case_id)
        references hearing_flow_artifact(id, case_id),
    constraint fk_hearing_flow_artifact_report
        foreign key (report_id, case_id)
        references hearing_flow_artifact(id, case_id),
    constraint fk_hearing_flow_artifact_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_hearing_flow_artifact_case_type
        unique (case_id, artifact_type),
    constraint uq_hearing_flow_artifact_id_case unique (id, case_id),
    constraint ck_hearing_flow_artifact_type
        check (artifact_type in (
            'JUDGE_PROPOSAL', 'JURY_REVIEW_REPORT', 'ADJUDICATION_DRAFT'
        )),
    constraint ck_hearing_flow_artifact_schema
        check (
            (artifact_type = 'JUDGE_PROPOSAL'
                and schema_version = 'judge_proposal.v1')
            or
            (artifact_type = 'JURY_REVIEW_REPORT'
                and schema_version = 'jury_review_report.v1')
            or
            (artifact_type = 'ADJUDICATION_DRAFT'
                and schema_version = 'adjudication_draft.v2')
        ),
    constraint ck_hearing_flow_artifact_parent_chain
        check (
            (artifact_type = 'JUDGE_PROPOSAL'
                and proposal_id is null
                and proposal_content_hash is null
                and report_id is null
                and report_content_hash is null)
            or
            (artifact_type = 'JURY_REVIEW_REPORT'
                and proposal_id is not null
                and proposal_content_hash ~ '^[0-9a-f]{64}$'
                and report_id is null
                and report_content_hash is null)
            or
            (artifact_type = 'ADJUDICATION_DRAFT'
                and proposal_id is not null
                and proposal_content_hash ~ '^[0-9a-f]{64}$'
                and report_id is not null
                and report_content_hash ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_hearing_flow_artifact_hashes
        check (
            trial_dossier_hash ~ '^[0-9a-f]{64}$'
            and content_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_hearing_flow_artifact_payload
        check (
            jsonb_typeof(payload_json) = 'object'
            and payload_json ?& array[
                'schema_version', 'trial_dossier_id', 'trial_dossier_hash',
                'content_hash'
            ]
            and payload_json ->> 'schema_version' = schema_version
            and payload_json ->> 'trial_dossier_id' = trial_dossier_id
            and payload_json ->> 'trial_dossier_hash' = trial_dossier_hash
            and payload_json ->> 'content_hash' = content_hash
        ),
    constraint ck_hearing_flow_artifact_business_id
        check (
            (artifact_type = 'JUDGE_PROPOSAL'
                and payload_json ? 'proposal_id'
                and payload_json ->> 'proposal_id' = id)
            or
            (artifact_type = 'JURY_REVIEW_REPORT'
                and payload_json ?& array[
                    'report_id', 'proposal_id', 'proposal_content_hash'
                ]
                and payload_json ->> 'report_id' = id
                and payload_json ->> 'proposal_id' = proposal_id
                and payload_json ->> 'proposal_content_hash' = proposal_content_hash)
            or
            (artifact_type = 'ADJUDICATION_DRAFT'
                and payload_json ?& array[
                    'draft_id', 'proposal_id', 'proposal_content_hash',
                    'report_id', 'report_content_hash'
                ]
                and payload_json ->> 'draft_id' = id
                and payload_json ->> 'proposal_id' = proposal_id
                and payload_json ->> 'proposal_content_hash' = proposal_content_hash
                and payload_json ->> 'report_id' = report_id
                and payload_json ->> 'report_content_hash' = report_content_hash)
        )
);

create index idx_hearing_flow_artifact_flow
    on hearing_flow_artifact(flow_instance_id, artifact_type);

create index idx_hearing_flow_artifact_agent_run
    on hearing_flow_artifact(agent_run_id);

-- Message type controls rendering; message_source records one of the four
-- provenance modes from the V2 contract. AUTO_TIMEOUT is a PARTY_ACTION whose
-- structured bundle carries submission_status=AUTO_TIMEOUT.
alter table room_message
    add column message_source varchar(32);

alter table room_message disable trigger trg_room_message_append_only;

update room_message
set message_source = case
    when sender_type = 'PARTY' then 'PARTY_ACTION'
    when sender_type = 'AGENT' and agent_run_id is null then 'ROLE_TEMPLATE'
    when sender_type = 'AGENT' then 'AGENT_LLM'
    else 'SYSTEM_STAGE_EVENT'
end;

alter table room_message enable trigger trg_room_message_append_only;

alter table room_message
    alter column message_source set not null,
    add constraint ck_room_message_source
        check (message_source in (
            'SYSTEM_STAGE_EVENT', 'ROLE_TEMPLATE', 'AGENT_LLM', 'PARTY_ACTION'
        ));

alter table room_message drop constraint if exists ck_room_message_type;

alter table room_message
    add constraint ck_room_message_type
        check (message_type in (
            'PARTY_TEXT',
            'PARTY_EVIDENCE_REFERENCE',
            'PARTY_CONFIRMATION',
            'AGENT_MESSAGE',
            'JURY_REVIEW_REPORT',
            'SYSTEM_STAGE_EVENT',
            'SYSTEM_EVENT',
            'REVIEWER_NOTE'
        ));

-- V034's reviewer purge deletes agent_run before hearing_state. Remove the V2
-- aggregate at that boundary so its AgentRun foreign keys cannot block the
-- already-authorized case purge. Outside the scoped purge transaction this
-- trigger is a no-op and the immutable-table guards remain effective.
create function purge_hearing_flow_v2_before_agent_run_delete()
returns trigger
language plpgsql
as $$
declare
    purge_case_id text;
    purge_reviewer_role text;
begin
    purge_case_id := current_setting('app.demo_case_purge_case_id', true);
    purge_reviewer_role :=
        current_setting('app.demo_case_purge_reviewer_role', true);

    if purge_reviewer_role = 'PLATFORM_REVIEWER'
       and purge_case_id is not null
       and old.case_id = purge_case_id then
        delete from hearing_flow_artifact where case_id = purge_case_id;
        delete from hearing_trial_dossier where case_id = purge_case_id;
        delete from hearing_flow_action where case_id = purge_case_id;
        delete from hearing_flow_stage where case_id = purge_case_id;
        delete from hearing_flow_instance where case_id = purge_case_id;
    end if;

    return old;
end;
$$;

create trigger trg_purge_hearing_flow_v2_before_agent_run_delete
    before delete on agent_run
    for each row execute function purge_hearing_flow_v2_before_agent_run_delete();

-- Structured actions and frozen artifacts are immutable. Flow and stage rows
-- remain mutable because they are the authoritative state-machine cursor.
create trigger trg_hearing_flow_action_append_only
    before update or truncate on hearing_flow_action
    for each statement execute function reject_append_only_mutation();

create trigger trg_hearing_flow_action_delete_append_only
    before delete on hearing_flow_action
    for each row execute function reject_append_only_mutation();

create trigger trg_hearing_trial_dossier_append_only
    before update or truncate on hearing_trial_dossier
    for each statement execute function reject_append_only_mutation();

create trigger trg_hearing_trial_dossier_delete_append_only
    before delete on hearing_trial_dossier
    for each row execute function reject_append_only_mutation();

create trigger trg_hearing_flow_artifact_append_only
    before update or truncate on hearing_flow_artifact
    for each statement execute function reject_append_only_mutation();

create trigger trg_hearing_flow_artifact_delete_append_only
    before delete on hearing_flow_artifact
    for each row execute function reject_append_only_mutation();
