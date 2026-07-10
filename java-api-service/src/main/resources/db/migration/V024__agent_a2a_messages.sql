create table agent_a2a_message (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    round_no integer not null,
    from_agent varchar(64) not null,
    to_agent varchar(64) not null,
    message_type varchar(64) not null,
    input_refs_json jsonb not null,
    payload_json jsonb not null,
    visibility varchar(32) not null,
    agent_run_id varchar(64),
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_agent_a2a_case foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_agent_a2a_round check (round_no >= 1),
    constraint ck_agent_a2a_visibility check (visibility in ('SYSTEM_AUDIT_ONLY', 'REVIEWER_VISIBLE'))
);

create index idx_agent_a2a_judge_context
    on agent_a2a_message(case_id, to_agent, round_no, created_at);
