-- Versioned evidence, Agent governance, deliberation, review, and execution
-- artifacts required by the final architecture.

create table evidence_dossier_item (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    dossier_id varchar(64) not null,
    evidence_id varchar(64) not null,
    sequence_no integer not null,
    evidence_snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_dossier_item_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_dossier_item_dossier
        foreign key (dossier_id) references evidence_dossier(id),
    constraint fk_dossier_item_evidence
        foreign key (evidence_id) references evidence_item(id),
    constraint uq_dossier_item_version
        unique (dossier_id, evidence_id)
);

create table case_timeline_event (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    dossier_id varchar(64),
    event_type varchar(64) not null,
    event_time timestamptz not null,
    source_refs_json jsonb not null default '[]'::jsonb,
    event_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_timeline_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_timeline_dossier
        foreign key (dossier_id) references evidence_dossier(id)
);

create table agent_run (
    id varchar(64) primary key,
    case_id varchar(64),
    workflow_id varchar(128),
    agent_id varchar(128) not null,
    agent_role varchar(128) not null,
    profile_version varchar(64) not null,
    prompt_version varchar(64) not null,
    skill_version varchar(64) not null,
    ruleset_version varchar(64) not null,
    model varchar(128),
    run_status varchar(32) not null,
    stop_reason varchar(64),
    input_refs_json jsonb not null default '[]'::jsonb,
    output_ref varchar(128),
    validation_json jsonb not null default '{}'::jsonb,
    risk_flags_json jsonb not null default '[]'::jsonb,
    token_usage integer,
    latency_ms bigint,
    cost_amount numeric(18,6),
    started_at timestamptz not null,
    completed_at timestamptz,
    trace_id varchar(128) not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_agent_run_case
        foreign key (case_id) references fulfillment_dispute_case(id)
);

create table agent_tool_call (
    id varchar(64) primary key,
    agent_run_id varchar(64) not null,
    case_id varchar(64) not null,
    tool_name varchar(128) not null,
    tool_version varchar(64) not null,
    reason text not null,
    arguments_json jsonb not null default '{}'::jsonb,
    result_ref varchar(256),
    status varchar(32) not null,
    audit_id varchar(64),
    latency_ms bigint,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_agent_tool_run
        foreign key (agent_run_id) references agent_run(id),
    constraint fk_agent_tool_case
        foreign key (case_id) references fulfillment_dispute_case(id)
);

create table agent_guardrail_event (
    id varchar(64) primary key,
    agent_run_id varchar(64) not null,
    case_id varchar(64) not null,
    event_type varchar(64) not null,
    severity varchar(32) not null,
    decision varchar(32) not null,
    details_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_guardrail_run
        foreign key (agent_run_id) references agent_run(id),
    constraint fk_guardrail_case
        foreign key (case_id) references fulfillment_dispute_case(id)
);

create table agent_memory_entry (
    id varchar(64) primary key,
    case_id varchar(64),
    agent_run_id varchar(64),
    memory_scope varchar(32) not null,
    memory_key varchar(128) not null,
    memory_version integer not null,
    source_refs_json jsonb not null default '[]'::jsonb,
    content_json jsonb not null default '{}'::jsonb,
    approved_for_experience boolean not null default false,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_memory_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_memory_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_memory_version
        unique (memory_scope, memory_key, memory_version)
);

create table skill_version (
    id varchar(64) primary key,
    skill_code varchar(128) not null,
    version varchar(64) not null,
    input_schema_json jsonb not null,
    output_schema_json jsonb not null,
    allowed_agents_json jsonb not null default '[]'::jsonb,
    status varchar(32) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint uq_skill_version unique (skill_code, version)
);

create table prompt_version (
    id varchar(64) primary key,
    prompt_code varchar(128) not null,
    version varchar(64) not null,
    content_hash varchar(128) not null,
    policy_json jsonb not null default '{}'::jsonb,
    status varchar(32) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint uq_prompt_version unique (prompt_code, version)
);

