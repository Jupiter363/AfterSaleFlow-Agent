-- Allow the isolated target lane to reuse the frozen Intake private-thread wire contract
-- without weakening the legacy or signed-shadow bindings.
alter table case_intake_graph_thread_binding
    drop constraint ck_intake_graph_thread_constants,
    add constraint ck_intake_graph_thread_constants check (
        schema_version = 'graph-private-thread-registration.v1'
        and room_type = 'INTAKE'
        and state_schema_version = 'intake-graph-state.v2'
        and output_schema_version = 'intake-turn-proposal.v2'
        and (
            graph_key = 'intake.v2'
            or (
                writer_mode = 'TEMPORAL'
                and graph_key = 'all-rooms.target-e2e.v1'
                and graph_version = 'target-e2e-graph.2026-07-27.1'
                and checkpoint_schema_version = 'target-e2e-checkpoint.v1'
            )
        )
    );

create or replace function enforce_target_e2e_intake_thread_binding()
returns trigger
language plpgsql
as $$
begin
    if new.graph_key <> 'all-rooms.target-e2e.v1' then
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
           and activation.graph_checkpoint_schema_version =
                new.checkpoint_schema_version
    ) then
        raise exception using errcode = '23514',
            message = 'target E2E Intake thread requires the current activation-bound room epoch';
    end if;
    return new;
end
$$;

create trigger trg_target_e2e_intake_thread_binding
before insert on case_intake_graph_thread_binding
for each row execute function enforce_target_e2e_intake_thread_binding();

create or replace function enforce_target_e2e_finalization_epoch_binding()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
          from target_e2e_room_epoch_binding room_binding
          join target_e2e_activation activation
            on activation.activation_id = room_binding.activation_id
           and activation.manifest_hash = room_binding.activation_manifest_hash
           and activation.execution_lane = room_binding.execution_lane
           and activation.isolated_domain_db_binding_hash =
                room_binding.isolated_domain_db_binding_hash
         where room_binding.activation_id = new.activation_id
           and room_binding.activation_manifest_hash = new.activation_manifest_hash
           and room_binding.execution_lane = new.execution_lane
           and room_binding.isolated_domain_db_binding_hash =
                new.isolated_domain_db_binding_hash
           and room_binding.tenant_surrogate = new.tenant_surrogate
           and room_binding.case_id = new.case_id
           and room_binding.room_type = new.room_type
           and room_binding.room_epoch = new.room_epoch
           and room_binding.room_fencing_token = new.room_fencing_token
           and activation.graph_key = new.graph_key
           and activation.graph_version = new.graph_version
           and activation.graph_checkpoint_schema_version =
                new.checkpoint_schema_version
    ) then
        raise exception using errcode = '23514',
            message = 'target E2E finalization receipt is outside its activation-bound room epoch';
    end if;
    return new;
end
$$;

create trigger trg_target_e2e_finalization_epoch_binding
before insert on target_e2e_finalization_receipt
for each row execute function enforce_target_e2e_finalization_epoch_binding();
