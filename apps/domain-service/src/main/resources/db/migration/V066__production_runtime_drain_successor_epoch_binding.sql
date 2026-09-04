-- A target room transition drains work already admitted by its immutable activation.
-- Fresh bindings still require a live ACTIVE activation. DRAIN_ONLY may bind only the
-- direct successor created atomically while the projection still points at its source.
create or replace function enforce_production_runtime_room_epoch_binding()
returns trigger
language plpgsql
as $$
declare
    epoch_row case_room_epoch%rowtype;
    activation_row production_runtime_activation%rowtype;
    projection_row case_process_projection%rowtype;
    source_epoch_row case_room_epoch%rowtype;
    source_binding_row production_runtime_room_epoch_binding%rowtype;
    source_changed_in_transaction boolean;
    successor_created_in_transaction boolean;
    previous_room_epoch bigint;
begin
    select * into epoch_row from case_room_epoch
     where id = new.epoch_id for share;
    if not found then
        raise exception using errcode = '23503',
            message = 'production runtime room binding requires a durable room epoch';
    end if;

    select * into activation_row from production_runtime_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found then
        raise exception using errcode = '23514',
            message = 'production runtime room binding requires exact activation authority';
    end if;

    if activation_row.formal_writer <> 'JAVA_FINALIZER_ONLY'
       or activation_row.java_domain_commit_allowed is distinct from true
       or activation_row.external_effects_allowed is distinct from false
       or activation_row.production_traffic_allowed is distinct from false then
        raise exception using errcode = '23514',
            message = 'production runtime room binding requires Java domain write authority';
    end if;

    if activation_row.lifecycle_status = 'ACTIVE' then
        if activation_row.expires_at <= clock_timestamp() then
            raise exception using errcode = '23514',
                message = 'production runtime room binding requires a live ACTIVE activation';
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
                message = 'production runtime drain successor requires the current source projection';
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
                message = 'production runtime drain successor source epoch is unavailable';
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
          from production_runtime_room_epoch_binding source_binding
         where source_binding.epoch_id = source_epoch_row.id
         for share;
        if not found then
            raise exception using errcode = '23514',
                message = 'production runtime drain successor source binding is unavailable';
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
           or epoch_row.temporal_workflow_id is distinct from
                source_epoch_row.temporal_workflow_id
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
                message = 'production runtime DRAIN_ONLY binding is not an atomic direct successor';
        end if;
    else
        raise exception using errcode = '23514',
            message = 'production runtime room binding activation lifecycle is terminal';
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
       or epoch_row.stream_protocol <> 'agent-stream.v2'
       or activation_row.tenant_surrogate is distinct from new.tenant_surrogate
       or activation_row.execution_lane <> 'PRODUCTION'
       or not (new.room_type = any(activation_row.allowed_room_types)) then
        raise exception using errcode = '23514',
            message = 'production runtime room epoch binding does not match activation authority';
    end if;
    return new;
end
$$;
