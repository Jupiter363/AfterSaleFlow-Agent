-- P4 signed-synthetic admission receipts (EXPAND_ONLY).
-- This table persists verified claims and hashes only. The compact JWS is never durable data.

alter table case_intake_epoch_selection_binding
    add constraint uq_intake_synthetic_admission_selection
        unique (
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, selection_hash, writer_mode
        );

alter table case_intake_epoch_party_authority
    add constraint uq_intake_synthetic_admission_party
        unique (
            authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            registration_hash, thread_id, actor_id, actor_role, actor_scope_hash,
            agent_session_id
        );

create table case_intake_synthetic_activity_admission (
    receipt_id varchar(72) primary key,
    schema_version varchar(128) not null,
    admission_status varchar(32) not null,
    authorization_hash varchar(64) not null unique,
    envelope_hash varchar(64) not null unique,
    token_algorithm varchar(16) not null,
    token_type varchar(64) not null,
    signing_key_id varchar(128) not null,
    jwt_id varchar(128) not null,
    claims_schema_version varchar(128) not null,
    issuer varchar(128) not null,
    audience varchar(128) not null,
    subject varchar(128) not null,
    issued_at_epoch_seconds bigint not null,
    not_before_epoch_seconds bigint not null,
    expires_at_epoch_seconds bigint not null,
    traffic_source varchar(64) not null,
    epoch_id varchar(64) not null,
    party_authority_id varchar(128) not null,
    case_command_id varchar(64) not null,
    payload_authority_id varchar(128) not null,
    access_session_id varchar(64) not null,
    registration_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    writer_mode varchar(16) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(64) not null,
    command_id varchar(128) not null,
    command_sequence bigint not null,
    command_type varchar(64) not null,
    party varchar(16) not null,
    accepted_room_revision bigint not null,
    payload_ref varchar(1024) not null,
    payload_hash varchar(64) not null,
    command_operation_key varchar(512) not null,
    request_hash varchar(64) not null,
    process_revision bigint not null,
    room_revision bigint not null,
    deadline_epoch_millis bigint not null,
    retry_provider_attempts integer not null,
    retry_activity_attempts integer not null,
    retry_repairs integer not null,
    logical_run_id varchar(128) not null,
    attempt_id varchar(128) not null,
    selection_hash varchar(64) not null,
    registration_hash varchar(64) not null,
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
    pinned_versions jsonb not null,
    parity_baseline_ref varchar(1024) not null,
    parity_baseline_hash varchar(64) not null,
    admitted_at timestamptz not null default current_timestamp,
    constraint uq_intake_synthetic_admission_kid_jti
        unique (signing_key_id, jwt_id),
    constraint fk_intake_synthetic_admission_selection
        foreign key (
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, selection_hash, writer_mode
        ) references case_intake_epoch_selection_binding(
            epoch_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, selection_hash, writer_mode
        ),
    constraint fk_intake_synthetic_admission_party
        foreign key (
            party_authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            registration_hash, thread_id, actor_id, actor_role, actor_scope_hash,
            agent_session_id
        ) references case_intake_epoch_party_authority(
            authority_id, party, epoch_id, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, access_session_id, registration_id,
            registration_hash, thread_id, actor_id, actor_role, actor_scope_hash,
            agent_session_id
        ),
    constraint fk_intake_synthetic_admission_payload
        foreign key (
            payload_authority_id, epoch_id, party_authority_id, access_session_id,
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_scope_hash, agent_session_id, command_id
        ) references case_intake_command_payload_authority(
            payload_authority_id, epoch_id, party_authority_id, access_session_id,
            registration_id, tenant_surrogate, case_id, room_type, room_epoch,
            fencing_token, thread_id, actor_scope_hash, agent_session_id, command_id
        ),
    constraint fk_intake_synthetic_admission_command
        foreign key (
            case_command_id, command_id, command_type, command_sequence, epoch_id,
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
    constraint ck_intake_synthetic_admission_header
        check (
            token_algorithm = 'ES256'
            and token_type = 'intake-synthetic-admission+jwt'
            and signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and jwt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_intake_synthetic_admission_identity
        check (
            schema_version = 'intake-synthetic-activity-admission.v1'
            and admission_status = 'VERIFIED'
            and claims_schema_version = 'intake-synthetic-admission-claims.v1'
            and issuer = 'after-sale-flow.synthetic-driver'
            and audience = 'after-sale-flow.java-intake-admission'
            and subject = 'signed-synthetic-intake-shadow'
            and traffic_source = 'AUTHENTICATED_SIGNED_SYNTHETIC'
            and room_type = 'INTAKE'
            and writer_mode = 'SHADOW'
            and command_type = 'INTAKE_MESSAGE'
            and party in ('INITIATOR', 'RESPONDENT')
        ),
    constraint ck_intake_synthetic_admission_time
        check (
            issued_at_epoch_seconds >= 0
            and not_before_epoch_seconds >= issued_at_epoch_seconds
            and expires_at_epoch_seconds > not_before_epoch_seconds
            and expires_at_epoch_seconds - issued_at_epoch_seconds <= 60
            and deadline_epoch_millis > 0
        ),
    constraint ck_intake_synthetic_admission_route
        check (
            room_epoch >= 0 and fencing_token > 0 and command_sequence > 0
            and process_revision >= 0
            and room_revision >= 0
            and accepted_room_revision = room_revision
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and payload_ref ~ '^(s3|minio|urn):'
            and parity_baseline_ref ~ '^(s3|minio|urn):'
            and command_operation_key = 'intake.operation:' || case_id || ':' || command_id
        ),
    constraint ck_intake_synthetic_admission_hashes
        check (
            authorization_hash ~ '^[0-9a-f]{64}$'
            and envelope_hash ~ '^[0-9a-f]{64}$'
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
            and payload_hash ~ '^[0-9a-f]{64}$'
            and request_hash ~ '^[0-9a-f]{64}$'
            and selection_hash ~ '^[0-9a-f]{64}$'
            and registration_hash ~ '^[0-9a-f]{64}$'
            and parity_baseline_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_intake_synthetic_admission_retry
        check (
            retry_provider_attempts between 0 and 2
            and retry_activity_attempts between 0 and 3
            and retry_repairs between 0 and 1
        ),
    constraint ck_intake_synthetic_admission_pins
        check (
            room_workflow_type = 'IntakeRoomWorkflow'
            and graph_key = 'intake.v2'
            and state_schema_version = 'intake-graph-state.v2'
            and output_schema_version = 'intake-turn-proposal.v2'
            and tool_policy_version = 'no-tools.v1'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and agent_session_profile_version = 'agent-session-profile.v1'
            and memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
            and jsonb_typeof(pinned_versions) = 'object'
            and octet_length(pinned_versions::text) <= 8192
            and pinned_versions = jsonb_build_object(
                'case_workflow_type', case_workflow_type,
                'case_workflow_build_id', case_workflow_build_id,
                'room_workflow_type', room_workflow_type,
                'room_workflow_build_id', room_workflow_build_id,
                'process_contract_version', process_contract_version,
                'graph_key', graph_key,
                'graph_version', graph_version,
                'checkpoint_schema_version', checkpoint_schema_version,
                'state_schema_version', state_schema_version,
                'stream_protocol', stream_protocol,
                'prompt_version', prompt_version,
                'model_profile_id', model_profile_id,
                'output_schema_version', output_schema_version,
                'policy_version', policy_version,
                'guardrail_version', guardrail_version,
                'tool_policy_version', tool_policy_version,
                'cohort_policy_version', cohort_policy_version,
                'agent_key', agent_key,
                'agent_session_profile_version', agent_session_profile_version,
                'memory_policy_id', memory_policy_id
            )
        )
);

create or replace function enforce_intake_synthetic_admission_payload_exact()
returns trigger
language plpgsql
as $$
declare
    payload_row record;
begin
    select object_uri, content_sha256, source_kind, schema_version
      into payload_row
      from case_intake_command_payload_authority
     where payload_authority_id = new.payload_authority_id;

    if not found
       or payload_row.object_uri is distinct from new.payload_ref
       or payload_row.content_sha256 is distinct from new.payload_hash
       or payload_row.source_kind is distinct from 'EXISTING_PRIVATE_EVENT'
       or payload_row.schema_version is distinct from 'intake-turn-event.v2'
    then
        raise exception using
            errcode = '23514',
            message = 'synthetic admission payload authority exact comparison failed';
    end if;

    return new;
end;
$$;

create index idx_intake_synthetic_admission_activity
    on case_intake_synthetic_activity_admission (
        tenant_surrogate, case_id, room_epoch, fencing_token, command_id,
        command_sequence, request_hash, expires_at_epoch_seconds
    );

create index idx_intake_synthetic_admission_authority
    on case_intake_synthetic_activity_admission (
        epoch_id, party_authority_id, case_command_id, registration_id
    );

create constraint trigger trg_intake_synthetic_admission_payload_exact
    after insert on case_intake_synthetic_activity_admission
    deferrable initially immediate
    for each row execute function enforce_intake_synthetic_admission_payload_exact();

create trigger trg_intake_synthetic_admission_immutable
    before update or delete on case_intake_synthetic_activity_admission
    for each row execute function reject_r15_authority_mutation();

create trigger trg_intake_synthetic_admission_no_truncate
    before truncate on case_intake_synthetic_activity_admission
    for each statement execute function reject_r15_authority_mutation();
