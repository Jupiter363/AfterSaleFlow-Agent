-- P4-R1 engineering-only signed-synthetic comparison ledger (EXPAND_ONLY).
-- The table stores bounded hashes, references, classifications, and receipt metadata only.

alter table case_intake_epoch_party_authority
    add constraint uq_intake_shadow_comparison_party_authority
        unique (
            authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        );

alter table case_intake_command_authority
    add constraint uq_intake_shadow_comparison_command_authority
        unique (
            case_command_id, command_id, command_type, case_command_sequence, epoch_id,
            party_authority_id, access_session_id, registration_id,
            tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id,
            request_hash, accepted_room_revision
        );

create table case_intake_shadow_comparison (
    comparison_key_hash varchar(64) primary key,
    epoch_id varchar(64) not null,
    party_authority_id varchar(128) not null,
    party varchar(16) not null,
    case_command_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    access_session_id varchar(64) not null,
    registration_id varchar(128) not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    agent_session_id varchar(64) not null,
    actor_scope_hash varchar(64) not null,
    command_id varchar(128) not null,
    command_type varchar(64) not null,
    case_command_sequence bigint not null,
    accepted_room_revision bigint not null,
    operation_key varchar(512) not null unique,
    request_hash varchar(64) not null,
    result_hash varchar(64) not null,
    proposal_hash varchar(64) not null,
    comparison_hash varchar(64) not null,
    verdict varchar(16) not null,
    projected_event_type varchar(64) not null,
    comparison_payload jsonb not null,
    receipt_payload jsonb not null,
    recorded_at timestamptz not null,
    constraint fk_intake_shadow_comparison_selection
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ) references case_intake_epoch_selection_binding(
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint fk_intake_shadow_comparison_epoch
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ) references case_room_epoch(
            id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint fk_intake_shadow_comparison_party
        foreign key (
            party_authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ) references case_intake_epoch_party_authority(
            authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ),
    constraint fk_intake_shadow_comparison_command
        foreign key (
            case_command_id, command_id, command_type, case_command_sequence, epoch_id,
            party_authority_id, access_session_id, registration_id,
            tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id,
            request_hash, accepted_room_revision
        ) references case_intake_command_authority(
            case_command_id, command_id, command_type, case_command_sequence, epoch_id,
            party_authority_id, access_session_id, registration_id,
            tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id,
            request_hash, accepted_room_revision
        ),
    constraint ck_intake_shadow_comparison_route
        check (
            room_type = 'INTAKE'
            and command_type = 'INTAKE_MESSAGE'
            and room_epoch >= 0
            and fencing_token > 0
            and case_command_sequence > 0
            and accepted_room_revision >= 0
        ),
    constraint ck_intake_shadow_comparison_party
        check (party in ('INITIATOR', 'RESPONDENT')),
    constraint ck_intake_shadow_comparison_hashes
        check (
            comparison_key_hash ~ '^[0-9a-f]{64}$'
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and result_hash ~ '^[0-9a-f]{64}$'
            and proposal_hash ~ '^[0-9a-f]{64}$'
            and comparison_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_intake_shadow_comparison_verdict
        check (verdict in ('MATCH', 'DIFFERENT', 'HARD_FAILURE')),
    constraint ck_intake_shadow_comparison_event
        check (projected_event_type in ('TURN_NEEDS_INPUT', 'TURN_READY_TO_CONFIRM')),
    constraint ck_intake_shadow_comparison_payloads
        check (
            jsonb_typeof(comparison_payload) = 'object'
            and jsonb_typeof(receipt_payload) = 'object'
            and octet_length(comparison_payload::text) <= 32768
            and octet_length(receipt_payload::text) <= 32768
        )
);

create index idx_intake_shadow_comparison_epoch
    on case_intake_shadow_comparison (
        tenant_surrogate, case_id, room_epoch, fencing_token, recorded_at
    );

create index idx_intake_shadow_comparison_command
    on case_intake_shadow_comparison (command_id, request_hash);

create trigger trg_intake_shadow_comparison_immutable
    before update or delete on case_intake_shadow_comparison
    for each row execute function reject_r15_authority_mutation();

create trigger trg_intake_shadow_comparison_no_truncate
    before truncate on case_intake_shadow_comparison
    for each statement execute function reject_r15_authority_mutation();
