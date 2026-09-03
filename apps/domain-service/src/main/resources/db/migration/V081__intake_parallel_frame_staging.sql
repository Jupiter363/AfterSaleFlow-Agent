-- Technical staging authority for the Intake parallel-Frame execution profile.
-- Rows in this migration are execution/replay facts only. They never grant formal
-- dossier, room-message, phase, command-completion, or terminal-receipt authority.

alter table agent_run
    drop constraint if exists ck_agent_run_protocol_v3,
    add constraint ck_agent_run_protocol_v4
        check (protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        ));

alter table agent_run_stream_event
    drop constraint if exists ck_agent_run_stream_protocol_v3,
    drop constraint if exists ck_agent_run_stream_v3_binding,
    drop constraint if exists ck_agent_run_stream_event_type_v3,
    add constraint ck_agent_run_stream_protocol_v4
        check (stream_protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        )),
    add constraint ck_agent_run_stream_v4_binding
        check (
            stream_protocol not in ('agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4')
            or (agent_run_attempt_id is not null and payload_hash is not null)
        ),
    add constraint ck_agent_run_stream_event_type_v4
        check (event_type in (
            'start', 'attempt_started', 'visible_delta',
            'public_frame_start', 'public_text_delta',
            'public_frame_projection_item', 'active_frame_snapshot',
            'frame_generation_reset', 'public_frame_committed',
            'public_frame_sealed', 'public_frame_interrupted', 'generation_reset',
            'attempt_aborted', 'attempt_reset', 'usage', 'final', 'error'
        ));

create unique index uq_agent_run_stream_event_attempt_sequence_v4
    on agent_run_stream_event(agent_run_id, agent_run_attempt_id, sequence_no)
    where stream_protocol = 'agent-stream.v4';

alter table agent_run_stream_event_identity
    drop constraint if exists ck_stream_event_identity_protocol_v3,
    add constraint ck_stream_event_identity_protocol_v4
        check (stream_protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        ));

alter table agent_run_stream_event_delivery
    drop constraint if exists ck_stream_event_delivery_protocol_v3,
    drop constraint if exists ck_stream_event_delivery_type_v3,
    add constraint ck_stream_event_delivery_protocol_v4
        check (stream_protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        )),
    add constraint ck_stream_event_delivery_type_v4
        check (event_type in (
            'start', 'attempt_started', 'visible_delta',
            'public_frame_start', 'public_text_delta',
            'public_frame_projection_item', 'active_frame_snapshot',
            'frame_generation_reset', 'public_frame_committed',
            'public_frame_sealed', 'public_frame_interrupted', 'generation_reset',
            'attempt_aborted', 'attempt_reset', 'usage', 'final', 'error'
        ));

alter table agent_run_stream_delivery_high_watermark
    drop constraint if exists ck_stream_delivery_hwm_protocol_v3,
    add constraint ck_stream_delivery_hwm_protocol_v4
        check (stream_protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        ));

alter table agent_run_stream_archive_manifest
    drop constraint if exists ck_stream_archive_manifest_protocol_v3,
    add constraint ck_stream_archive_manifest_protocol_v4
        check (stream_protocol in (
            'agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3', 'agent-stream.v4'
        ));

