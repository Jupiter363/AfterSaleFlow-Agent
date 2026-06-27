create table hearing_state (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    workflow_id varchar(128) not null,
    hearing_status varchar(64) not null,
    current_node varchar(64),
    round_no integer not null default 0,
    confidence numeric(5,4),
    manual_required boolean not null default false,
    graph_state_json jsonb not null default '{}'::jsonb,
    pending_requests_json jsonb not null default '[]'::jsonb,
    manual_flags_json jsonb not null default '[]'::jsonb,
    waiting_until timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_hearing_state_case
        foreign key (case_id) references fulfillment_case(id),
    constraint uq_hearing_state_case unique (case_id),
    constraint uq_hearing_state_workflow unique (workflow_id),
    constraint ck_hearing_state_confidence
        check (confidence is null or (confidence >= 0 and confidence <= 1))
);

create index idx_hearing_state_status on hearing_state(hearing_status);

create table hearing_record (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    hearing_state_id varchar(64) not null,
    workflow_id varchar(128) not null,
    node_name varchar(64) not null,
    round_no integer not null,
    record_type varchar(64) not null,
    input_json jsonb not null default '{}'::jsonb,
    output_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    prompt_version varchar(64),
    model varchar(128),
    latency_ms bigint,
    token_usage integer,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_hearing_record_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_hearing_record_state
        foreign key (hearing_state_id) references hearing_state(id),
    constraint uq_hearing_record_node_round
        unique (workflow_id, node_name, round_no, record_type)
);

create index idx_hearing_record_case_id on hearing_record(case_id);
create index idx_hearing_record_workflow_id on hearing_record(workflow_id);

create table adjudication_draft (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    hearing_state_id varchar(64),
    draft_version integer not null default 1,
    fact_findings_json jsonb not null default '[]'::jsonb,
    evidence_assessment_json jsonb not null default '[]'::jsonb,
    policy_application_json jsonb not null default '[]'::jsonb,
    reviewer_attention_json jsonb not null default '[]'::jsonb,
    recommended_decision varchar(128) not null,
    confidence numeric(5,4) not null,
    draft_text text not null,
    created_by_agent varchar(128) not null,
    draft_status varchar(32) not null default 'DRAFT',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_adjudication_draft_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_adjudication_draft_hearing
        foreign key (hearing_state_id) references hearing_state(id),
    constraint uq_adjudication_draft_version unique (case_id, draft_version),
    constraint ck_adjudication_draft_confidence
        check (confidence >= 0 and confidence <= 1)
);

create index idx_adjudication_draft_case_id on adjudication_draft(case_id);
create index idx_adjudication_draft_status on adjudication_draft(draft_status);
