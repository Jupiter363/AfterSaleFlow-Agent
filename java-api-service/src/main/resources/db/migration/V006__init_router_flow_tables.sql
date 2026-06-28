create table route_decision (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    idempotency_key varchar(128) not null,
    route_type varchar(64) not null,
    reason_code varchar(128) not null,
    reason_detail text not null,
    requires_additional_evidence boolean not null default false,
    dossier_version integer not null,
    policy_rule_id varchar(64),
    input_snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_route_decision_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_route_decision_policy
        foreign key (policy_rule_id) references policy_rule(id),
    constraint uq_route_decision_case unique (case_id),
    constraint uq_route_decision_idempotency unique (case_id, idempotency_key),
    constraint ck_route_decision_type
        check (route_type in (
            'REGULAR_FULFILLMENT',
            'RULE_BASED_RESOLUTION',
            'DISPUTE_HEARING'
        ))
);

create index idx_route_decision_type_created
    on route_decision(route_type, created_at);

create table flow_conclusion (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    route_decision_id varchar(64) not null,
    conclusion_type varchar(64) not null,
    conclusion_status varchar(64) not null,
    conclusion_code varchar(128) not null,
    summary text not null,
    recommended_actions_json jsonb not null default '[]'::jsonb,
    policy_rule_id varchar(64),
    policy_version integer,
    risk_level varchar(32) not null,
    requires_remedy_planning boolean not null default true,
    requires_human_review boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_flow_conclusion_case
        foreign key (case_id) references fulfillment_case(id),
    constraint fk_flow_conclusion_route
        foreign key (route_decision_id) references route_decision(id),
    constraint fk_flow_conclusion_policy
        foreign key (policy_rule_id) references policy_rule(id),
    constraint uq_flow_conclusion_case unique (case_id),
    constraint uq_flow_conclusion_route unique (route_decision_id),
    constraint ck_flow_conclusion_downstream
        check (requires_remedy_planning and requires_human_review)
);

create index idx_flow_conclusion_status
    on flow_conclusion(conclusion_status, created_at);

insert into policy_rule (
    id,
    rule_code,
    rule_version,
    rule_name,
    rule_scope,
    rule_status,
    effective_from,
    priority,
    condition_json,
    outcome_json,
    source_document_json,
    created_by,
    updated_by
) values
(
    'POLICY_UNSHIPPED_CANCEL_V1',
    'UNSHIPPED_CANCEL',
    1,
    'Unshipped order cancellation policy',
    'UNSHIPPED_CANCEL',
    'ACTIVE',
    '2020-01-01T00:00:00Z',
    100,
    '{"requires_evidence":true,"maximum_risk_level":"MEDIUM"}'::jsonb,
    '{"conclusion_code":"REFUND_OR_CANCEL_RECOMMENDED","recommended_actions":["CANCEL_ORDER","REFUND"],"requires_human_review":true}'::jsonb,
    '{"document_code":"FULFILLMENT_POLICY","section":"UNSHIPPED_ORDER"}'::jsonb,
    'system',
    'system'
),
(
    'POLICY_MERCHANT_REFUND_V1',
    'MERCHANT_APPROVED_REFUND',
    1,
    'Merchant-approved refund policy',
    'MERCHANT_APPROVED_REFUND',
    'ACTIVE',
    '2020-01-01T00:00:00Z',
    90,
    '{"requires_evidence":true,"maximum_risk_level":"MEDIUM"}'::jsonb,
    '{"conclusion_code":"REFUND_RECOMMENDED","recommended_actions":["REFUND"],"requires_human_review":true}'::jsonb,
    '{"document_code":"AFTER_SALE_POLICY","section":"MERCHANT_APPROVAL"}'::jsonb,
    'system',
    'system'
);
