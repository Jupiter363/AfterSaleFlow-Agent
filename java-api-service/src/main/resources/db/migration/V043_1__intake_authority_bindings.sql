-- P4-R1.5 authority binding contract (EXPAND_ONLY).
-- Runtime remains DISABLED or SIGNED_SYNTHETIC_SHADOW; this migration only
-- persists immutable server-owned authority tuples and their database proofs.

-- V043_1 candidate keys used by the new composite foreign keys.  Existing
-- V001-V043 objects are left intact; these are additive constraints only.
alter table case_room_epoch
    add constraint uq_r15_case_room_epoch_selection
        unique (id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token);

alter table case_intake_graph_thread_binding
    add constraint uq_r15_graph_thread_authority
        unique (
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_id, actor_role, audience,
            actor_scope_hash, agent_session_id, registration_hash
        );

alter table case_access_session
    add constraint uq_r15_access_session_authority
        unique (id, tenant_id, case_id, actor_id, actor_role, permission_level);

alter table agent_conversation_session
    add constraint uq_r15_agent_session_authority
        unique (
            id, tenant_id, case_id, room_type, access_session_id, actor_id,
            actor_role, agent_key, prompt_profile_id, memory_policy_id
        );

alter table case_intake_snapshot_binding
    add constraint uq_r15_snapshot_event_route
        unique (binding_id, thread_registration_id);

alter table case_command
    add constraint uq_r15_case_command_authority
        unique (id, tenant_surrogate, case_id, command_id, request_hash);

