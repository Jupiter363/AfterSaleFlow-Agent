-- New activation cut-over: agent-stream.v3 is the only target stream protocol. Provider text
-- deltas are transient; only frame snapshots/commit metadata are durable.

alter table agent_run
    drop constraint if exists ck_agent_run_protocol_v2,
    add constraint ck_agent_run_protocol_v3
        check (protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3'));

alter table agent_run_attempt
    add column if not exists public_output_started boolean not null default false,
    add column if not exists public_output_started_at timestamptz;

alter table agent_run_stream_event
    drop constraint if exists ck_agent_run_stream_protocol_v2,
    drop constraint if exists ck_agent_run_stream_v2_binding,
    drop constraint if exists ck_agent_run_stream_event_type,
    add constraint ck_agent_run_stream_protocol_v3
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3')),
    add constraint ck_agent_run_stream_v3_binding
        check (
            stream_protocol not in ('agent-stream.v2', 'agent-stream.v3')
            or (agent_run_attempt_id is not null and payload_hash is not null)
        ),
    add constraint ck_agent_run_stream_event_type_v3
        check (event_type in (
            'start', 'attempt_started', 'visible_delta',
            'public_frame_start', 'public_text_delta', 'active_frame_snapshot',
            'public_frame_committed', 'public_frame_interrupted', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        ));

create unique index if not exists uq_agent_run_stream_event_attempt_sequence_v3
    on agent_run_stream_event(agent_run_id, agent_run_attempt_id, sequence_no)
    where stream_protocol = 'agent-stream.v3';

alter table agent_run_stream_event_identity
    drop constraint if exists ck_stream_event_identity_protocol,
    add constraint ck_stream_event_identity_protocol_v3
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3'));
alter table agent_run_stream_event_delivery
    drop constraint if exists ck_stream_event_delivery_protocol,
    drop constraint if exists ck_stream_event_delivery_type,
    add constraint ck_stream_event_delivery_protocol_v3
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3')),
    add constraint ck_stream_event_delivery_type_v3
        check (event_type in (
            'start', 'attempt_started', 'visible_delta',
            'public_frame_start', 'public_text_delta', 'active_frame_snapshot',
            'public_frame_committed', 'public_frame_interrupted', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        ));
alter table agent_run_stream_delivery_high_watermark
    drop constraint if exists ck_stream_delivery_hwm_protocol,
    add constraint ck_stream_delivery_hwm_protocol_v3
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3'));

-- The archive constraint is intentionally cut over with the same release unit.
alter table agent_run_stream_archive_manifest
    drop constraint if exists ck_stream_archive_manifest_protocol,
    add constraint ck_stream_archive_manifest_protocol_v3
        check (stream_protocol in ('agent_stream.v1', 'agent-stream.v2', 'agent-stream.v3'));

create table if not exists agent_run_public_frame (
    id varchar(64) primary key,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    frame_id varchar(128) not null,
    frame_sequence integer not null,
    frame_type varchar(64) not null,
    public_header jsonb not null,
    public_text text not null,
    header_sha256 varchar(64) not null,
    public_text_sha256 varchar(64) not null,
    frame_sha256 varchar(64) not null,
    public_text_chars integer not null,
    durable_cursor varchar(256) not null,
    commit_status varchar(32) not null default 'COMMITTED',
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_run_public_frame_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id) on delete cascade,
    constraint uq_agent_run_public_frame_identity
        unique (agent_run_id, agent_run_attempt_id, frame_id),
    constraint uq_agent_run_public_frame_sequence
        unique (agent_run_id, agent_run_attempt_id, frame_sequence),
    constraint ck_agent_run_public_frame_hashes
        check (
            header_sha256 ~ '^[0-9a-f]{64}$'
            and public_text_sha256 ~ '^[0-9a-f]{64}$'
            and frame_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_run_public_frame_cursor
        check (durable_cursor ~ '^v3:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}:FRAME:[1-9][0-9]{0,2}$'),
    constraint ck_agent_run_public_frame_chars
        check (public_text_chars between 0 and 100000),
    constraint ck_agent_run_public_frame_status
        check (commit_status = 'COMMITTED')
);

create table if not exists agent_run_frame_authority (
    id varchar(64) primary key,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    command_id varchar(128) not null,
    frame_id varchar(128) not null,
    frame_sequence integer not null,
    frame_type varchar(64) not null,
    private_header jsonb not null,
    public_header jsonb not null,
    public_text text,
    header_sha256 varchar(64) not null,
    public_text_sha256 varchar(64) not null,
    frame_sha256 varchar(64) not null,
    committed_at timestamptz not null default clock_timestamp(),
    constraint fk_agent_run_frame_authority_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id) on delete cascade,
    constraint uq_agent_run_frame_authority_identity
        unique (agent_run_id, agent_run_attempt_id, frame_id),
    constraint ck_agent_run_frame_authority_hashes
        check (
            header_sha256 ~ '^[0-9a-f]{64}$'
            and public_text_sha256 ~ '^[0-9a-f]{64}$'
            and frame_sha256 ~ '^[0-9a-f]{64}$'
        )
);

