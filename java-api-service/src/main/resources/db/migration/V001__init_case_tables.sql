create table fulfillment_case (
    id varchar(64) primary key,
    order_id varchar(64),
    after_sale_id varchar(64),
    user_id varchar(128) not null,
    merchant_id varchar(128) not null,
    creation_idempotency_key varchar(128) not null unique,
    case_type varchar(64) not null,
    dispute_type varchar(64),
    case_status varchar(64) not null,
    route_type varchar(64),
    risk_level varchar(32) not null,
    current_workflow_id varchar(128),
    title varchar(256) not null,
    description text not null,
    intake_result_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    closed_at timestamptz,
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint ck_fulfillment_case_risk_level
        check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create index idx_fulfillment_case_user_id on fulfillment_case(user_id);
create index idx_fulfillment_case_merchant_id on fulfillment_case(merchant_id);
create index idx_fulfillment_case_status on fulfillment_case(case_status);
create index idx_fulfillment_case_order_id on fulfillment_case(order_id);
create index idx_fulfillment_case_route_type on fulfillment_case(route_type);

create table party_claim (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    party_role varchar(32) not null,
    party_id varchar(128) not null,
    claim_key varchar(128) not null,
    claim_text text not null,
    claim_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    claim_status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_party_claim_case
        foreign key (case_id) references fulfillment_case(id),
    constraint uq_party_claim_case_role_key
        unique (case_id, party_role, claim_key),
    constraint ck_party_claim_role
        check (party_role in ('USER', 'MERCHANT', 'PLATFORM', 'SYSTEM'))
);

create index idx_party_claim_case_id on party_claim(case_id);
create index idx_party_claim_party on party_claim(party_role, party_id);

create table issue (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    issue_key varchar(128) not null,
    issue_type varchar(64) not null,
    title varchar(256) not null,
    description text not null,
    issue_status varchar(32) not null,
    issue_json jsonb not null default '{}'::jsonb,
    burden_json jsonb not null default '{}'::jsonb,
    sequence_no integer not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_issue_case
        foreign key (case_id) references fulfillment_case(id),
    constraint uq_issue_case_key unique (case_id, issue_key),
    constraint uq_issue_case_sequence unique (case_id, sequence_no)
);

create index idx_issue_case_id on issue(case_id);
create index idx_issue_status on issue(issue_status);