create table case_intake_epoch_selection_binding (
    epoch_id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    selection_hash varchar(64) not null,
    writer_mode varchar(16) not null,
    case_workflow_type varchar(128) not null,
    case_workflow_build_id varchar(128) not null,
    room_workflow_type varchar(128) not null,
    room_workflow_build_id varchar(128) not null,
    process_contract_version varchar(128) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    state_schema_version varchar(128) not null,
    stream_protocol varchar(128) not null,
    prompt_version varchar(128) not null,
    model_profile_id varchar(128) not null,
    output_schema_version varchar(128) not null,
    policy_version varchar(128) not null,
    guardrail_version varchar(128) not null,
    tool_policy_version varchar(128) not null,
    cohort_policy_version varchar(128) not null,
    agent_key varchar(128) not null,
    agent_session_profile_version varchar(128) not null,
    memory_policy_id varchar(128) not null,
    created_at timestamptz not null default current_timestamp,
    constraint uq_r15_selection_authority
        unique (epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token),
    constraint fk_r15_selection_epoch
        foreign key (epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token)
        references case_room_epoch(
            id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint ck_r15_selection_identity
        check (
            epoch_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and length(btrim(case_workflow_type)) between 1 and 128
            and length(btrim(case_workflow_build_id)) between 1 and 128
            and length(btrim(room_workflow_build_id)) between 1 and 128
            and length(btrim(process_contract_version)) between 1 and 128
            and length(btrim(graph_version)) between 1 and 128
            and length(btrim(checkpoint_schema_version)) between 1 and 128
            and length(btrim(stream_protocol)) between 1 and 128
            and length(btrim(prompt_version)) between 1 and 128
            and length(btrim(model_profile_id)) between 1 and 128
            and length(btrim(policy_version)) between 1 and 128
            and length(btrim(guardrail_version)) between 1 and 128
            and length(btrim(tool_policy_version)) between 1 and 128
            and length(btrim(cohort_policy_version)) between 1 and 128
        ),
    constraint ck_r15_selection_constants
        check (
            room_type = 'INTAKE'
            and writer_mode = 'SHADOW'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and graph_key = 'intake.v2'
            and state_schema_version = 'intake-graph-state.v2'
            and output_schema_version = 'intake-turn-proposal.v2'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and agent_session_profile_version = 'agent-session-profile.v1'
            and memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
        ),
    constraint ck_r15_selection_epoch
        check (room_epoch >= 0 and fencing_token > 0),
    constraint ck_r15_selection_hash
        check (selection_hash ~ '^[0-9a-f]{64}$')
);

create table case_intake_epoch_party_authority (
    authority_id varchar(128) primary key,
    epoch_id varchar(64) not null,
    party varchar(16) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    session_tenant_id varchar(64) not null,
    session_case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    registration_id varchar(128) not null,
    registration_hash varchar(64) not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    audience varchar(32) not null,
    actor_scope_hash varchar(64) not null,
    access_session_id varchar(64) not null,
    permission_level varchar(64) not null,
    agent_session_id varchar(64) not null,
    agent_key varchar(128) not null,
    prompt_version varchar(128) not null,
    agent_session_profile_version varchar(128) not null,
    prompt_profile_id varchar(128) not null,
    memory_policy_id varchar(128) not null,
    created_at timestamptz not null default current_timestamp,
    constraint uq_r15_party_epoch unique (epoch_id, party),
    constraint uq_r15_party_authority_route unique (
        authority_id, epoch_id, tenant_surrogate, case_id, room_type, room_epoch,
        fencing_token, access_session_id, registration_id, thread_id, actor_id,
        actor_role, actor_scope_hash, agent_session_id
    ),
    constraint fk_r15_party_selection
        foreign key (epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token)
        references case_intake_epoch_selection_binding(
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ),
    constraint fk_r15_party_graph_registration
        foreign key (
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_id, actor_role, audience,
            actor_scope_hash, agent_session_id, registration_hash
        ) references case_intake_graph_thread_binding(
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_id, actor_role, audience,
            actor_scope_hash, agent_session_id, registration_hash
        ),
    constraint fk_r15_party_access_session
        foreign key (
            access_session_id, session_tenant_id, session_case_id, actor_id,
            actor_role, permission_level
        ) references case_access_session(
            id, tenant_id, case_id, actor_id, actor_role, permission_level
        ),
    constraint fk_r15_party_agent_session
        foreign key (
            agent_session_id, session_tenant_id, session_case_id, room_type,
            access_session_id, actor_id, actor_role, agent_key, prompt_profile_id,
            memory_policy_id
        ) references agent_conversation_session(
            id, tenant_id, case_id, room_type, access_session_id, actor_id,
            actor_role, agent_key, prompt_profile_id, memory_policy_id
        ),
    constraint ck_r15_party_identity
        check (
            authority_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and epoch_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and session_tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and session_case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and registration_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and access_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and agent_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        ),
    constraint ck_r15_party_scope
        check (
            party in ('INITIATOR', 'RESPONDENT')
            and room_type = 'INTAKE'
            and session_tenant_id = tenant_surrogate
            and session_case_id = case_id
            and actor_role = audience
        ),
    constraint ck_r15_party_permission
        check (
            (actor_role = 'USER' and permission_level = 'PARTY_USER')
            or (actor_role = 'MERCHANT' and permission_level = 'PARTY_MERCHANT')
        ),
    constraint ck_r15_party_profile
        check (
            agent_key = 'DISPUTE_INTAKE_OFFICER'
            and agent_session_profile_version = 'agent-session-profile.v1'
            and prompt_profile_id ~ '^asp[.]v1[.][0-9a-f]{64}$'
            and memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
        ),
    constraint ck_r15_party_epoch
        check (room_epoch >= 0 and fencing_token > 0),
    constraint ck_r15_party_hashes
        check (registration_hash ~ '^[0-9a-f]{64}$' and actor_scope_hash ~ '^[0-9a-f]{64}$')
);

create table case_intake_command_payload_authority (
    payload_authority_id varchar(128) primary key,
    command_id varchar(128) not null,
    epoch_id varchar(64) not null,
    party_authority_id varchar(128) not null,
    access_session_id varchar(64) not null,
    registration_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(64) not null,
    source_kind varchar(32) not null,
    existing_event_binding_id varchar(128),
    artifact_id varchar(128) not null,
    schema_version varchar(128) not null,
    object_uri varchar(1024) not null,
    object_version varchar(128) not null,
    content_sha256 varchar(64) not null,
    size_bytes bigint not null,
    put_receipt_schema_version varchar(128),
    put_receipt_id varchar(128),
    put_idempotency_key varchar(72),
    put_receipt_stored_at_epoch_micros bigint,
    put_receipt_hash varchar(64),
    created_at timestamptz not null default current_timestamp,
    constraint uq_r15_payload_artifact unique (tenant_surrogate, artifact_id),
    constraint uq_r15_payload_command_route unique (
        payload_authority_id, epoch_id, party_authority_id, access_session_id,
        registration_id, tenant_surrogate, case_id, room_type, room_epoch,
        fencing_token, thread_id, actor_scope_hash, agent_session_id, command_id
    ),
    constraint fk_r15_payload_party
        foreign key (
            party_authority_id, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ) references case_intake_epoch_party_authority(
            authority_id, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ),
    constraint fk_r15_payload_event
        foreign key (existing_event_binding_id, registration_id)
        references case_intake_snapshot_binding(binding_id, thread_registration_id),
    constraint ck_r15_payload_identity
        check (
            payload_authority_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and command_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and epoch_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and party_authority_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and registration_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and access_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and agent_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
            and artifact_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_r15_payload_common
        check (
            room_type = 'INTAKE'
            and room_epoch >= 0
            and fencing_token > 0
            and content_sha256 ~ '^[0-9a-f]{64}$'
            and object_uri ~ '^(s3|minio|urn):'
            and length(btrim(object_version)) between 1 and 128
            and size_bytes > 0
        ),
    constraint ck_r15_payload_source_shape
        check (
            (
                source_kind = 'EXISTING_PRIVATE_EVENT'
                and schema_version = 'intake-turn-event.v2'
                and size_bytes between 1 and 32768
                and existing_event_binding_id is not null
                and put_receipt_schema_version is null
                and put_receipt_id is null
                and put_idempotency_key is null
                and put_receipt_stored_at_epoch_micros is null
                and put_receipt_hash is null
            )
            or
            (
                source_kind = 'SERVER_MINTED_HUMAN_INPUT'
                and schema_version = 'intake-human-input-command.v1'
                and size_bytes between 1 and 32768
                and existing_event_binding_id is null
                and put_receipt_schema_version = 'intake-command-payload-put-receipt.v1'
                and put_receipt_id is not null
                and put_idempotency_key ~ '^iput[.]v1[.][0-9a-f]{64}$'
                and put_receipt_stored_at_epoch_micros between 0 and 9007199254740991
                and put_receipt_hash ~ '^[0-9a-f]{64}$'
            )
            or
            (
                source_kind = 'SERVER_CANONICAL_BRANCH'
                and schema_version = 'intake-branch-command.v1'
                and size_bytes between 1 and 16384
                and existing_event_binding_id is null
                and put_receipt_schema_version = 'intake-command-payload-put-receipt.v1'
                and put_receipt_id is not null
                and put_idempotency_key ~ '^iput[.]v1[.][0-9a-f]{64}$'
                and put_receipt_stored_at_epoch_micros between 0 and 9007199254740991
                and put_receipt_hash ~ '^[0-9a-f]{64}$'
            )
        )
);

create table case_intake_command_authority (
    case_command_id varchar(64) primary key,
    command_id varchar(128) not null,
    case_command_sequence bigint not null,
    command_type varchar(64) not null,
    epoch_id varchar(64) not null,
    party_authority_id varchar(128) not null,
    access_session_id varchar(64) not null,
    registration_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(64) not null,
    payload_authority_id varchar(128) not null,
    request_hash varchar(64) not null,
    accepted_room_revision bigint not null,
    execution_disposition varchar(32) not null,
    created_at timestamptz not null default current_timestamp,
    constraint uq_r15_command_tenant_command unique (tenant_surrogate, command_id),
    constraint uq_r15_command_snapshot unique (
        case_command_id, tenant_surrogate, case_id, command_id, request_hash
    ),
    constraint uq_r15_command_payload unique (payload_authority_id),
    constraint fk_r15_command_case_command
        foreign key (case_command_id, tenant_surrogate, case_id, command_id, request_hash)
        references case_command(id, tenant_surrogate, case_id, command_id, request_hash),
    constraint fk_r15_command_party
        foreign key (
            party_authority_id, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ) references case_intake_epoch_party_authority(
            authority_id, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id
        ),
    constraint fk_r15_command_payload
        foreign key (
            payload_authority_id, epoch_id, party_authority_id, access_session_id,
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_scope_hash, agent_session_id, command_id
        ) references case_intake_command_payload_authority(
            payload_authority_id, epoch_id, party_authority_id, access_session_id,
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_scope_hash, agent_session_id, command_id
        ),
    constraint ck_r15_command_scope
        check (
            command_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and command_type in ('INTAKE_MESSAGE', 'INTAKE_CONFIRM', 'INTAKE_CANCEL')
            and room_type = 'INTAKE'
            and room_epoch >= 0
            and fencing_token > 0
            and case_command_sequence > 0
            and accepted_room_revision >= 0
            and request_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_r15_command_disposition
        check (execution_disposition in ('INERT_EXTERNAL_EVENT', 'ACTIVITY_ORCHESTRATED'))
);

-- Compact lookup indexes deliberately exclude object_uri from every B-tree key.
create index idx_r15_party_epoch on case_intake_epoch_party_authority(epoch_id, party);
create index idx_r15_payload_command on case_intake_command_payload_authority(tenant_surrogate, command_id);
create index idx_r15_command_case_sequence on case_intake_command_authority(case_id, case_command_sequence);

-- All R1.5 rows are immutable authority snapshots.  Status changes belong to
-- the referenced V019/V043 rows and are checked by the owning transaction.
create or replace function reject_r15_authority_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using errcode = '23514',
        message = 'P4-R1.5 authority binding is append-only';
end
$$;

create trigger trg_r15_selection_immutable
    before update or delete on case_intake_epoch_selection_binding
    for each row execute function reject_r15_authority_mutation();
create trigger trg_r15_selection_no_truncate
    before truncate on case_intake_epoch_selection_binding
    for each statement execute function reject_r15_authority_mutation();
create trigger trg_r15_party_immutable
    before update or delete on case_intake_epoch_party_authority
    for each row execute function reject_r15_authority_mutation();
create trigger trg_r15_party_no_truncate
    before truncate on case_intake_epoch_party_authority
    for each statement execute function reject_r15_authority_mutation();
create trigger trg_r15_payload_immutable
    before update or delete on case_intake_command_payload_authority
    for each row execute function reject_r15_authority_mutation();
create trigger trg_r15_payload_no_truncate
    before truncate on case_intake_command_payload_authority
    for each statement execute function reject_r15_authority_mutation();
create trigger trg_r15_command_immutable
    before update or delete on case_intake_command_authority
    for each row execute function reject_r15_authority_mutation();
create trigger trg_r15_command_no_truncate
    before truncate on case_intake_command_authority
    for each statement execute function reject_r15_authority_mutation();

-- The epoch bootstrap cannot deliver an outbox item until both party rows are
-- present.  A deferred trigger makes the exact two-party proof transactional.
create or replace function assert_r15_exact_two_party_bootstrap()
returns trigger
language plpgsql
as $$
declare
    row_count integer;
    party_count integer;
begin
    select count(*), count(distinct party)
      into row_count, party_count
      from case_intake_epoch_party_authority
     where epoch_id = new.epoch_id;
    if row_count <> 2 or party_count <> 2
       or not exists (select 1 from case_intake_epoch_party_authority
                      where epoch_id = new.epoch_id and party = 'INITIATOR')
       or not exists (select 1 from case_intake_epoch_party_authority
                      where epoch_id = new.epoch_id and party = 'RESPONDENT') then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 epoch bootstrap requires exactly INITIATOR and RESPONDENT';
    end if;
    return new;
end
$$;

create constraint trigger trg_r15_exact_two_party_bootstrap
    after insert on case_intake_epoch_party_authority
    deferrable initially deferred
    for each row execute function assert_r15_exact_two_party_bootstrap();

-- Lock/status proof used by epoch binding, command acceptance, and start reads.
-- Every acceptance reader takes FOR SHARE locks in this fixed order:
-- case_access_session, agent_conversation_session,
-- case_intake_graph_thread_binding. Revocation writers take FOR UPDATE in the
-- same order and retain locks until commit (intake-authority-revocation-lock.v1).
create or replace function enforce_r15_live_status()
returns trigger
language plpgsql
as $$
declare
    access_status varchar(32);
    agent_status varchar(32);
    registration_status varchar(24);
begin
    select status into access_status
      from case_access_session where id = new.access_session_id for share;
    select status into agent_status
      from agent_conversation_session where id = new.agent_session_id for share;
    select registration_status into registration_status
      from case_intake_graph_thread_binding where registration_id = new.registration_id for share;
    if access_status is distinct from 'ACTIVE'
       or agent_status is distinct from 'ACTIVE'
       or registration_status is distinct from 'REGISTERED' then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 authority requires ACTIVE access/agent and REGISTERED graph binding';
    end if;
    return new;
end
$$;

create trigger trg_r15_party_live_status
    before insert on case_intake_epoch_party_authority
    for each row execute function enforce_r15_live_status();
create trigger trg_r15_payload_live_status
    before insert on case_intake_command_payload_authority
    for each row execute function enforce_r15_live_status();
create trigger trg_r15_command_live_status
    before insert on case_intake_command_authority
    for each row execute function enforce_r15_live_status();

-- Existing private events are accepted only when every route and artifact
-- column equals the immutable V043 event row selected by the compact FK.
create or replace function enforce_r15_existing_private_event_binding()
returns trigger
language plpgsql
as $$
declare
    event_row record;
begin
    if new.source_kind <> 'EXISTING_PRIVATE_EVENT' then
        return new;
    end if;
    select * into event_row
      from case_intake_snapshot_binding
     where binding_id = new.existing_event_binding_id
       and thread_registration_id = new.registration_id
       and binding_type = 'EVENT'
       and schema_version = 'intake-turn-event.v2'
       and room_type = 'INTAKE'
       and visibility = 'PRIVATE'
       and initialization_marker = false;
    if not found
       or event_row.tenant_surrogate is distinct from new.tenant_surrogate
       or event_row.case_id is distinct from new.case_id
       or event_row.room_type is distinct from new.room_type
       or event_row.room_epoch is distinct from new.room_epoch
       or event_row.fencing_token is distinct from new.fencing_token
       or event_row.thread_id is distinct from new.thread_id
       or event_row.actor_scope_hash is distinct from new.actor_scope_hash
       or event_row.agent_session_id is distinct from new.agent_session_id
       or event_row.actor_audience is distinct from new.actor_role
       or event_row.schema_version is distinct from new.schema_version
       or event_row.artifact_id is distinct from new.artifact_id
       or event_row.object_uri is distinct from new.object_uri
       or event_row.object_version is distinct from new.object_version
       or event_row.content_sha256 is distinct from new.content_sha256
       or event_row.size_bytes is distinct from new.size_bytes then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 existing private event route or artifact mismatch';
    end if;
    return new;
end
$$;

create constraint trigger trg_r15_existing_private_event_assertion
    after insert on case_intake_command_payload_authority
    deferrable initially immediate
    for each row execute function enforce_r15_existing_private_event_binding();

-- Server-minted and canonical-branch payloads carry an immutable provenance
-- receipt.  The Java put service computes receipt_hash as SHA_256 of the
-- RFC_8785 UTF-8 authority snapshot; the database keeps the complete snapshot
-- and rejects a partial receipt or a mismatched idempotency-key shape.
create or replace function enforce_r15_put_receipt_binding()
returns trigger
language plpgsql
as $$
begin
    if new.source_kind in ('SERVER_MINTED_HUMAN_INPUT', 'SERVER_CANONICAL_BRANCH')
       and (new.put_receipt_schema_version is null
            or new.put_receipt_id is null
            or new.put_idempotency_key is null
            or new.put_receipt_stored_at_epoch_micros is null
            or new.put_receipt_hash is null) then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 server payload requires complete immutable put receipt';
    end if;
    if new.source_kind = 'EXISTING_PRIVATE_EVENT'
       and (new.put_receipt_schema_version is not null
            or new.put_receipt_id is not null
            or new.put_idempotency_key is not null
            or new.put_receipt_stored_at_epoch_micros is not null
            or new.put_receipt_hash is not null) then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 existing event cannot claim a put receipt';
    end if;
    return new;
end
$$;

create constraint trigger trg_r15_put_receipt_binding
    after insert on case_intake_command_payload_authority
    deferrable initially immediate
    for each row execute function enforce_r15_put_receipt_binding();

-- Command authority is an immutable four-field case-command binding.  The
-- composite FK proves identity; this trigger also proves the payload reference
-- fields are exactly the server-owned payload snapshot in the same transaction.
create or replace function enforce_r15_command_exact_comparison()
returns trigger
language plpgsql
as $$
declare
    command_row record;
    payload_row record;
begin
    select * into command_row from case_command
     where id = new.case_command_id
       and tenant_surrogate = new.tenant_surrogate
       and case_id = new.case_id
       and command_id = new.command_id
       and request_hash = new.request_hash;
    select * into payload_row from case_intake_command_payload_authority
     where payload_authority_id = new.payload_authority_id;
    if not found
       or command_row.case_command_sequence is distinct from new.case_command_sequence
       or command_row.command_type is distinct from new.command_type
       or command_row.room_type is distinct from new.room_type
       or command_row.room_epoch is distinct from new.room_epoch
       or command_row.actor_id is distinct from new.actor_id
       or command_row.actor_role is distinct from new.actor_role
       or command_row.payload_schema_version is distinct from payload_row.schema_version
       or command_row.payload_uri is distinct from payload_row.object_uri
       or command_row.payload_sha256 is distinct from payload_row.content_sha256
       or command_row.payload_size_bytes is distinct from payload_row.size_bytes then
        raise exception using errcode = '23514',
            message = 'P4-R1.5 command authority exact comparison failed';
    end if;
    return new;
end
$$;

create constraint trigger trg_r15_command_exact_comparison
    after insert on case_intake_command_authority
    deferrable initially immediate
    for each row execute function enforce_r15_command_exact_comparison();

-- V043_1 order proof (predecessor V043__intake_graph_bindings.sql; V044 and
-- V045 remain reserved): predecessor objects and the V043 graph candidate key
-- must exist before any R1.5 authority row can be created.
do $$
begin
    if to_regclass('case_room_epoch') is null
       or to_regclass('case_intake_graph_thread_binding') is null
       or to_regclass('case_intake_snapshot_binding') is null
       or to_regclass('case_access_session') is null
       or to_regclass('agent_conversation_session') is null
       or to_regclass('case_command') is null then
        raise exception using errcode = '42P01',
            message = 'V043_1 requires V019, V039 and V043 predecessor objects';
    end if;
end
$$;