create index if not exists idx_agent_run_public_frame_replay
    on agent_run_public_frame(agent_run_id, agent_run_attempt_id, frame_sequence);

create table if not exists evidence_turn_projection_v2 (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_epoch bigint not null,
    command_id varchar(128) not null,
    agent_run_id varchar(64) not null,
    agent_run_attempt_id varchar(128) not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    room_message_id varchar(64) not null,
    frame_manifest_sha256 varchar(64) not null,
    result_sha256 varchar(64) not null,
    observation_graph jsonb not null,
    evidence_assessments jsonb not null,
    evidence_requests jsonb not null,
    human_review_tasks jsonb not null,
    room_readiness jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_evidence_turn_projection_v2_attempt
        foreign key (agent_run_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id) on delete cascade,
    constraint fk_evidence_turn_projection_v2_message
        foreign key (room_message_id) references room_message(id),
    constraint uq_evidence_turn_projection_v2_command unique (case_id, command_id),
    constraint uq_evidence_turn_projection_v2_attempt unique (agent_run_id, agent_run_attempt_id),
    constraint ck_evidence_turn_projection_v2_hashes check (
        frame_manifest_sha256 ~ '^[0-9a-f]{64}$'
        and result_sha256 ~ '^[0-9a-f]{64}$'
    )
);

create table if not exists evidence_fact_edge_v2 (
    id varchar(64) primary key,
    projection_id varchar(64) not null,
    case_id varchar(64) not null,
    evidence_id varchar(64) not null,
    source_unit_id varchar(128) not null,
    observation_slot varchar(128) not null,
    fact_id varchar(128) not null,
    relation varchar(64) not null,
    reason text not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_evidence_fact_edge_v2_projection
        foreign key (projection_id) references evidence_turn_projection_v2(id) on delete cascade,
    constraint uq_evidence_fact_edge_v2 unique (
        projection_id, observation_slot, fact_id
    ),
    constraint ck_evidence_fact_edge_v2_relation check (
        relation in (
            'CONTENT_SUPPORTS', 'CONTENT_CONTRADICTS',
            'CONTEXT_ONLY', 'INCONCLUSIVE'
        )
    )
);

create index if not exists idx_evidence_fact_edge_v2_case_fact
    on evidence_fact_edge_v2(case_id, fact_id, evidence_id);

-- Preserve immutable historical activation rows, while admitting only complete old or new
-- graph tuples. Mixed-version tuples remain impossible at the database boundary.
alter table target_e2e_activation
    drop constraint if exists ck_target_e2e_activation_bindings,
    add constraint ck_target_e2e_activation_bindings check (
        length(btrim(case_build_id)) between 1 and 128
        and length(btrim(control_build_id)) between 1 and 128
        and length(btrim(agent_build_id)) between 1 and 128
        and (
            (
                graph_key = 'all-rooms.target-e2e.v1'
                and graph_version = 'target-e2e-graph.2026-07-27.1'
                and graph_checkpoint_schema_version = 'target-e2e-checkpoint.v1'
            ) or (
                graph_key = 'all-rooms.target-e2e.v2'
                and graph_version = 'target-e2e-graph.2026-08-18.1'
                and graph_checkpoint_schema_version = 'target-e2e-checkpoint.v2'
            )
        )
        and graph_binding_hash ~ '^[0-9a-f]{64}$'
        and length(btrim(graph_code_build_id)) between 1 and 128
        and temporal_namespace ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and isolated_graph_db_binding_hash ~ '^[0-9a-f]{64}$'
        and binding_set_hash ~ '^[0-9a-f]{64}$'
    );

