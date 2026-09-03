create table case_intake_party_completion (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    participant_role varchar(32) not null,
    participant_id varchar(128) not null,
    completion_status varchar(32) not null,
    completed_at timestamptz not null,
    created_at timestamptz not null,
    created_by varchar(128) not null,
    constraint fk_case_intake_party_completion_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_case_intake_party_completion_role
        unique (case_id, participant_role),
    constraint ck_case_intake_party_completion_role
        check (participant_role in ('USER', 'MERCHANT')),
    constraint ck_case_intake_party_completion_status
        check (completion_status in ('COMPLETED', 'TIMED_OUT'))
);

create index idx_case_intake_party_completion_case
    on case_intake_party_completion(case_id, completed_at);