create table intake_parallel_frame_set (
    frame_set_id varchar(128) primary key,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    command_id varchar(128) not null,
    command_request_sha256 varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_id varchar(64) not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    thread_id varchar(64) not null,
    actor_scope_hash varchar(64) not null,
    agent_session_id varchar(128) not null,
    event_binding_id varchar(128) not null,
    thread_registration_id varchar(128) not null,
    logical_sequence bigint not null,
    binding_generation bigint not null,
    authority_version bigint not null,
    context_envelope_sha256 varchar(64) not null,
    model_context_view_sha256 varchar(64) not null,
    execution_profile_id varchar(128) not null,
    projection_registry_version varchar(128) not null,
    model_profile_id varchar(128) not null,
    turn_deadline_at timestamptz not null,
    assembly_state varchar(32) not null default 'COLLECTING',
    input_set_sha256 varchar(64),
    proposal_artifact_id varchar(128),
    proposal_sha256 varchar(64),
    graph_result_sha256 varchar(64),
    terminal_receipt_id varchar(128),
    failure_code varchar(128),
    version bigint not null default 0,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    ready_at timestamptz,
    committed_at timestamptz,
    failed_at timestamptz,
    constraint fk_intake_parallel_frame_set_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id) on delete cascade,
    constraint fk_intake_parallel_frame_set_event_binding
        foreign key (
            event_binding_id, thread_registration_id, logical_sequence, binding_generation
        ) references case_intake_snapshot_binding(
            binding_id, thread_registration_id, event_sequence, binding_generation
        ),
    constraint uq_intake_parallel_frame_set_attempt
        unique (agent_run_id, agent_run_attempt_id),
    constraint ck_intake_parallel_frame_set_identity
        check (
            frame_set_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and command_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and execution_profile_id = 'PARALLEL_FRAMES_V1'
            and length(btrim(projection_registry_version)) between 1 and 128
            and length(btrim(model_profile_id)) between 1 and 128
        ),
    constraint ck_intake_parallel_frame_set_numbers
        check (
            room_epoch >= 0 and fencing_token > 0 and logical_sequence > 0
            and binding_generation > 0 and authority_version >= 0 and version >= 0
        ),
    constraint ck_intake_parallel_frame_set_hashes
        check (
            command_request_sha256 ~ '^[0-9a-f]{64}$'
            and actor_scope_hash ~ '^[0-9a-f]{64}$'
            and context_envelope_sha256 ~ '^[0-9a-f]{64}$'
            and model_context_view_sha256 ~ '^[0-9a-f]{64}$'
            and (input_set_sha256 is null or input_set_sha256 ~ '^[0-9a-f]{64}$')
            and (proposal_sha256 is null or proposal_sha256 ~ '^[0-9a-f]{64}$')
            and (graph_result_sha256 is null or graph_result_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_intake_parallel_frame_set_state
        check (assembly_state in (
            'COLLECTING', 'READY', 'COMMITTED', 'FAILED_UNCOMMITTED'
        )),
    constraint ck_intake_parallel_frame_set_state_fields
        check (
            (assembly_state = 'COLLECTING'
                and input_set_sha256 is null
                and proposal_artifact_id is null
                and proposal_sha256 is null
                and graph_result_sha256 is null
                and terminal_receipt_id is null
                and failure_code is null
                and ready_at is null and committed_at is null and failed_at is null)
            or
            (assembly_state = 'READY'
                and input_set_sha256 is not null
                and proposal_artifact_id is not null
                and proposal_sha256 is not null
                and graph_result_sha256 is not null
                and terminal_receipt_id is null
                and failure_code is null
                and ready_at is not null and committed_at is null and failed_at is null)
            or
            (assembly_state = 'COMMITTED'
                and input_set_sha256 is not null
                and proposal_artifact_id is not null
                and proposal_sha256 is not null
                and graph_result_sha256 is not null
                and terminal_receipt_id is not null
                and failure_code is null
                and ready_at is not null and committed_at is not null and failed_at is null)
            or
            (assembly_state = 'FAILED_UNCOMMITTED'
                and terminal_receipt_id is null
                and failure_code is not null
                and committed_at is null and failed_at is not null)
        ),
    constraint ck_intake_parallel_frame_set_time
        check (
            updated_at >= created_at
            and turn_deadline_at > created_at
            and (ready_at is null or ready_at >= created_at)
            and (committed_at is null or committed_at >= ready_at)
            and (failed_at is null or failed_at >= created_at)
        )
);

create table intake_parallel_frame_generation (
    frame_set_id varchar(128) not null,
    frame_type varchar(32) not null,
    frame_generation bigint not null,
    frame_id varchar(128) not null,
    prompt_profile_id varchar(128) not null,
    output_schema_id varchar(128) not null,
    model_profile_id varchar(128) not null,
    frame_model_input_sha256 varchar(64) not null,
    frame_prompt_sha256 varchar(64) not null,
    repair_code varchar(128),
    validation_path varchar(1024),
    provider_call_lease_state varchar(16) not null default 'ADMITTED',
    preview_state varchar(16) not null default 'NONE',
    first_preview_next_local_index bigint,
    latest_snapshot_next_local_index bigint,
    latest_snapshot_sha256 varchar(64),
    latest_snapshot_cursor varchar(160),
    latest_projection_item_sha256 varchar(64),
    next_local_index bigint not null default 0,
    staging_state varchar(16) not null default 'ADMITTED',
    provider_call_count integer not null default 0,
    result_id varchar(128),
    failure_code varchar(128),
    failure_retryable boolean,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    started_at timestamptz,
    terminal_at timestamptz,
    primary key (frame_set_id, frame_type, frame_generation),
    constraint fk_intake_parallel_frame_generation_set
        foreign key (frame_set_id) references intake_parallel_frame_set(frame_set_id)
        on delete cascade,
    constraint uq_intake_parallel_frame_generation_frame_id unique (frame_id),
    constraint uq_intake_parallel_frame_generation_exact
        unique (frame_set_id, frame_type, frame_generation, frame_id),
    constraint ck_intake_parallel_frame_generation_type
        check (frame_type in ('DIALOGUE_FRAME', 'DOSSIER_FRAME', 'QUALITY_FRAME')),
    constraint ck_intake_parallel_frame_generation_identity
        check (
            frame_generation > 0
            and frame_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and length(btrim(prompt_profile_id)) between 1 and 128
            and length(btrim(output_schema_id)) between 1 and 128
            and length(btrim(model_profile_id)) between 1 and 128
            and (
                (frame_generation = 1 and repair_code is null and validation_path is null)
                or (
                    frame_generation > 1
                    and repair_code ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                    and length(btrim(validation_path)) between 1 and 1024
                )
            )
        ),
    constraint ck_intake_parallel_frame_generation_hashes
        check (
            frame_model_input_sha256 ~ '^[0-9a-f]{64}$'
            and frame_prompt_sha256 ~ '^[0-9a-f]{64}$'
            and (latest_snapshot_sha256 is null or latest_snapshot_sha256 ~ '^[0-9a-f]{64}$')
            and (latest_projection_item_sha256 is null
                or latest_projection_item_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_intake_parallel_frame_generation_progress
        check (
            next_local_index >= 0
            and provider_call_count between 0 and 2
            and (first_preview_next_local_index is null
                or first_preview_next_local_index between 1 and next_local_index)
            and (latest_snapshot_next_local_index is null
                or latest_snapshot_next_local_index between 0 and next_local_index)
        ),
    constraint ck_intake_parallel_frame_generation_states
        check (
            provider_call_lease_state in ('ADMITTED', 'STARTED', 'TERMINAL', 'AMBIGUOUS')
            and preview_state in ('NONE', 'OBSERVED')
            and staging_state in ('ADMITTED', 'STARTED', 'SEALED', 'FAILED', 'AMBIGUOUS')
        ),
    constraint ck_intake_parallel_frame_generation_state_fields
        check (
            (staging_state in ('ADMITTED', 'STARTED')
                and result_id is null and failure_code is null
                and failure_retryable is null and terminal_at is null)
            or
            (staging_state = 'SEALED'
                and result_id is not null and failure_code is null
                and failure_retryable is null and terminal_at is not null)
            or
            (staging_state in ('FAILED', 'AMBIGUOUS')
                and result_id is null and failure_code is not null
                and failure_retryable is not null and terminal_at is not null)
        ),
    constraint ck_intake_parallel_frame_generation_time
        check (
            updated_at >= created_at
            and (started_at is null or started_at >= created_at)
            and (terminal_at is null or terminal_at >= created_at)
        )
);

create table intake_parallel_frame_slot (
    frame_set_id varchar(128) not null,
    frame_type varchar(32) not null,
    current_generation bigint not null,
    current_frame_id varchar(128) not null,
    slot_state varchar(16) not null,
    current_result_id varchar(128),
    slot_version bigint not null default 0,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (frame_set_id, frame_type),
    constraint fk_intake_parallel_frame_slot_generation
        foreign key (frame_set_id, frame_type, current_generation)
        references intake_parallel_frame_generation(
            frame_set_id, frame_type, frame_generation
        ),
    constraint fk_intake_parallel_frame_slot_exact_frame
        foreign key (
            frame_set_id, frame_type, current_generation, current_frame_id
        ) references intake_parallel_frame_generation(
            frame_set_id, frame_type, frame_generation, frame_id
        ),
    constraint uq_intake_parallel_frame_slot_frame_id unique (current_frame_id),
    constraint ck_intake_parallel_frame_slot_type
        check (frame_type in ('DIALOGUE_FRAME', 'DOSSIER_FRAME', 'QUALITY_FRAME')),
    constraint ck_intake_parallel_frame_slot_state
        check (slot_state in ('ADMITTED', 'STARTED', 'SEALED', 'FAILED', 'AMBIGUOUS')),
    constraint ck_intake_parallel_frame_slot_fields
        check (
            current_generation > 0 and slot_version >= 0
            and ((slot_state = 'SEALED' and current_result_id is not null)
                or (slot_state <> 'SEALED' and current_result_id is null))
            and updated_at >= created_at
        )
);

create table intake_parallel_frame_ingress (
    ingress_id varchar(128) primary key,
    frame_set_id varchar(128) not null,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    frame_type varchar(32) not null,
    frame_generation bigint not null,
    ingress_identity varchar(256) not null,
    stream_session_id varchar(128) not null,
    transport_sequence bigint not null,
    event_kind varchar(64) not null,
    local_index bigint,
    canonical_payload_json jsonb not null,
    canonical_payload_sha256 varchar(64) not null,
    global_sequence bigint not null,
    public_event_id varchar(64) not null,
    receipt_id varchar(128) not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_frame_ingress_generation
        foreign key (frame_set_id, frame_type, frame_generation)
        references intake_parallel_frame_generation(
            frame_set_id, frame_type, frame_generation
        ),
    constraint fk_intake_parallel_frame_ingress_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id) on delete cascade,
    constraint fk_intake_parallel_frame_ingress_public_event
        foreign key (public_event_id) references agent_run_stream_event(id),
    constraint uq_intake_parallel_frame_ingress_identity
        unique (agent_run_id, agent_run_attempt_id, ingress_identity),
    constraint uq_intake_parallel_frame_ingress_session_sequence
        unique (frame_set_id, stream_session_id, transport_sequence),
    constraint uq_intake_parallel_frame_ingress_global_sequence
        unique (agent_run_id, agent_run_attempt_id, global_sequence),
    constraint uq_intake_parallel_frame_ingress_receipt unique (receipt_id),
    constraint ck_intake_parallel_frame_ingress_type
        check (frame_type in ('DIALOGUE_FRAME', 'DOSSIER_FRAME', 'QUALITY_FRAME')),
    constraint ck_intake_parallel_frame_ingress_event_kind
        check (event_kind in (
            'public_frame_start', 'public_frame_projection_item',
            'active_frame_snapshot', 'frame_generation_reset',
            'public_frame_sealed', 'public_frame_interrupted', 'usage'
        )),
    constraint ck_intake_parallel_frame_ingress_numbers
        check (
            frame_generation > 0 and transport_sequence >= 0 and global_sequence >= 0
            and (local_index is null or local_index >= 0)
        ),
    constraint ck_intake_parallel_frame_ingress_hash
        check (canonical_payload_sha256 ~ '^[0-9a-f]{64}$')
);

create table intake_parallel_frame_projection_item (
    frame_set_id varchar(128) not null,
    frame_type varchar(32) not null,
    frame_generation bigint not null,
    local_index bigint not null,
    canonical_item_id varchar(128) not null,
    projection_kind varchar(64) not null,
    projection_path_id varchar(128) not null,
    value_kind varchar(16) not null,
    canonical_value_json jsonb,
    public_text text,
    item_sha256 varchar(64) not null,
    authority_binding_sha256 varchar(64) not null,
    ingress_id varchar(128) not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (frame_set_id, frame_type, frame_generation, local_index),
    constraint fk_intake_parallel_projection_generation
        foreign key (frame_set_id, frame_type, frame_generation)
        references intake_parallel_frame_generation(
            frame_set_id, frame_type, frame_generation
        ),
    constraint fk_intake_parallel_projection_ingress
        foreign key (ingress_id) references intake_parallel_frame_ingress(ingress_id),
    constraint uq_intake_parallel_projection_item_id
        unique (frame_set_id, canonical_item_id),
    constraint ck_intake_parallel_projection_type
        check (frame_type in ('DIALOGUE_FRAME', 'DOSSIER_FRAME', 'QUALITY_FRAME')),
    constraint ck_intake_parallel_projection_value
        check (
            local_index >= 0
            and value_kind in ('TEXT', 'JSON_VALUE')
            and ((value_kind = 'TEXT' and public_text is not null
                    and canonical_value_json is null and octet_length(public_text) <= 32768)
                or (value_kind = 'JSON_VALUE' and canonical_value_json is not null
                    and public_text is null
                    and octet_length(canonical_value_json::text) <= 65536))
        ),
    constraint ck_intake_parallel_projection_hashes
        check (
            item_sha256 ~ '^[0-9a-f]{64}$'
            and authority_binding_sha256 ~ '^[0-9a-f]{64}$'
        )
);

create table intake_parallel_frame_result (
    result_id varchar(128) primary key,
    frame_set_id varchar(128) not null,
    frame_type varchar(32) not null,
    frame_generation bigint not null,
    frame_id varchar(128) not null,
    child_checkpoint_ref varchar(1024) not null,
    child_checkpoint_sha256 varchar(64) not null,
    context_envelope_sha256 varchar(64) not null,
    model_context_view_sha256 varchar(64) not null,
    canonical_result_json jsonb not null,
    result_sha256 varchar(64) not null,
    public_projection_sha256 varchar(64) not null,
    next_local_index bigint not null,
    provider_call_count integer not null,
    input_tokens bigint not null,
    output_tokens bigint not null,
    total_tokens bigint not null,
    latency_ms bigint not null,
    sealed_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_frame_result_generation
        foreign key (frame_set_id, frame_type, frame_generation)
        references intake_parallel_frame_generation(
            frame_set_id, frame_type, frame_generation
        ),
    constraint uq_intake_parallel_frame_result_generation
        unique (frame_set_id, frame_type, frame_generation),
    constraint uq_intake_parallel_frame_result_frame unique (frame_id),
    constraint uq_intake_parallel_frame_result_set_id
        unique (frame_set_id, frame_type, frame_generation, result_id),
    constraint ck_intake_parallel_frame_result_type
        check (frame_type in ('DIALOGUE_FRAME', 'DOSSIER_FRAME', 'QUALITY_FRAME')),
    constraint ck_intake_parallel_frame_result_hashes
        check (
            child_checkpoint_sha256 ~ '^[0-9a-f]{64}$'
            and context_envelope_sha256 ~ '^[0-9a-f]{64}$'
            and model_context_view_sha256 ~ '^[0-9a-f]{64}$'
            and result_sha256 ~ '^[0-9a-f]{64}$'
            and public_projection_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_intake_parallel_frame_result_numbers
        check (
            frame_generation > 0 and next_local_index >= 0
            and provider_call_count between 1 and 2
            and input_tokens >= 0 and output_tokens >= 0
            and total_tokens = input_tokens + output_tokens and latency_ms >= 0
        ),
    constraint ck_intake_parallel_frame_result_size
        check (octet_length(canonical_result_json::text) between 2 and 262144)
);

alter table intake_parallel_frame_generation
    add constraint fk_intake_parallel_frame_generation_result
        foreign key (frame_set_id, frame_type, frame_generation, result_id)
        references intake_parallel_frame_result(
            frame_set_id, frame_type, frame_generation, result_id
        ) deferrable initially deferred;

alter table intake_parallel_frame_slot
    add constraint fk_intake_parallel_frame_slot_result
        foreign key (frame_set_id, frame_type, current_generation, current_result_id)
        references intake_parallel_frame_result(
            frame_set_id, frame_type, frame_generation, result_id
        ) deferrable initially deferred;

create table intake_parallel_proposal_artifact (
    artifact_id varchar(128) primary key,
    frame_set_id varchar(128) not null unique,
    schema_version varchar(128) not null,
    input_set_sha256 varchar(64) not null,
    artifact_uri varchar(1024) not null,
    canonical_proposal_json jsonb not null,
    proposal_sha256 varchar(64) not null,
    size_bytes bigint not null,
    profile_manifest_id varchar(128) not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_proposal_set
        foreign key (frame_set_id) references intake_parallel_frame_set(frame_set_id),
    constraint uq_intake_parallel_proposal_exact
        unique (artifact_id, frame_set_id, input_set_sha256, proposal_sha256),
    constraint ck_intake_parallel_proposal_schema
        check (schema_version = 'intake-turn-proposal.v2'),
    constraint ck_intake_parallel_proposal_reference
        check (
            artifact_uri ~ '^urn:intake:parallel-proposal:'
            and input_set_sha256 ~ '^[0-9a-f]{64}$'
            and proposal_sha256 ~ '^[0-9a-f]{64}$'
            and size_bytes between 2 and 524288
            and length(btrim(profile_manifest_id)) between 1 and 128
        )
);

alter table intake_parallel_frame_set
    add constraint fk_intake_parallel_frame_set_proposal
        foreign key (proposal_artifact_id, frame_set_id, input_set_sha256, proposal_sha256)
        references intake_parallel_proposal_artifact(
            artifact_id, frame_set_id, input_set_sha256, proposal_sha256
        ) deferrable initially deferred;

create index idx_intake_parallel_frame_set_state
    on intake_parallel_frame_set(assembly_state, updated_at);

create index idx_intake_parallel_frame_ingress_replay
    on intake_parallel_frame_ingress(
        agent_run_id, agent_run_attempt_id, global_sequence
    );

create index idx_intake_parallel_frame_result_set
    on intake_parallel_frame_result(frame_set_id, frame_type, frame_generation);

create trigger trg_intake_parallel_ingress_no_update
    before update or delete on intake_parallel_frame_ingress
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_ingress_no_truncate
    before truncate on intake_parallel_frame_ingress
    for each statement execute function reject_append_only_mutation();

create trigger trg_intake_parallel_projection_no_update
    before update or delete on intake_parallel_frame_projection_item
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_projection_no_truncate
    before truncate on intake_parallel_frame_projection_item
    for each statement execute function reject_append_only_mutation();

create trigger trg_intake_parallel_result_no_update
    before update or delete on intake_parallel_frame_result
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_result_no_truncate
    before truncate on intake_parallel_frame_result
    for each statement execute function reject_append_only_mutation();

create trigger trg_intake_parallel_proposal_no_update
    before update or delete on intake_parallel_proposal_artifact
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_proposal_no_truncate
    before truncate on intake_parallel_proposal_artifact
    for each statement execute function reject_append_only_mutation();

create function enforce_intake_parallel_frame_generation_transition()
returns trigger
language plpgsql
as $$
begin
    if new.frame_set_id is distinct from old.frame_set_id
        or new.frame_type is distinct from old.frame_type
        or new.frame_generation is distinct from old.frame_generation
        or new.frame_id is distinct from old.frame_id
        or new.prompt_profile_id is distinct from old.prompt_profile_id
        or new.output_schema_id is distinct from old.output_schema_id
        or new.model_profile_id is distinct from old.model_profile_id
        or new.frame_model_input_sha256 is distinct from old.frame_model_input_sha256
        or new.frame_prompt_sha256 is distinct from old.frame_prompt_sha256
        or new.repair_code is distinct from old.repair_code
        or new.validation_path is distinct from old.validation_path
        or new.created_at is distinct from old.created_at
        or new.updated_at < old.updated_at
        or new.provider_call_count < old.provider_call_count
        or new.next_local_index < old.next_local_index
        or (old.preview_state = 'OBSERVED' and new.preview_state <> 'OBSERVED')
        or (
            old.first_preview_next_local_index is not null
            and new.first_preview_next_local_index is distinct from old.first_preview_next_local_index
        )
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame generation authority/progress drifted';
    end if;
    if old.provider_call_lease_state = 'ADMITTED'
        and new.provider_call_lease_state not in ('STARTED', 'AMBIGUOUS')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame provider lease left ADMITTED illegally';
    end if;
    if old.provider_call_lease_state = 'STARTED'
        and new.provider_call_lease_state not in ('STARTED', 'TERMINAL', 'AMBIGUOUS')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame provider lease left STARTED illegally';
    end if;
    if old.provider_call_lease_state in ('TERMINAL', 'AMBIGUOUS')
        and new.provider_call_lease_state is distinct from old.provider_call_lease_state
    then
        raise exception using errcode = '23514',
            message = 'terminal Intake parallel Frame provider lease is immutable';
    end if;
    if old.staging_state = 'ADMITTED'
        and new.staging_state not in ('STARTED', 'FAILED', 'AMBIGUOUS')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame generation left ADMITTED illegally';
    end if;
    if old.staging_state = 'STARTED'
        and new.staging_state not in ('STARTED', 'SEALED', 'FAILED', 'AMBIGUOUS')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame generation left STARTED illegally';
    end if;
    if old.staging_state in ('SEALED', 'FAILED', 'AMBIGUOUS') then
        raise exception using errcode = '23514',
            message = 'terminal Intake parallel Frame generation is immutable';
    end if;
    return new;
end
$$;

create trigger trg_intake_parallel_frame_generation_transition
    before update on intake_parallel_frame_generation
    for each row execute function enforce_intake_parallel_frame_generation_transition();

create trigger trg_intake_parallel_frame_generation_no_delete
    before delete on intake_parallel_frame_generation
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_frame_generation_no_truncate
    before truncate on intake_parallel_frame_generation
    for each statement execute function reject_append_only_mutation();

create function enforce_intake_parallel_frame_set_transition()
returns trigger
language plpgsql
as $$
begin
    if new.frame_set_id is distinct from old.frame_set_id
        or new.agent_run_id is distinct from old.agent_run_id
        or new.agent_run_attempt_id is distinct from old.agent_run_attempt_id
        or new.command_id is distinct from old.command_id
        or new.command_request_sha256 is distinct from old.command_request_sha256
        or new.event_binding_id is distinct from old.event_binding_id
        or new.thread_registration_id is distinct from old.thread_registration_id
        or new.logical_sequence is distinct from old.logical_sequence
        or new.binding_generation is distinct from old.binding_generation
        or new.authority_version is distinct from old.authority_version
        or new.context_envelope_sha256 is distinct from old.context_envelope_sha256
        or new.model_context_view_sha256 is distinct from old.model_context_view_sha256
        or new.execution_profile_id is distinct from old.execution_profile_id
        or new.projection_registry_version is distinct from old.projection_registry_version
        or new.model_profile_id is distinct from old.model_profile_id
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set authority is immutable';
    end if;
    if new.version <> old.version + 1 or new.updated_at < old.updated_at then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set version must advance exactly once';
    end if;
    if old.assembly_state = 'COLLECTING'
        and new.assembly_state not in ('READY', 'FAILED_UNCOMMITTED')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set left COLLECTING illegally';
    end if;
    if old.assembly_state = 'READY'
        and new.assembly_state not in ('COMMITTED', 'FAILED_UNCOMMITTED')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set left READY illegally';
    end if;
    if old.assembly_state in ('COMMITTED', 'FAILED_UNCOMMITTED') then
        raise exception using errcode = '23514',
            message = 'terminal Intake parallel Frame-set is immutable';
    end if;
    return new;
end
$$;

create trigger trg_intake_parallel_frame_set_transition
    before update on intake_parallel_frame_set
    for each row execute function enforce_intake_parallel_frame_set_transition();

create function enforce_intake_parallel_frame_slot_transition()
returns trigger
language plpgsql
as $$
begin
    if new.frame_set_id is distinct from old.frame_set_id
        or new.frame_type is distinct from old.frame_type
        or new.slot_version <> old.slot_version + 1
        or new.updated_at < old.updated_at
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame slot identity/version drifted';
    end if;
    if old.slot_state = 'SEALED' then
        raise exception using errcode = '23514',
            message = 'sealed Intake parallel Frame slot is immutable';
    end if;
    if new.current_generation = old.current_generation then
        if new.current_frame_id is distinct from old.current_frame_id
            or old.slot_state = 'ADMITTED' and new.slot_state not in ('STARTED', 'FAILED', 'AMBIGUOUS')
            or old.slot_state = 'STARTED' and new.slot_state not in ('SEALED', 'FAILED', 'AMBIGUOUS')
            or old.slot_state in ('FAILED', 'AMBIGUOUS')
        then
            raise exception using errcode = '23514',
                message = 'Intake parallel Frame slot state transition is invalid';
        end if;
    elsif new.current_generation = old.current_generation + 1 then
        if old.slot_state not in ('FAILED', 'AMBIGUOUS')
            or new.slot_state <> 'ADMITTED'
            or new.current_result_id is not null
        then
            raise exception using errcode = '23514',
                message = 'Intake parallel Frame retry generation is unauthorized';
        end if;
    else
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame generation must stay or advance exactly once';
    end if;
    return new;
end
$$;

create trigger trg_intake_parallel_frame_slot_transition
    before update on intake_parallel_frame_slot
    for each row execute function enforce_intake_parallel_frame_slot_transition();

create trigger trg_intake_parallel_frame_slot_no_delete
    before delete on intake_parallel_frame_slot
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_frame_slot_no_truncate
    before truncate on intake_parallel_frame_slot
    for each statement execute function reject_append_only_mutation();

create trigger trg_intake_parallel_frame_set_no_delete
    before delete on intake_parallel_frame_set
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_frame_set_no_truncate
    before truncate on intake_parallel_frame_set
    for each statement execute function reject_append_only_mutation();
