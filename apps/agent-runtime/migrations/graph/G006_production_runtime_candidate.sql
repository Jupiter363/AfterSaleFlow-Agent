-- Isolated production-runtime candidate activation and immutable execution-lane bindings.

alter table agent_graph_version_registry
    drop constraint ck_agent_graph_registry_state;

alter table agent_graph_version_registry
    add constraint ck_agent_graph_registry_state
    check (registry_state in ('DISABLED', 'SHADOW', 'ACTIVE_CANDIDATE', 'RETIRED'));

alter table agent_graph_version_registry
    drop constraint ck_agent_graph_registry_times;

alter table agent_graph_version_registry
    add constraint ck_agent_graph_registry_times
    check (
        (
            registry_state in ('SHADOW', 'ACTIVE_CANDIDATE')
            and loadable
            and activated_at is not null
            and retired_at is null
        )
        or (registry_state = 'RETIRED' and retired_at is not null and loadable)
        or (registry_state = 'DISABLED' and retired_at is null)
    );

alter table agent_graph_command
    add column activation_id varchar(64);

alter table agent_graph_command
    add column room_fencing_token bigint;

alter table agent_graph_command
    add column command_hash varchar(64);

alter table agent_graph_command
    add column command_envelope_hash varchar(64);

alter table agent_graph_command
    drop constraint ck_agent_graph_command_mode;

alter table agent_graph_command
    add constraint ck_agent_graph_command_mode
    check (
        (
            execution_mode = 'SHADOW'
            and activation_id is null
            and room_fencing_token is null
            and command_hash is null
            and command_envelope_hash is null
        )
        or (
            execution_mode = 'PRODUCTION'
            and activation_id ~ '^p9act\.v1\.[0-9a-f]{32}$'
            and room_fencing_token >= 1
            and command_hash ~ '^[0-9a-f]{64}$'
            and command_envelope_hash ~ '^[0-9a-f]{64}$'
        )
    );

alter table agent_graph_result
    add column activation_id varchar(64);

alter table agent_graph_result
    add column room_fencing_token bigint;

alter table agent_graph_result
    add column command_hash varchar(64);

alter table agent_graph_result
    add column command_envelope_hash varchar(64);

alter table agent_graph_result
    add column proposal_hash varchar(64);

alter table agent_graph_result
    add column result_envelope_hash varchar(64);

alter table agent_graph_result
    add column proposal_source_json jsonb;

alter table agent_graph_result
    add column result_envelope_json jsonb;

alter table agent_graph_result
    drop constraint ck_agent_graph_result_mode;

alter table agent_graph_result
    add constraint ck_agent_graph_result_mode
    check (
        (
            execution_mode = 'SHADOW'
            and activation_id is null
            and room_fencing_token is null
            and command_hash is null
            and command_envelope_hash is null
            and proposal_hash is null
            and result_envelope_hash is null
            and proposal_source_json is null
            and result_envelope_json is null
        )
        or (
            execution_mode = 'PRODUCTION'
            and activation_id ~ '^p9act\.v1\.[0-9a-f]{32}$'
            and room_fencing_token >= 1
            and command_hash ~ '^[0-9a-f]{64}$'
            and command_envelope_hash ~ '^[0-9a-f]{64}$'
            and proposal_hash ~ '^[0-9a-f]{64}$'
            and result_envelope_hash ~ '^[0-9a-f]{64}$'
            and jsonb_typeof(proposal_source_json) = 'object'
            and jsonb_typeof(result_envelope_json) = 'object'
        )
    );

