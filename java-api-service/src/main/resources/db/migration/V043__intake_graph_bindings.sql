-- Phase 4 Intake Graph bindings. Payload bodies remain in immutable object storage;
-- Domain PostgreSQL stores only authorization tuples, version pins, and references.

alter table case_room_epoch
    add column room_workflow_type varchar(128),
    add column room_workflow_build_id varchar(128),
    add constraint ck_case_room_epoch_v2_room_workflow_binding
        check (
            (
                selection_schema_version = 'room-epoch-selection.v2'
                and length(btrim(room_workflow_type)) between 1 and 128
                and length(btrim(room_workflow_build_id)) between 1 and 128
            )
            or
            (
                selection_schema_version <> 'room-epoch-selection.v2'
                and room_workflow_type is null
                and room_workflow_build_id is null
            )
        );

create unique index uq_case_room_epoch_intake_graph_binding
    on case_room_epoch(
        tenant_surrogate, case_id, room_type, room_epoch, fencing_token
    );

create table case_intake_graph_thread_binding (
    registration_id varchar(128) primary key,
    schema_version varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    audience varchar(32) not null,
    actor_capabilities_json jsonb not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    state_schema_version varchar(128) not null,
    prompt_version varchar(128) not null,
    model_profile_id varchar(128) not null,
    output_schema_version varchar(128) not null,
    policy_version varchar(128) not null,
    guardrail_version varchar(128) not null,
    tool_policy_version varchar(128) not null,
    writer_mode varchar(16) not null,
    registration_hash varchar(64) not null,
    registration_status varchar(24) not null default 'PENDING',
    issued_at timestamptz not null,
    registered_at timestamptz,
    retired_at timestamptz,
    created_at timestamptz not null default now(),
    constraint fk_intake_graph_thread_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_intake_graph_thread_epoch
        foreign key (
            tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ) references case_room_epoch(
            tenant_surrogate, case_id, room_type, room_epoch, fencing_token
        ) on delete cascade,
    constraint ck_intake_graph_thread_constants
        check (
            schema_version = 'graph-private-thread-registration.v1'
            and room_type = 'INTAKE'
            and graph_key = 'intake.v2'
            and state_schema_version = 'intake-graph-state.v2'
            and output_schema_version = 'intake-turn-proposal.v2'
        ),
    constraint ck_intake_graph_thread_identity
        check (
            registration_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and agent_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_intake_graph_thread_scope
        check (
            actor_role in ('USER', 'MERCHANT')
            and audience = actor_role
            and jsonb_typeof(actor_capabilities_json) = 'array'
            and jsonb_array_length(actor_capabilities_json) between 1 and 16
            and octet_length(actor_capabilities_json::text) <= 4096
        ),
    constraint ck_intake_graph_thread_hashes
        check (
            actor_scope_hash ~ '^[0-9a-f]{64}$'
            and registration_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_intake_graph_thread_versions
        check (
            length(btrim(graph_version)) between 1 and 128
            and length(btrim(checkpoint_schema_version)) between 1 and 128
            and length(btrim(prompt_version)) between 1 and 128
            and length(btrim(model_profile_id)) between 1 and 128
            and length(btrim(policy_version)) between 1 and 128
            and length(btrim(guardrail_version)) between 1 and 128
            and length(btrim(tool_policy_version)) between 1 and 128
        ),
    constraint ck_intake_graph_thread_epoch_fence
        check (room_epoch >= 0 and fencing_token > 0),
    constraint ck_intake_graph_thread_writer
        check (writer_mode in ('SHADOW', 'TEMPORAL')),
    constraint ck_intake_graph_thread_status
        check (registration_status in ('PENDING', 'REGISTERED', 'FAILED', 'RETIRED')),
    constraint ck_intake_graph_thread_status_time
        check (
            created_at >= issued_at
            and (
                (
                    registration_status in ('PENDING', 'FAILED')
                    and registered_at is null
                    and retired_at is null
                )
                or
                (
                    registration_status = 'REGISTERED'
                    and registered_at is not null
                    and registered_at >= issued_at
                    and retired_at is null
                )
                or
                (
                    registration_status = 'RETIRED'
                    and retired_at is not null
                    and retired_at >= coalesce(registered_at, issued_at)
                )
            )
        )
);

create unique index uq_intake_graph_thread_id
    on case_intake_graph_thread_binding(thread_id);

create unique index uq_intake_graph_thread_private_tuple
    on case_intake_graph_thread_binding(
        tenant_surrogate,
        case_id,
        room_epoch,
        actor_scope_hash,
        agent_session_id,
        graph_key,
        graph_version,
        checkpoint_schema_version
    );

create unique index uq_intake_graph_thread_domain_binding
    on case_intake_graph_thread_binding(
        registration_id,
        tenant_surrogate,
        case_id,
        room_type,
        room_epoch,
        fencing_token,
        thread_id,
        actor_scope_hash,
        agent_session_id,
        audience
    );

create index idx_intake_graph_thread_case_epoch
    on case_intake_graph_thread_binding(case_id, room_epoch, created_at);

create or replace function enforce_intake_graph_thread_immutability()
returns trigger
language plpgsql
as $$
begin
    if new.registration_id is distinct from old.registration_id
        or new.schema_version is distinct from old.schema_version
        or new.tenant_surrogate is distinct from old.tenant_surrogate
        or new.case_id is distinct from old.case_id
        or new.room_type is distinct from old.room_type
        or new.room_epoch is distinct from old.room_epoch
        or new.fencing_token is distinct from old.fencing_token
        or new.thread_id is distinct from old.thread_id
        or new.actor_id is distinct from old.actor_id
        or new.actor_role is distinct from old.actor_role
        or new.audience is distinct from old.audience
        or new.actor_capabilities_json is distinct from old.actor_capabilities_json
        or new.actor_scope_hash is distinct from old.actor_scope_hash
        or new.agent_session_id is distinct from old.agent_session_id
        or new.graph_key is distinct from old.graph_key
        or new.graph_version is distinct from old.graph_version
        or new.checkpoint_schema_version is distinct from old.checkpoint_schema_version
        or new.state_schema_version is distinct from old.state_schema_version
        or new.prompt_version is distinct from old.prompt_version
        or new.model_profile_id is distinct from old.model_profile_id
        or new.output_schema_version is distinct from old.output_schema_version
        or new.policy_version is distinct from old.policy_version
        or new.guardrail_version is distinct from old.guardrail_version
        or new.tool_policy_version is distinct from old.tool_policy_version
        or new.writer_mode is distinct from old.writer_mode
        or new.registration_hash is distinct from old.registration_hash
        or new.issued_at is distinct from old.issued_at
        or new.created_at is distinct from old.created_at
    then
        raise exception using
            errcode = '23514',
            message = 'Intake Graph private thread binding is immutable';
    end if;

    if (old.registration_status = 'PENDING'
            and new.registration_status not in ('PENDING', 'REGISTERED', 'FAILED', 'RETIRED'))
        or (old.registration_status = 'REGISTERED'
            and new.registration_status not in ('REGISTERED', 'RETIRED'))
        or (old.registration_status = 'FAILED'
            and new.registration_status not in ('FAILED', 'RETIRED'))
        or (old.registration_status = 'RETIRED'
            and new.registration_status <> 'RETIRED')
        or (old.registered_at is not null
            and new.registered_at is distinct from old.registered_at)
        or (old.retired_at is not null
            and new.retired_at is distinct from old.retired_at)
    then
        raise exception using
            errcode = '23514',
            message = 'Intake Graph private thread status cannot move backward';
    end if;
    return new;
end
$$;

create trigger trg_intake_graph_thread_immutability
    before update on case_intake_graph_thread_binding
    for each row execute function enforce_intake_graph_thread_immutability();

create trigger trg_intake_graph_thread_no_truncate
    before truncate on case_intake_graph_thread_binding
    for each statement execute function reject_append_only_mutation();

create trigger trg_intake_graph_thread_no_delete
    before delete on case_intake_graph_thread_binding
    for each row execute function reject_append_only_mutation();

create table case_intake_snapshot_binding (
    binding_id varchar(128) primary key,
    thread_registration_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    actor_audience varchar(32) not null,
    binding_type varchar(16) not null,
    schema_version varchar(128) not null,
    artifact_id varchar(128) not null,
    object_uri varchar(1024) not null,
    object_version varchar(128) not null,
    content_sha256 varchar(64) not null,
    size_bytes bigint not null,
    visibility varchar(16) not null,
    domain_revision bigint not null,
    room_revision bigint,
    projection_revision bigint,
    event_id varchar(128),
    message_id varchar(128),
    event_sequence bigint,
    audience varchar(32),
    occurred_at timestamptz,
    initialization_marker boolean not null,
    created_at timestamptz not null,
    constraint fk_intake_snapshot_thread
        foreign key (
            thread_registration_id,
            tenant_surrogate,
            case_id,
            room_type,
            room_epoch,
            fencing_token,
            thread_id,
            actor_scope_hash,
            agent_session_id,
            actor_audience
        ) references case_intake_graph_thread_binding(
            registration_id,
            tenant_surrogate,
            case_id,
            room_type,
            room_epoch,
            fencing_token,
            thread_id,
            actor_scope_hash,
            agent_session_id,
            audience
        ) on delete cascade,
    constraint ck_intake_snapshot_constants
        check (
            room_type = 'INTAKE'
            and visibility = 'PRIVATE'
            and actor_audience in ('USER', 'MERCHANT')
        ),
    constraint ck_intake_snapshot_identity
        check (
            binding_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and artifact_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and thread_id ~ '^grt[.]v1[.][0-9a-f]{32}$'
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
            and agent_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_intake_snapshot_reference
        check (
            object_uri ~ '^(s3|minio|urn):'
            and length(btrim(object_version)) between 1 and 128
            and content_sha256 ~ '^[0-9a-f]{64}$'
            and (
                (binding_type = 'INITIAL' and size_bytes between 1 and 262144)
                or (binding_type = 'EVENT' and size_bytes between 1 and 32768)
            )
        ),
    constraint ck_intake_snapshot_revisions
        check (
            room_epoch >= 0
            and fencing_token > 0
            and domain_revision >= 0
            and (room_revision is null or room_revision >= 0)
            and (projection_revision is null or projection_revision >= 0)
        ),
    constraint ck_intake_snapshot_shape
        check (
            (
                binding_type = 'INITIAL'
                and schema_version = 'intake-domain-snapshot.v2'
                and initialization_marker
                and room_revision is not null
                and projection_revision is not null
                and event_id is null
                and message_id is null
                and event_sequence is null
                and audience is null
                and occurred_at is null
            )
            or
            (
                binding_type = 'EVENT'
                and schema_version = 'intake-turn-event.v2'
                and not initialization_marker
                and room_revision is null
                and projection_revision is null
                and event_id is not null
                and message_id is not null
                and event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                and message_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                and event_sequence > 0
                and audience in ('USER', 'MERCHANT')
                and audience = actor_audience
                and occurred_at is not null
            )
        )
);

create unique index uq_intake_snapshot_initialization
    on case_intake_snapshot_binding(thread_registration_id)
    where initialization_marker;

create unique index uq_intake_snapshot_artifact
    on case_intake_snapshot_binding(tenant_surrogate, artifact_id);

create unique index uq_intake_event_id
    on case_intake_snapshot_binding(tenant_surrogate, event_id)
    where binding_type = 'EVENT';

create unique index uq_intake_event_sequence
    on case_intake_snapshot_binding(thread_registration_id, event_sequence)
    where binding_type = 'EVENT';

create unique index uq_intake_event_message
    on case_intake_snapshot_binding(tenant_surrogate, message_id)
    where binding_type = 'EVENT';

create index idx_intake_snapshot_thread_sequence
    on case_intake_snapshot_binding(thread_registration_id, event_sequence, created_at);

create trigger trg_case_intake_snapshot_binding_append_only
    before update or truncate on case_intake_snapshot_binding
    for each statement execute function reject_append_only_mutation();

create trigger trg_case_intake_snapshot_binding_delete_append_only
    before delete on case_intake_snapshot_binding
    for each row execute function reject_append_only_mutation();

create function reject_case_room_epoch_child_selection_rewrite()
returns trigger
language plpgsql
as $$
begin
    if new.room_workflow_type is distinct from old.room_workflow_type
        or new.room_workflow_build_id is distinct from old.room_workflow_build_id
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch immutable room Workflow selection cannot be rewritten';
    end if;
    return new;
end
$$;

create trigger trg_case_room_epoch_child_selection_immutable
    before update on case_room_epoch
    for each row execute function reject_case_room_epoch_child_selection_rewrite();