alter table case_intake_epoch_selection_binding
    drop constraint if exists ck_r15_selection_constants,
    add constraint ck_r15_selection_constants check (
        (
            writer_mode = 'SHADOW'
            and execution_lane = 'SIGNED_SYNTHETIC_SHADOW'
            and activation_id is null
            and activation_manifest_hash is null
            and isolated_domain_db_binding_hash is null
            and room_type = 'INTAKE'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and graph_key = 'intake.v2'
            and state_schema_version = 'intake-graph-state.v2'
            and output_schema_version = 'intake-turn-proposal.v2'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and agent_session_profile_version = 'agent-session-profile.v1'
            and memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
        ) or (
            writer_mode = 'TEMPORAL'
            and execution_lane = 'TARGET_E2E_CANDIDATE'
            and activation_id is not null
            and activation_manifest_hash ~ '^[0-9a-f]{64}$'
            and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
            and room_type = 'INTAKE'
            and case_workflow_type = 'CaseProcessWorkflow'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and (
                (
                    graph_key = 'all-rooms.target-e2e.v1'
                    and graph_version = 'target-e2e-graph.2026-07-27.1'
                    and checkpoint_schema_version = 'target-e2e-checkpoint.v1'
                    and stream_protocol = 'agent-stream.v2'
                ) or (
                    graph_key = 'all-rooms.target-e2e.v2'
                    and graph_version = 'target-e2e-graph.2026-08-18.1'
                    and checkpoint_schema_version = 'target-e2e-checkpoint.v2'
                    and stream_protocol = 'agent-stream.v3'
                )
            )
        )
    );

alter table case_intake_graph_thread_binding
    drop constraint if exists ck_intake_graph_thread_constants,
    add constraint ck_intake_graph_thread_constants check (
        schema_version = 'graph-private-thread-registration.v1'
        and room_type = 'INTAKE'
        and state_schema_version = 'intake-graph-state.v2'
        and (
            (
                graph_key = 'intake.v2'
                and output_schema_version = 'intake-turn-proposal.v2'
            ) or (
                writer_mode = 'TEMPORAL'
                and (
                    (
                        graph_key = 'all-rooms.target-e2e.v1'
                        and graph_version = 'target-e2e-graph.2026-07-27.1'
                        and checkpoint_schema_version = 'target-e2e-checkpoint.v1'
                        and output_schema_version in (
                            'intake-turn-proposal.v2',
                            'target-e2e-room-proposal-source.v1'
                        )
                    ) or (
                        graph_key = 'all-rooms.target-e2e.v2'
                        and graph_version = 'target-e2e-graph.2026-08-18.1'
                        and checkpoint_schema_version = 'target-e2e-checkpoint.v2'
                        and output_schema_version = 'target-e2e-room-proposal-source.v2'
                    )
                )
            )
        )
    );

create or replace function enforce_target_e2e_intake_thread_binding()
returns trigger
language plpgsql
as $$
begin
    if new.graph_key = 'all-rooms.target-e2e.v1' then
        raise exception using errcode = '23514',
            message = 'target E2E v1 Intake thread writes are retired';
    end if;
    if new.graph_key <> 'all-rooms.target-e2e.v2' then
        return new;
    end if;

    if not exists (
        select 1
          from target_e2e_room_epoch_binding room_binding
          join target_e2e_activation activation
            on activation.activation_id = room_binding.activation_id
           and activation.manifest_hash = room_binding.activation_manifest_hash
           and activation.execution_lane = room_binding.execution_lane
           and activation.isolated_domain_db_binding_hash =
                room_binding.isolated_domain_db_binding_hash
         where room_binding.tenant_surrogate = new.tenant_surrogate
           and room_binding.case_id = new.case_id
           and room_binding.room_type = new.room_type
           and room_binding.room_epoch = new.room_epoch
           and room_binding.room_fencing_token = new.fencing_token
           and activation.lifecycle_status = 'ACTIVE'
           and activation.expires_at > clock_timestamp()
           and activation.graph_key = new.graph_key
           and activation.graph_version = new.graph_version
           and activation.graph_checkpoint_schema_version = new.checkpoint_schema_version
    ) then
        raise exception using errcode = '23514',
            message = 'target E2E Intake thread requires the current activation-bound room epoch';
    end if;
    return new;
