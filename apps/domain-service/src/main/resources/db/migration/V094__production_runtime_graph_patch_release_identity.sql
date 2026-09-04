-- Preserve both predecessor production-runtime graph releases while admitting the
-- append-only patch release used by the current candidate.

alter table production_runtime_activation
    drop constraint if exists ck_production_runtime_activation_bindings,
    add constraint ck_production_runtime_activation_bindings check (
        length(btrim(case_build_id)) between 1 and 128
        and length(btrim(control_build_id)) between 1 and 128
        and length(btrim(agent_build_id)) between 1 and 128
        and (
            (
                graph_key = 'all-rooms.production-runtime.v1'
                and graph_version = 'production-runtime-graph.2026-07-27.1'
                and graph_checkpoint_schema_version = 'production-runtime-checkpoint.v1'
            ) or (
                graph_key = 'all-rooms.production-runtime.v2'
                and graph_version in (
                    'production-runtime-graph.2026-08-18.1',
                    'production-runtime-graph.2026-08-18.2',
                    'production-runtime-graph.2026-08-18.3'
                )
                and graph_checkpoint_schema_version = 'production-runtime-checkpoint.v2'
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
            and execution_lane = 'PRODUCTION'
            and activation_id is not null
            and activation_manifest_hash ~ '^[0-9a-f]{64}$'
            and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
            and room_type = 'INTAKE'
            and case_workflow_type = 'CaseProcessWorkflow'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and graph_key = 'all-rooms.production-runtime.v2'
            and graph_version in (
                'production-runtime-graph.2026-08-18.1',
                'production-runtime-graph.2026-08-18.2',
                'production-runtime-graph.2026-08-18.3'
            )
            and checkpoint_schema_version = 'production-runtime-checkpoint.v2'
            and stream_protocol = 'agent-stream.v3'
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
                        graph_key = 'all-rooms.production-runtime.v1'
                        and graph_version = 'production-runtime-graph.2026-07-27.1'
                        and checkpoint_schema_version = 'production-runtime-checkpoint.v1'
                        and output_schema_version in (
                            'intake-turn-proposal.v2',
                            'production-runtime-room-proposal-source.v1'
                        )
                    ) or (
                        graph_key = 'all-rooms.production-runtime.v2'
                        and graph_version in (
                            'production-runtime-graph.2026-08-18.1',
                            'production-runtime-graph.2026-08-18.2',
                            'production-runtime-graph.2026-08-18.3'
                        )
                        and checkpoint_schema_version = 'production-runtime-checkpoint.v2'
                        and output_schema_version = 'production-runtime-room-proposal-source.v2'
                    )
                )
            )
        )
    );

create or replace function enforce_production_runtime_intake_selection()
returns trigger
language plpgsql
as $$
declare
    activation_row production_runtime_activation%rowtype;
begin
    if new.writer_mode <> 'TEMPORAL' then
        return new;
    end if;
    select * into activation_row
      from production_runtime_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found
       or activation_row.lifecycle_status <> 'ACTIVE'
       or activation_row.expires_at <= clock_timestamp() then
        raise exception using errcode = '23514',
            message = 'production runtime TEMPORAL selection requires a live ACTIVE activation';
    end if;
    if new.graph_key <> 'all-rooms.production-runtime.v2'
       or new.graph_version not in (
            'production-runtime-graph.2026-08-18.1',
            'production-runtime-graph.2026-08-18.2',
            'production-runtime-graph.2026-08-18.3'
       )
       or new.checkpoint_schema_version <> 'production-runtime-checkpoint.v2'
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
            select 1 from production_runtime_case_reservation reservation
             where reservation.activation_id = new.activation_id
               and reservation.tenant_surrogate = new.tenant_surrogate
               and reservation.case_id = new.case_id
               and reservation.reserved_at <= new.created_at
       ) then
        raise exception using errcode = '23514',
            message = 'production runtime TEMPORAL selection binding mismatch or case is not reserved';
    end if;
    return new;
end
$$;
