-- P4-R1 engineering-only signed-synthetic comparison ledger (EXPAND_ONLY).
-- The table stores bounded hashes, references, classifications, and receipt metadata only.

create table case_intake_shadow_comparison (
    comparison_key_hash varchar(64) primary key,
    epoch_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    agent_session_id varchar(64) not null,
    actor_scope_hash varchar(64) not null,
    command_id varchar(128) not null,
    operation_key varchar(512) not null unique,
    request_hash varchar(64) not null,
    result_hash varchar(64) not null,
    proposal_hash varchar(64) not null,
    comparison_hash varchar(64) not null,
    verdict varchar(16) not null,
    comparison_payload jsonb not null,
    receipt_payload jsonb not null,
    recorded_at timestamptz not null,
    constraint fk_intake_shadow_comparison_selection
        foreign key (epoch_id)
        references case_intake_epoch_selection_binding (epoch_id),
    constraint ck_intake_shadow_comparison_epoch
        check (room_epoch >= 0 and fencing_token > 0),
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