end
$$;

create or replace function enforce_target_e2e_intake_selection()
returns trigger
language plpgsql
as $$
declare
    activation_row target_e2e_activation%rowtype;
begin
    if new.writer_mode <> 'TEMPORAL' then
        return new;
    end if;
    select * into activation_row
      from target_e2e_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found
       or activation_row.lifecycle_status <> 'ACTIVE'
       or activation_row.expires_at <= clock_timestamp() then
        raise exception using errcode = '23514',
            message = 'target E2E TEMPORAL selection requires a live ACTIVE activation';
    end if;
    if new.graph_key <> 'all-rooms.target-e2e.v2'
       or new.graph_version <> 'target-e2e-graph.2026-08-18.1'
       or new.checkpoint_schema_version <> 'target-e2e-checkpoint.v2'
       or new.stream_protocol <> 'agent-stream.v3'
       or activation_row.tenant_surrogate is distinct from new.tenant_surrogate
       or activation_row.case_build_id is distinct from new.case_workflow_build_id
       or activation_row.control_build_id is distinct from new.room_workflow_build_id
       or activation_row.graph_key is distinct from new.graph_key
       or activation_row.graph_version is distinct from new.graph_version
       or activation_row.graph_checkpoint_schema_version is distinct from
            new.checkpoint_schema_version
       or not ('INTAKE' = any(activation_row.allowed_room_types))
       or not exists (
            select 1 from target_e2e_case_reservation reservation
             where reservation.activation_id = new.activation_id
               and reservation.tenant_surrogate = new.tenant_surrogate
               and reservation.case_id = new.case_id
               and reservation.reserved_at <= new.created_at
       ) then
        raise exception using errcode = '23514',
            message = 'target E2E TEMPORAL selection binding mismatch or case is not reserved';
    end if;
    return new;
end
$$;

create or replace function enforce_target_e2e_intake_command_material()
returns trigger
language plpgsql
as $$
declare
    admission_row target_e2e_command_admission%rowtype;
    context_document jsonb;
    context_target_schema text;
    context_logical_run_id text;
    context_attempt_id text;
    context_attempt_no_text text;
    context_attempt_no bigint;
    context_previous_attempt_id text;
    matched_attempt_id text;
    matched_previous_attempt_id text;
