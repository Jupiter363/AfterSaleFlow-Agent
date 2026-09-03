-- Versioned evidence verification, party completion, hearing rounds, and settlement.

create table evidence_verification (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    evidence_id varchar(64) not null,
    verification_version integer not null,
    verification_status varchar(32) not null,
    deterministic_checks_json jsonb not null default '{}'::jsonb,
    agent_findings_json jsonb not null default '{}'::jsonb,
    reasons_json jsonb not null default '[]'::jsonb,
    requires_human_review boolean not null default false,
    verified_at timestamptz not null,
    verified_by varchar(128) not null,
    agent_run_id varchar(64),
    created_at timestamptz not null default now(),
    trace_id varchar(128),
    constraint fk_evidence_verification_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_evidence_verification_item
        foreign key (evidence_id) references evidence_item(id),
    constraint fk_evidence_verification_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_evidence_verification_version
        unique (evidence_id, verification_version),
    constraint ck_evidence_verification_status
        check (
            verification_status in (
                'VERIFIED',
                'PLAUSIBLE',
                'SUSPICIOUS',
                'REJECTED',
                'NEEDS_HUMAN_REVIEW'
            )
        )
);

create index idx_evidence_verification_case_status
    on evidence_verification(case_id, verification_status);

create table evidence_party_completion (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    dossier_version integer not null,
    participant_role varchar(32) not null,
    participant_id varchar(128) not null,
    completion_status varchar(32) not null default 'COMPLETED',
    idempotency_key varchar(128) not null,
    completed_at timestamptz not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_evidence_completion_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_evidence_completion_role
        unique (case_id, dossier_version, participant_role),
    constraint uq_evidence_completion_idempotency
        unique (case_id, idempotency_key),
    constraint ck_evidence_completion_role
        check (participant_role in ('USER', 'MERCHANT')),
    constraint ck_evidence_completion_status
        check (completion_status in ('COMPLETED', 'SUPERSEDED'))
);

create table hearing_round (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    hearing_state_id varchar(64),
    round_no integer not null,
    round_status varchar(32) not null,
    dossier_version integer not null,
    opened_at timestamptz not null,
    closed_at timestamptz,
    stop_reason varchar(64),
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_hearing_round_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_hearing_round_state
        foreign key (hearing_state_id) references hearing_state(id),
    constraint uq_hearing_round_case_no unique (case_id, round_no),
    constraint ck_hearing_round_no check (round_no between 1 and 3),
    constraint ck_hearing_round_status
        check (round_status in ('OPEN', 'WAITING', 'COMPLETED', 'FORCED_CLOSED')),
    constraint ck_hearing_stop_reason
        check (
            stop_reason is null
            or stop_reason in (
                'FACTS_SUFFICIENT',
                'SETTLEMENT_CONFIRMED',
                'MAX_ROUNDS',
                'DEADLINE_EXPIRED'
            )
        )
);

create index idx_hearing_round_case_status
    on hearing_round(case_id, round_status);

create table settlement_proposal (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    proposal_version integer not null,
    proposal_status varchar(32) not null,
    proposed_by_role varchar(32) not null,
    proposed_by_id varchar(128) not null,
    proposal_text text not null,
    proposal_json jsonb not null default '{}'::jsonb,
    supersedes_proposal_id varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    trace_id varchar(128),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_settlement_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_settlement_supersedes
        foreign key (supersedes_proposal_id) references settlement_proposal(id),
    constraint uq_settlement_case_version unique (case_id, proposal_version),
    constraint ck_settlement_status
        check (
            proposal_status in (
                'PENDING_CONFIRMATION',
                'CONFIRMED',
                'SUPERSEDED',
                'REJECTED'
            )
        ),
    constraint ck_settlement_proposer_role
        check (proposed_by_role in ('USER', 'MERCHANT', 'SYSTEM'))
);

create index idx_settlement_case_status
    on settlement_proposal(case_id, proposal_status);

create table settlement_confirmation (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    proposal_id varchar(64) not null,
    proposal_version integer not null,
    participant_role varchar(32) not null,
    participant_id varchar(128) not null,
    confirmation_status varchar(32) not null default 'CONFIRMED',
    idempotency_key varchar(128) not null,
    confirmed_at timestamptz not null,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_settlement_confirmation_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_settlement_confirmation_proposal
        foreign key (proposal_id) references settlement_proposal(id),
    constraint uq_settlement_confirmation_idempotency
        unique (case_id, idempotency_key),
    constraint ck_settlement_confirmation_role
        check (participant_role in ('USER', 'MERCHANT')),
    constraint ck_settlement_confirmation_status
        check (confirmation_status in ('CONFIRMED', 'INVALIDATED'))
);

create unique index uq_settlement_confirmation_role
    on settlement_confirmation(proposal_id, participant_role);