create table deliberation_report (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    report_version integer not null,
    draft_id varchar(64) not null,
    frozen_dossier_version integer not null,
    panel_result_json jsonb not null default '{}'::jsonb,
    major_risks_json jsonb not null default '[]'::jsonb,
    consensus_json jsonb not null default '[]'::jsonb,
    disagreements_json jsonb not null default '[]'::jsonb,
    recommended_revision_json jsonb not null default '[]'::jsonb,
    trace_id varchar(128) not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_deliberation_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_deliberation_draft
        foreign key (draft_id) references adjudication_draft(id),
    constraint uq_deliberation_version
        unique (case_id, report_version)
);

create table deliberation_finding (
    id varchar(64) primary key,
    report_id varchar(64) not null,
    case_id varchar(64) not null,
    critic_type varchar(32) not null,
    severity varchar(32) not null,
    finding_json jsonb not null,
    evidence_refs_json jsonb not null default '[]'::jsonb,
    rule_refs_json jsonb not null default '[]'::jsonb,
    major_objection boolean not null default false,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_deliberation_finding_report
        foreign key (report_id) references deliberation_report(id),
    constraint fk_deliberation_finding_case
        foreign key (case_id) references fulfillment_dispute_case(id)
);

create table remedy_action (
    id varchar(64) primary key,
    plan_id varchar(64) not null,
    case_id varchar(64) not null,
    action_type varchar(64) not null,
    sequence_no integer not null,
    action_parameters_json jsonb not null default '{}'::jsonb,
    dependency_ids_json jsonb not null default '[]'::jsonb,
    action_hash varchar(128) not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_remedy_action_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint fk_remedy_action_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_remedy_action_hash unique (action_hash)
);

create table approval_policy_decision (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    plan_id varchar(64) not null,
    policy_version varchar(64) not null,
    risk_level varchar(32) not null,
    required_reviewer_role varchar(32) not null,
    required_review_count integer not null default 1,
    allowed_actions_json jsonb not null default '[]'::jsonb,
    forbidden_actions_json jsonb not null default '[]'::jsonb,
    escalation_reason text,
    auto_approve boolean not null default false,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_policy_decision_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_policy_decision_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint ck_policy_never_auto_approve check (not auto_approve)
);

alter table evidence_dossier
    add column created_by_agent_run_id varchar(64),
    add column timeline_version integer not null default 1,
    add column matrix_version integer not null default 1,
    add constraint fk_dossier_agent_run
        foreign key (created_by_agent_run_id) references agent_run(id);

alter table adjudication_draft
    add column non_final boolean not null default true,
    add column created_by_agent_run_id varchar(64),
    add constraint ck_adjudication_draft_non_final check (non_final),
    add constraint fk_draft_agent_run
        foreign key (created_by_agent_run_id) references agent_run(id);

alter table review_packet
    add column case_version bigint,
    add column dossier_version integer,
    add column issue_version integer,
    add column adjudication_draft_version integer,
    add column deliberation_report_version integer,
    add column remedy_plan_version integer,
    add column ruleset_version varchar(64),
    add column prompt_version varchar(64),
    add column skill_version varchar(64),
    add column profile_version varchar(64),
    add column action_hash varchar(128);

create index idx_timeline_case_time on case_timeline_event(case_id, event_time);
create index idx_agent_run_case on agent_run(case_id, created_at);
create index idx_agent_tool_run on agent_tool_call(agent_run_id, created_at);
create index idx_guardrail_case on agent_guardrail_event(case_id, created_at);
create index idx_deliberation_case on deliberation_report(case_id, report_version);
create index idx_deliberation_finding_report on deliberation_finding(report_id);
create index idx_remedy_action_plan on remedy_action(plan_id, sequence_no);
create index idx_policy_decision_case on approval_policy_decision(case_id, created_at);