begin
    select * into admission_row
      from target_e2e_command_admission
     where admission_id = new.admission_id
     for share;

    if not found
       or admission_row.activation_id is distinct from new.activation_id
       or admission_row.activation_manifest_hash is distinct from new.activation_manifest_hash
       or admission_row.execution_lane is distinct from new.execution_lane
       or admission_row.isolated_domain_db_binding_hash is distinct from
            new.isolated_domain_db_binding_hash
       or admission_row.tenant_surrogate is distinct from new.tenant_surrogate
       or admission_row.case_id is distinct from new.case_id
       or admission_row.command_id is distinct from new.command_id
       or admission_row.command_hash is distinct from new.command_hash
       or admission_row.command_envelope_hash is distinct from new.command_envelope_hash
       or admission_row.room_epoch is distinct from new.room_epoch
       or admission_row.room_fencing_token is distinct from new.room_fencing_token
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material must exactly bind its command admission';
    end if;

    context_document := new.context_canonical_json::jsonb;
    if context_document #>> '{schemaVersion}' is distinct from new.context_schema_version
       or context_document #>> '{targetAgentRun,executionLane}' is distinct from new.execution_lane
       or context_document #>> '{targetAgentRun,activationId}' is distinct from new.activation_id
       or context_document #>> '{targetAgentRun,activationManifestHash}' is distinct from
            new.activation_manifest_hash
       or context_document #>> '{targetAgentRun,roomFencingToken}' is distinct from
            new.room_fencing_token::text
       or context_document #>> '{targetAgentRun,commandHash}' is distinct from new.command_hash
       or context_document #>> '{targetAgentRun,commandEnvelopeHash}' is distinct from
            new.command_envelope_hash
       or context_document #>> '{targetAgentRun,request,command,tenant_surrogate}' is distinct from
            new.tenant_surrogate
       or context_document #>> '{targetAgentRun,request,command,case_id}' is distinct from
            new.case_id
       or context_document #>> '{targetAgentRun,request,command,command_id}' is distinct from
            new.command_id
       or context_document #>> '{targetAgentRun,request,command,room_type}' is distinct from 'INTAKE'
       or context_document #>> '{targetAgentRun,request,command,room_epoch}' is distinct from
            new.room_epoch::text
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material context is not an exact admission binding';
    end if;

    context_target_schema := context_document #>> '{targetAgentRun,schemaVersion}';
    context_logical_run_id := context_document #>> '{targetAgentRun,request,agent_run_id}';
    context_attempt_id := context_document #>> '{targetAgentRun,request,command,attempt_id}';
    context_attempt_no_text := context_document #>> '{targetAgentRun,request,attempt_no}';
    context_previous_attempt_id :=
        context_document #>> '{targetAgentRun,request,previous_attempt_id}';

    if context_attempt_no_text is null
       or context_attempt_no_text !~ '^[1-9][0-9]{0,15}$'
    then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material has an invalid attempt number';
    end if;
    context_attempt_no := context_attempt_no_text::bigint;

    if context_attempt_no = 1 then
        if context_target_schema is distinct from 'intake-target-agent-run-context.v1'
           or context_previous_attempt_id is not null
        then
            raise exception using errcode = '23514',
                message = 'initial target E2E Intake material must use context v1 without a predecessor';
        end if;
    elsif context_target_schema is distinct from 'intake-target-agent-run-context.v2'
          or context_previous_attempt_id is null
    then
        raise exception using errcode = '23514',
            message = 'retry target E2E Intake material must use context v2 with a predecessor';
    end if;

    select attempt.id into matched_attempt_id
      from agent_run run
      join agent_run_attempt attempt on attempt.agent_run_id = run.id
     where run.id = context_logical_run_id
       and run.protocol = 'agent-stream.v3'
       and run.executor_kind = 'TEMPORAL_ACTIVITY'
       and run.tenant_surrogate = new.tenant_surrogate
       and run.case_id = new.case_id
       and run.room_type = 'INTAKE'
       and run.room_epoch = new.room_epoch
       and run.fencing_token = new.room_fencing_token
       and run.logical_input_hash =
            context_document #>> '{targetAgentRun,request,logical_input_hash}'
       and run.attempt_limit::text =
            context_document #>> '{targetAgentRun,request,attempt_limit}'
       and attempt.id = context_attempt_id
       and attempt.attempt_no = context_attempt_no
       and attempt.lineage_schema_version = 'agent-run-attempt-lineage.v1'
       and attempt.command_id = new.command_id
       and attempt.command_request_hash =
            context_document #>> '{targetAgentRun,request,command,request_hash}'
       and attempt.logical_input_hash = run.logical_input_hash
       and attempt.previous_attempt_id is not distinct from context_previous_attempt_id
       and context_document #>> '{targetAgentRun,request,command,logical_run_id}' = run.id
     for key share of run, attempt;

    if not found then
        raise exception using errcode = '23514',
            message = 'target E2E Intake material does not bind its durable AgentRun attempt';
    end if;

    if context_attempt_no > 1 then
        select predecessor.id into matched_previous_attempt_id
          from agent_run_attempt predecessor
         where predecessor.id = context_previous_attempt_id
           and predecessor.agent_run_id = context_logical_run_id
           and predecessor.attempt_no = context_attempt_no - 1
         for key share;

        if not found then
            raise exception using errcode = '23514',
                message = 'target E2E Intake retry predecessor is not the adjacent attempt';
        end if;
    end if;

    return new;
