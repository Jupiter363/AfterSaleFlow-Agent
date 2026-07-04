alter table hearing_round
    add column round_deadline_at timestamptz;

update hearing_round
set round_deadline_at = opened_at + interval '5 minutes'
where round_deadline_at is null;

alter table hearing_round
    alter column round_deadline_at set not null;

create table hearing_round_party_submission (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    round_id varchar(64) not null,
    round_no integer not null,
    participant_role varchar(32) not null,
    participant_id varchar(128) not null,
    submission_source varchar(32) not null,
    submission_json jsonb not null default '{}'::jsonb,
    submitted_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_hearing_round_party_submission_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_hearing_round_party_submission_round
        foreign key (round_id) references hearing_round(id),
    constraint uq_hearing_round_party_submission_role
        unique (case_id, round_no, participant_role),
    constraint ck_hearing_round_party_submission_round_no
        check (round_no between 1 and 3),
    constraint ck_hearing_round_party_submission_role
        check (participant_role in ('USER', 'MERCHANT')),
    constraint ck_hearing_round_party_submission_source
        check (submission_source in ('PARTY_ACTION', 'AUTO_TIMEOUT'))
);

create index idx_hearing_round_party_submission_round
    on hearing_round_party_submission(round_id, submitted_at);
