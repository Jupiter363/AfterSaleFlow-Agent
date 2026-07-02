-- Room-based collaboration foundation.
--
-- Demo disputes are intentionally not inserted by Flyway. They are business
-- data and are loaded by a configuration-gated application seeder so an empty
-- production or integration-test database stays empty after schema migration.

alter table fulfillment_dispute_case
    add column source_type varchar(32) not null default 'INTAKE_CREATED',
    add column source_system varchar(64),
    add column external_case_ref varchar(128),
    add column current_room varchar(32),
    add column current_deadline_at timestamptz,
    add constraint ck_dispute_source_type
        check (source_type in ('EXTERNAL_IMPORT', 'INTAKE_CREATED')),
    add constraint ck_dispute_current_room
        check (
            current_room is null
            or current_room in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')
        );

create unique index uq_dispute_external_source
    on fulfillment_dispute_case(source_system, external_case_ref)
    where source_system is not null and external_case_ref is not null;

create table case_participant (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    actor_id varchar(128) not null,
    participant_role varchar(32) not null,
    participant_status varchar(32) not null default 'ACTIVE',
    joined_at timestamptz,
    invited_at timestamptz,
    left_at timestamptz,
    visibility_scope_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_case_participant_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_case_participant_actor_role
        unique (case_id, actor_id, participant_role),
    constraint ck_case_participant_role
        check (
            participant_role in (
                'USER',
                'MERCHANT',
                'CUSTOMER_SERVICE',
                'PLATFORM_REVIEWER',
                'ADMIN'
            )
        ),
    constraint ck_case_participant_status
        check (participant_status in ('INVITED', 'ACTIVE', 'LEFT'))
);

create index idx_case_participant_actor
    on case_participant(actor_id, participant_role, participant_status);
create index idx_case_participant_case
    on case_participant(case_id, participant_status);

create table case_room (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_status varchar(32) not null,
    opened_at timestamptz,
    sealed_at timestamptz,
    closed_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_case_room_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_case_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_case_room_status
        check (room_status in ('LOCKED', 'OPEN', 'WAITING', 'SEALED', 'CLOSED'))
);

create unique index uq_case_room_type on case_room(case_id, room_type);
create index idx_case_room_status on case_room(room_type, room_status);

create table room_message (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_id varchar(64) not null,
    sequence_no bigint not null,
    sender_type varchar(32) not null,
    sender_role varchar(64) not null,
    sender_id varchar(128) not null,
    audience_json jsonb not null default '[]'::jsonb,
    message_type varchar(64) not null,
    message_text text,
    attachment_refs_json jsonb not null default '[]'::jsonb,
    agent_run_id varchar(64),
    hearing_round integer,
    idempotency_key varchar(128) not null,
    created_at timestamptz not null default now(),
    trace_id varchar(128),
    created_by varchar(128) not null,
    constraint fk_room_message_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_room_message_room
        foreign key (room_id) references case_room(id),
    constraint fk_room_message_agent_run
        foreign key (agent_run_id) references agent_run(id),
    constraint uq_room_message_sequence unique (room_id, sequence_no),
    constraint uq_room_message_idempotency unique (case_id, idempotency_key),
    constraint ck_room_message_sender_type
        check (sender_type in ('PARTY', 'AGENT', 'SYSTEM', 'REVIEWER')),
    constraint ck_room_message_type
        check (
            message_type in (
                'PARTY_TEXT',
                'PARTY_EVIDENCE_REFERENCE',
                'PARTY_CONFIRMATION',
                'AGENT_MESSAGE',
                'SYSTEM_EVENT',
                'REVIEWER_NOTE'
            )
        ),
    constraint ck_room_message_round
        check (hearing_round is null or hearing_round between 1 and 3)
);

create index idx_room_message_case_sequence
    on room_message(case_id, sequence_no);
create index idx_room_message_room_created
    on room_message(room_id, created_at);

create table case_phase_clock (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_id varchar(64) not null,
    clock_type varchar(32) not null,
    clock_status varchar(32) not null,
    started_at timestamptz,
    deadline_at timestamptz not null,
    completed_at timestamptz,
    temporal_workflow_id varchar(128) not null,
    temporal_run_id varchar(128),
    completion_reason varchar(64),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_phase_clock_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_phase_clock_room
        foreign key (room_id) references case_room(id),
    constraint uq_phase_clock_case_type
        unique (case_id, clock_type),
    constraint ck_phase_clock_type
        check (clock_type in ('EVIDENCE_SUBMISSION', 'HEARING')),
    constraint ck_phase_clock_status
        check (
            clock_status in (
                'SCHEDULED',
                'RUNNING',
                'COMPLETED_EARLY',
                'EXPIRED',
                'CANCELLED'
            )
        ),
    constraint ck_phase_clock_deadline
        check (started_at is null or deadline_at > started_at)
);

create index idx_phase_clock_deadline
    on case_phase_clock(clock_status, deadline_at);