end
$$;

create or replace function enforce_target_e2e_room_epoch_binding()
returns trigger
language plpgsql
as $$
declare
    epoch_row case_room_epoch%rowtype;
    activation_row target_e2e_activation%rowtype;
    projection_row case_process_projection%rowtype;
    source_epoch_row case_room_epoch%rowtype;
    source_binding_row target_e2e_room_epoch_binding%rowtype;
    source_changed_in_transaction boolean;
    successor_created_in_transaction boolean;
    previous_room_epoch bigint;
begin
    select * into epoch_row from case_room_epoch
     where id = new.epoch_id for share;
    if not found then
        raise exception using errcode = '23503',
            message = 'target E2E room binding requires a durable room epoch';
    end if;

    select * into activation_row from target_e2e_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found then
        raise exception using errcode = '23514',
            message = 'target E2E room binding requires exact activation authority';
    end if;

    if activation_row.formal_writer <> 'JAVA_FINALIZER_ONLY'
       or activation_row.java_domain_commit_allowed is distinct from true
       or activation_row.external_effects_allowed is distinct from false
       or activation_row.production_traffic_allowed is distinct from false then
        raise exception using errcode = '23514',
            message = 'target E2E room binding requires Java domain write authority';
    end if;

    if activation_row.lifecycle_status = 'ACTIVE' then
        if activation_row.expires_at <= clock_timestamp() then
            raise exception using errcode = '23514',
                message = 'target E2E room binding requires a live ACTIVE activation';
        end if;
    elsif activation_row.lifecycle_status = 'DRAIN_ONLY' then
        select * into projection_row
          from case_process_projection projection
         where projection.case_id = new.case_id
           and projection.tenant_surrogate = new.tenant_surrogate
           and projection.writer_mode = 'TEMPORAL'
         for share;
        if not found then
            raise exception using errcode = '23514',
                message = 'target E2E drain successor requires the current source projection';
        end if;

        select * into source_epoch_row
          from case_room_epoch source_epoch
         where source_epoch.tenant_surrogate = projection_row.tenant_surrogate
           and source_epoch.case_id = projection_row.case_id
           and source_epoch.room_type = projection_row.current_room
           and source_epoch.room_epoch = projection_row.room_epoch
           and source_epoch.fencing_token = projection_row.fencing_token
           and source_epoch.temporal_workflow_id = projection_row.temporal_workflow_id
           and source_epoch.temporal_run_id = projection_row.temporal_run_id
           and source_epoch.temporal_build_id = projection_row.temporal_build_id
         for share;
        if not found then
            raise exception using errcode = '23514',
                message = 'target E2E drain successor source epoch is unavailable';
        end if;

        select source_epoch.xmin::text = pg_current_xact_id()::text
          into source_changed_in_transaction
          from case_room_epoch source_epoch
         where source_epoch.id = source_epoch_row.id;
        select successor.xmin::text = pg_current_xact_id()::text
          into successor_created_in_transaction
          from case_room_epoch successor
         where successor.id = epoch_row.id;

        select * into source_binding_row
          from target_e2e_room_epoch_binding source_binding
         where source_binding.epoch_id = source_epoch_row.id
         for share;
        if not found then
            raise exception using errcode = '23514',
                message = 'target E2E drain successor source binding is unavailable';
        end if;

        select coalesce(max(prior.room_epoch), -1)
          into previous_room_epoch
          from case_room_epoch prior
         where prior.case_id = epoch_row.case_id
           and prior.room_type = epoch_row.room_type
           and prior.id <> epoch_row.id;

        if source_changed_in_transaction is distinct from true
           or successor_created_in_transaction is distinct from true
           or source_epoch_row.lifecycle_status <> 'TERMINAL'
           or source_epoch_row.writer_mode <> 'TEMPORAL'
           or source_epoch_row.provisioning_status <> 'READY'
           or source_epoch_row.process_revision <> projection_row.process_revision + 1
           or epoch_row.lifecycle_status <> 'PREPARING'
           or epoch_row.provisioning_status <> 'PENDING'
           or epoch_row.writer_mode <> 'TEMPORAL'
           or epoch_row.process_revision <> source_epoch_row.process_revision
           or epoch_row.room_revision <> 0
           or epoch_row.fencing_token <> source_epoch_row.fencing_token + 1
           or epoch_row.room_epoch <> previous_room_epoch + 1
           or epoch_row.room_type is not distinct from source_epoch_row.room_type
           or epoch_row.activated_at is distinct from source_epoch_row.terminal_at
           or epoch_row.temporal_workflow_id is distinct from source_epoch_row.temporal_workflow_id
           or epoch_row.temporal_run_id is not null
           or epoch_row.temporal_build_id is distinct from source_epoch_row.temporal_build_id
           or epoch_row.graph_key is distinct from source_epoch_row.graph_key
           or epoch_row.graph_version is distinct from source_epoch_row.graph_version
           or epoch_row.checkpoint_schema_version is distinct from
                source_epoch_row.checkpoint_schema_version
           or epoch_row.stream_protocol is distinct from source_epoch_row.stream_protocol
           or epoch_row.selection_schema_version is distinct from
                source_epoch_row.selection_schema_version
           or epoch_row.process_contract_version is distinct from
                source_epoch_row.process_contract_version
           or epoch_row.workflow_type is distinct from source_epoch_row.workflow_type
           or epoch_row.room_workflow_build_id is distinct from
                source_epoch_row.room_workflow_build_id
           or source_binding_row.activation_id is distinct from new.activation_id
           or source_binding_row.activation_manifest_hash is distinct from
                new.activation_manifest_hash
           or source_binding_row.execution_lane is distinct from new.execution_lane
           or source_binding_row.isolated_domain_db_binding_hash is distinct from
                new.isolated_domain_db_binding_hash
           or source_binding_row.tenant_surrogate is distinct from new.tenant_surrogate
           or source_binding_row.case_id is distinct from new.case_id then
            raise exception using errcode = '23514',
                message = 'target E2E DRAIN_ONLY binding is not an atomic direct successor';
        end if;
    else
        raise exception using errcode = '23514',
            message = 'target E2E room binding activation lifecycle is terminal';
    end if;

    if epoch_row.tenant_surrogate is distinct from new.tenant_surrogate
       or epoch_row.case_id is distinct from new.case_id
       or epoch_row.room_type is distinct from new.room_type
       or epoch_row.room_epoch is distinct from new.room_epoch
       or epoch_row.fencing_token is distinct from new.room_fencing_token
       or epoch_row.writer_mode <> 'TEMPORAL'
       or epoch_row.selection_schema_version <> 'room-epoch-selection.v2'
       or epoch_row.process_contract_version <> 'case-process-contract.v1'
       or epoch_row.workflow_type <> 'CaseProcessWorkflow'
       or epoch_row.room_workflow_type is distinct from
          (case epoch_row.room_type
              when 'INTAKE' then 'IntakeRoomWorkflow'
              when 'EVIDENCE' then 'EvidenceRoomWorkflow'
              when 'HEARING' then 'HearingRoomWorkflow'
              when 'REVIEW' then 'OutcomeRoomWorkflow'
              else null
           end)
       or epoch_row.graph_key is distinct from activation_row.graph_key
       or epoch_row.graph_version is distinct from activation_row.graph_version
       or epoch_row.checkpoint_schema_version is distinct from
            activation_row.graph_checkpoint_schema_version
       or epoch_row.temporal_build_id is distinct from activation_row.case_build_id
       or epoch_row.room_workflow_build_id is distinct from activation_row.control_build_id
       or epoch_row.stream_protocol <> 'agent-stream.v3'
       or activation_row.tenant_surrogate is distinct from new.tenant_surrogate
       or activation_row.execution_lane <> 'TARGET_E2E_CANDIDATE'
       or not (new.room_type = any(activation_row.allowed_room_types)) then
        raise exception using errcode = '23514',
            message = 'target E2E room epoch binding does not match activation authority';
    end if;
    return new;
end
$$;
