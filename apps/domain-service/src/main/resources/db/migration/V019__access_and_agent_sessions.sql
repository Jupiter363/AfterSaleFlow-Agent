create table case_access_session (
    id varchar(64) primary key,
    tenant_id varchar(64) not null default 'default',
    case_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(64) not null,
    permission_level varchar(64) not null,
    permission_scopes_json jsonb not null default '[]'::jsonb,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_case_access_session_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_case_access_session_actor_role
        check (actor_role in ('USER', 'MERCHANT', 'CUSTOMER_SERVICE', 'PLATFORM_REVIEWER', 'ADMIN', 'SYSTEM')),
    constraint ck_case_access_session_permission_level
        check (permission_level in ('PARTY_USER', 'PARTY_MERCHANT', 'SERVICE_ASSIST', 'REVIEWER_ALL', 'ADMIN_ALL', 'SYSTEM_ALL'))
);

create unique index uq_case_access_session_scope
    on case_access_session(tenant_id, case_id, actor_id, actor_role, permission_level);

create index idx_case_access_session_case_actor
    on case_access_session(case_id, actor_id, actor_role);

create table agent_conversation_session (
    id varchar(64) primary key,
    tenant_id varchar(64) not null default 'default',
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    actor_id varchar(128) not null,
    actor_role varchar(64) not null,
    agent_key varchar(128) not null,
    access_session_id varchar(64) not null,
    prompt_profile_id varchar(128) not null,
    memory_policy_id varchar(128) not null,
    conversation_scope varchar(512) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_agent_conversation_session_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_agent_conversation_session_access
        foreign key (access_session_id) references case_access_session(id),
    constraint ck_agent_conversation_session_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_agent_conversation_session_actor_role
        check (actor_role in ('USER', 'MERCHANT', 'CUSTOMER_SERVICE', 'PLATFORM_REVIEWER', 'ADMIN', 'SYSTEM'))
);

create unique index uq_agent_conversation_session_scope
    on agent_conversation_session(tenant_id, case_id, room_type, actor_id, actor_role, agent_key, prompt_profile_id);

create unique index uq_agent_conversation_session_conversation_scope
    on agent_conversation_session(conversation_scope);

create index idx_agent_conversation_session_access
    on agent_conversation_session(access_session_id);

create table agent_session_dossier (
    id varchar(64) primary key,
    agent_session_id varchar(64) not null unique,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    actor_id varchar(128) not null,
    actor_role varchar(64) not null,
    agent_key varchar(128) not null,
    dossier_json jsonb not null default '{}'::jsonb,
    quality_score integer not null default 0,
    ready_for_next_step boolean not null default false,
    recommendation varchar(64),
    source_turn_no integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_agent_session_dossier_agent_session
        foreign key (agent_session_id) references agent_conversation_session(id),
    constraint fk_agent_session_dossier_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_agent_session_dossier_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_agent_session_dossier_actor_role
        check (actor_role in ('USER', 'MERCHANT', 'CUSTOMER_SERVICE', 'PLATFORM_REVIEWER', 'ADMIN', 'SYSTEM'))
);

create index idx_agent_session_dossier_case_room_actor
    on agent_session_dossier(case_id, room_type, actor_id, actor_role);

alter table room_turn_memory
    add column agent_session_id varchar(64),
    add column access_session_id varchar(64),
    add column conversation_scope varchar(512),
    add column session_actor_id varchar(128),
    add column session_actor_role varchar(64),
    add column prompt_profile_id varchar(128),
    add column memory_policy_snapshot_json jsonb not null default '{}'::jsonb;

alter table room_turn_memory
    add constraint fk_room_turn_memory_agent_session
        foreign key (agent_session_id) references agent_conversation_session(id),
    add constraint fk_room_turn_memory_access_session
        foreign key (access_session_id) references case_access_session(id);

create index idx_room_turn_memory_session_turn
    on room_turn_memory(agent_session_id, turn_no desc);

create index idx_room_turn_memory_scope_turn
    on room_turn_memory(conversation_scope, turn_no desc);

alter table case_intake_dossier
    add column source_agent_session_id varchar(64),
    add column source_actor_id varchar(128),
    add column source_actor_role varchar(64);

alter table case_intake_dossier
    add constraint fk_case_intake_dossier_source_agent_session
        foreign key (source_agent_session_id) references agent_conversation_session(id);
