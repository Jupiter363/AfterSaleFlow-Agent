create table audit_log (
    id varchar(64) primary key,
    case_id varchar(64),
    trace_id varchar(128) not null,
    request_id varchar(128) not null,
    workflow_id varchar(128),
    user_id varchar(128),
    role varchar(32) not null,
    service varchar(64) not null,
    action varchar(128) not null,
    resource_type varchar(64) not null,
    resource_id varchar(128),
    outcome varchar(32) not null,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    source_ip varchar(64),
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_audit_log_case
        foreign key (case_id) references fulfillment_case(id)
);

create index idx_audit_log_case_id on audit_log(case_id, created_at);
create index idx_audit_log_trace_id on audit_log(trace_id);
create index idx_audit_log_action on audit_log(action, created_at);

create table policy_rule (
    id varchar(64) primary key,
    rule_code varchar(128) not null,
    rule_version integer not null,
    rule_name varchar(256) not null,
    rule_scope varchar(64) not null,
    rule_status varchar(32) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    priority integer not null default 0,
    condition_json jsonb not null default '{}'::jsonb,
    outcome_json jsonb not null default '{}'::jsonb,
    source_document_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint ck_policy_rule_dates
        check (effective_to is null or effective_to > effective_from)
);

create unique index uq_policy_rule_code_version on policy_rule(rule_code, rule_version);
create index idx_policy_rule_scope_status on policy_rule(rule_scope, rule_status);
create index idx_policy_rule_effective on policy_rule(effective_from, effective_to);

create table evaluation_trace (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    evaluation_version integer not null default 1,
    evaluation_status varchar(32) not null,
    evaluator_model varchar(128),
    prompt_version varchar(64),
    input_snapshot_json jsonb not null default '{}'::jsonb,
    metric_scores_json jsonb not null default '{}'::jsonb,
    findings_json jsonb not null default '[]'::jsonb,
    report_json jsonb not null default '{}'::jsonb,
    latency_ms bigint,
    token_usage integer,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_evaluation_trace_case
        foreign key (case_id) references fulfillment_case(id),
    constraint uq_evaluation_trace_version unique (case_id, evaluation_version)
);

create index idx_evaluation_trace_case_id on evaluation_trace(case_id);
create index idx_evaluation_trace_status on evaluation_trace(evaluation_status);
