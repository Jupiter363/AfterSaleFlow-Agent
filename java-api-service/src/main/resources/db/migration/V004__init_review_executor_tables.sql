create table remedy_plan (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    adjudication_draft_id varchar(64),
    plan_version integer not null default 1,
    source_route varchar(64) not null,
    plan_status varchar(32) not null,
    risk_level varchar(32) not null,
    total_amount numeric(18,2) not null default 0,
    currency varchar(8) not null default 'CNY',
    actions_json jsonb not null default '[]'::jsonb,
    preconditions_json jsonb not null default '[]'::jsonb,
    notification_plan_json jsonb not null default '[]'::jsonb,
    requires_human_review boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_remedy_plan_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_remedy_plan_draft
        foreign key (adjudication_draft_id) references adjudication_draft(id),
    constraint uq_remedy_plan_version unique (case_id, plan_version),
    constraint ck_remedy_plan_amount check (total_amount >= 0)
);

create index idx_remedy_plan_case_id on remedy_plan(case_id);
create index idx_remedy_plan_status on remedy_plan(plan_status);

create table review_packet (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    plan_id varchar(64) not null,
    packet_version integer not null default 1,
    case_summary_json jsonb not null default '{}'::jsonb,
    claims_json jsonb not null default '[]'::jsonb,
    issues_json jsonb not null default '[]'::jsonb,
    evidence_matrix_json jsonb not null default '[]'::jsonb,
    draft_json jsonb not null default '{}'::jsonb,
    remedy_json jsonb not null default '{}'::jsonb,
    risk_flags_json jsonb not null default '[]'::jsonb,
    packet_status varchar(32) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_review_packet_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_review_packet_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint uq_review_packet_version unique (case_id, plan_id, packet_version)
);

create index idx_review_packet_case_id on review_packet(case_id);

create table review_task (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    plan_id varchar(64) not null,
    packet_id varchar(64) not null,
    task_status varchar(32) not null,
    priority varchar(32) not null,
    assigned_reviewer_id varchar(128),
    required_role varchar(32) not null default 'PLATFORM_REVIEWER',
    due_at timestamptz,
    decision_json jsonb not null default '{}'::jsonb,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_review_task_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_review_task_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint fk_review_task_packet
        foreign key (packet_id) references review_packet(id)
);

create index idx_review_task_status on review_task(task_status, priority, created_at);
create index idx_review_task_reviewer on review_task(assigned_reviewer_id, task_status);

create table approval_record (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    review_task_id varchar(64) not null,
    plan_id varchar(64) not null,
    reviewer_id varchar(128) not null,
    reviewer_role varchar(32) not null,
    decision_type varchar(32) not null,
    original_plan_json jsonb not null default '{}'::jsonb,
    approved_plan_json jsonb not null default '{}'::jsonb,
    decision_reason text,
    approval_hash varchar(128) not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_approval_record_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_approval_record_task
        foreign key (review_task_id) references review_task(id),
    constraint fk_approval_record_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint uq_approval_record_hash unique (approval_hash)
);

create index idx_approval_record_case_id on approval_record(case_id);
create index idx_approval_record_task_id on approval_record(review_task_id);

create table action_record (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    plan_id varchar(64) not null,
    approval_record_id varchar(64) not null,
    action_type varchar(64) not null,
    risk_level varchar(32) not null,
    idempotency_key varchar(128) not null unique,
    approved_by varchar(128) not null,
    executed_by varchar(128) not null,
    request_json jsonb not null default '{}'::jsonb,
    result_json jsonb not null default '{}'::jsonb,
    execution_status varchar(32) not null,
    error_code varchar(64),
    error_message text,
    attempt_count integer not null default 1,
    execution_time timestamptz,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_action_record_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_action_record_plan
        foreign key (plan_id) references remedy_plan(id),
    constraint fk_action_record_approval
        foreign key (approval_record_id) references approval_record(id),
    constraint ck_action_record_attempt check (attempt_count > 0)
);

create index idx_action_record_case_id on action_record(case_id);
create index idx_action_record_status on action_record(execution_status);
create index idx_action_record_plan_id on action_record(plan_id);