create table agent_graph_production_runtime_activation (
    activation_id varchar(64) primary key,
    run_nonce varchar(128) not null unique,
    context_hash varchar(64) not null,
    environment_id varchar(128) not null,
    environment_generation bigint not null,
    candidate_sha varchar(40) not null,
    tenant_surrogate varchar(128) not null,
    case_scope jsonb not null,
    allowed_room_types jsonb not null,
    temporal_namespace varchar(128) not null,
    context_json jsonb not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    registered_at timestamptz not null default clock_timestamp(),
    constraint ck_production_runtime_activation_id
        check (activation_id ~ '^p9act\.v1\.[0-9a-f]{32}$'),
    constraint ck_production_runtime_activation_hashes
        check (
            context_hash ~ '^[0-9a-f]{64}$'
            and candidate_sha ~ '^[0-9a-f]{40}$'
        ),
    constraint ck_production_runtime_activation_generation
        check (environment_generation >= 1),
    constraint ck_production_runtime_activation_cases
        check (jsonb_typeof(case_scope) = 'object'),
    constraint ck_production_runtime_activation_rooms
        check (
            jsonb_typeof(allowed_room_types) = 'array'
            and jsonb_array_length(allowed_room_types) > 0
        ),
    constraint ck_production_runtime_activation_context
        check (jsonb_typeof(context_json) = 'object'),
    constraint ck_production_runtime_activation_expiry
        check (expires_at > issued_at and expires_at <= issued_at + interval '7200 seconds')
);

create table agent_graph_production_runtime_environment_generation (
    environment_id varchar(128) primary key,
    environment_generation bigint not null,
    activation_id varchar(64) not null,
    context_hash varchar(64) not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint ck_production_runtime_generation_positive check (environment_generation >= 1),
    constraint ck_production_runtime_generation_context_hash
        check (context_hash ~ '^[0-9a-f]{64}$')
);

create table agent_graph_production_runtime_activation_lifecycle (
    activation_id varchar(64) primary key,
    lifecycle_state varchar(32) not null,
    activated_at timestamptz not null,
    drain_only_at timestamptz,
    drained_at timestamptz,
    revoked_at timestamptz,
    updated_at timestamptz not null default clock_timestamp(),
    foreign key (activation_id)
        references agent_graph_production_runtime_activation(activation_id) on delete restrict,
    constraint ck_production_runtime_activation_lifecycle_state
        check (lifecycle_state in ('ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL')),
    constraint ck_production_runtime_activation_lifecycle_times
        check (
            (lifecycle_state = 'ACTIVE' and drain_only_at is null and drained_at is null and revoked_at is null)
            or (lifecycle_state = 'DRAIN_ONLY' and drain_only_at is not null and drained_at is null and revoked_at is null)
            or (lifecycle_state = 'DRAINED' and drain_only_at is not null and drained_at is not null and revoked_at is null)
            or (lifecycle_state = 'REVOKED_TERMINAL' and drain_only_at is not null and drained_at is not null and revoked_at is not null)
        )
);

create table agent_graph_production_runtime_synthetic_case_reservation (
    activation_id varchar(64) not null,
    slot_number integer not null,
    generated_case_id varchar(128) not null unique,
    fixture_set_id varchar(128) not null,
    fixture_set_hash varchar(64) not null,
    reserved_at timestamptz not null default clock_timestamp(),
    primary key (activation_id, slot_number),
    foreign key (activation_id)
        references agent_graph_production_runtime_activation(activation_id) on delete restrict,
    constraint ck_production_runtime_synthetic_slot check (slot_number between 1 and 16),
    constraint ck_production_runtime_synthetic_fixture_hash
        check (fixture_set_hash ~ '^[0-9a-f]{64}$')
);

create table agent_graph_production_runtime_room_authority (
    tenant_surrogate varchar(128) not null,
    case_id varchar(128) not null,
    room_type varchar(16) not null,
    activation_id varchar(64) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    updated_at timestamptz not null default clock_timestamp(),
    primary key (tenant_surrogate, case_id, room_type),
    foreign key (activation_id)
        references agent_graph_production_runtime_activation(activation_id) on delete restrict,
    constraint ck_production_runtime_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_production_runtime_room_epoch check (room_epoch >= 0),
    constraint ck_production_runtime_room_fence check (room_fencing_token >= 1)
);

create function reject_agent_graph_production_runtime_immutable_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'production-runtime grant rows are immutable';
end;
$function$;

create trigger trg_reject_agent_graph_production_runtime_activation_mutation
before update or delete on agent_graph_production_runtime_activation
for each row execute function reject_agent_graph_production_runtime_immutable_mutation();

create trigger trg_reject_agent_graph_production_runtime_synthetic_case_mutation
before update or delete on agent_graph_production_runtime_synthetic_case_reservation
for each row execute function reject_agent_graph_production_runtime_immutable_mutation();
