-- Target Hearing is provisioned in two phases.  The V035 flow trigger remains the sole
-- projection creator, but it may bind an exact PROVISIONING epoch to a deterministic transient
-- run id.  The bootstrap finalizer is the only writer allowed to replace that value with the
-- server-issued child run id after the epoch has become ACTIVE/READY.

create or replace function enforce_hearing_temporal_projection_authority()
returns trigger
language plpgsql
as $$
declare
    authority case_room_epoch%rowtype;
    flow hearing_flow_instance%rowtype;
    authority_workflow_id text;
    authority_run_id text;
    authority_build_id text;
    provisional_run_id text;
    provisional_binding boolean;
    activation_transition boolean;
begin
    select epoch.*
      into authority
      from case_room_epoch epoch
     where epoch.id = new.epoch_id
       and epoch.tenant_surrogate = new.tenant_surrogate
       and epoch.case_id = new.case_id
       and epoch.room_type = 'HEARING'
       and epoch.room_epoch = new.hearing_epoch
       and epoch.fencing_token = new.fencing_token;
    if not found then
        raise exception using errcode = '23503',
            message = 'Hearing projection has no exact case_room_epoch authority';
    end if;

    select instance.*
      into flow
      from hearing_flow_instance instance
     where instance.id = new.flow_instance_id
       and instance.case_id = new.case_id;
    if not found then
        raise exception using errcode = '23503',
            message = 'Hearing projection has no exact V035 flow';
    end if;

    authority_workflow_id := coalesce(
        authority.room_temporal_workflow_id, authority.temporal_workflow_id);
    authority_run_id := coalesce(
        authority.room_temporal_run_id, authority.temporal_run_id);
    authority_build_id := coalesce(
        authority.room_workflow_build_id,
        authority.temporal_build_id,
        'legacy-java.v1');
    provisional_run_id := 'provisioning:' || authority.id;
    provisional_binding :=
        authority.writer_mode = 'TEMPORAL'
        and authority.lifecycle_status = 'PROVISIONING'
        and authority.provisioning_status = 'PROVISIONING'
        and authority.room_temporal_run_id is null
        and new.temporal_run_id = provisional_run_id;

    if new.writer_mode <> authority.writer_mode
       or new.process_revision <> authority.process_revision
       or new.room_revision <> authority.room_revision
       or new.temporal_workflow_id is distinct from authority_workflow_id
       or new.temporal_build_or_deployment is distinct from authority_build_id
       or (new.temporal_run_id is distinct from authority_run_id and not provisional_binding) then
        raise exception using errcode = '23514',
            message = 'Hearing projection does not match current case_room_epoch authority';
    end if;

    if new.current_stage <> flow.current_stage
       or new.stage_sequence <> flow.stage_sequence
       or new.stage_deadline_at is distinct from flow.shared_deadline_at then
        raise exception using errcode = '23514',
            message = 'Hearing projection does not match the Java V035 formal cursor';
    end if;

    if new.last_acknowledged_receipt_id is not null
       and not exists (
            select 1
              from hearing_domain_receipt receipt
             where receipt.receipt_id = new.last_acknowledged_receipt_id
               and receipt.receipt_hash = new.last_acknowledged_receipt_hash
               and receipt.flow_instance_id = new.flow_instance_id
               and receipt.case_id = new.case_id
               and receipt.epoch_id = new.epoch_id
               and receipt.process_revision = new.process_revision
               and receipt.room_revision = new.room_revision
               and receipt.fencing_token = new.fencing_token
       ) then
        raise exception using errcode = '23514',
            message = 'Hearing projection receipt acknowledgement is not exact';
    end if;

    if tg_op = 'UPDATE' then
        activation_transition :=
            old.temporal_run_id = provisional_run_id
            and new.temporal_run_id is not distinct from authority.room_temporal_run_id
            and authority.lifecycle_status = 'ACTIVE'
            and authority.provisioning_status = 'READY'
            and coalesce(btrim(authority.room_temporal_run_id), '') <> ''
            and current_setting('app.hearing_activation_commit', true) = 'on';
        if new.epoch_id <> old.epoch_id
           or new.tenant_surrogate <> old.tenant_surrogate
           or new.case_id <> old.case_id
           or new.hearing_epoch <> old.hearing_epoch
           or new.writer_mode <> old.writer_mode
           or new.fencing_token <> old.fencing_token
           or new.temporal_namespace is distinct from old.temporal_namespace
           or new.temporal_workflow_id is distinct from old.temporal_workflow_id
           or (new.temporal_run_id is distinct from old.temporal_run_id
               and not activation_transition)
           or new.temporal_build_or_deployment <> old.temporal_build_or_deployment then
            raise exception using errcode = '23514',
                message = 'Hearing epoch and execution selection are immutable';
        end if;
        if new.process_revision < old.process_revision
           or new.room_revision < old.room_revision
           or new.process_revision > old.process_revision + 1
           or new.room_revision > old.room_revision + 1 then
            raise exception using errcode = '23514',
                message = 'Hearing projection revisions must use monotonic single-step CAS';
        end if;
        if not (
            (new.stage_sequence = old.stage_sequence
                and new.current_stage = old.current_stage)
            or
            (new.stage_sequence = old.stage_sequence + 1)
        ) then
            raise exception using errcode = '23514',
                message = 'Hearing projection can move only to the adjacent stage';
        end if;
    end if;
    return new;
end
$$;
create or replace function bind_new_hearing_flow_projection()
returns trigger
language plpgsql
as $$
declare
    authority case_room_epoch%rowtype;
    configured_epoch_id text;
    configured_namespace text;
    selected_run_id text;
begin
    configured_epoch_id := current_setting('app.hearing_epoch_id', true);
    if coalesce(btrim(configured_epoch_id), '') <> '' then
        select epoch.*
          into authority
          from case_room_epoch epoch
         where epoch.id = configured_epoch_id
           and epoch.case_id = new.case_id
           and epoch.room_type = 'HEARING';
    else
        select epoch.*
          into authority
          from case_room_epoch epoch
         where epoch.case_id = new.case_id
           and epoch.room_type = 'HEARING'
           and epoch.lifecycle_status = 'ACTIVE'
         order by epoch.room_epoch desc, epoch.id
         limit 1;
    end if;
    if not found then
        raise exception using errcode = '23514',
            message = 'new Hearing flow requires an exact authoritative case_room_epoch';
    end if;
    if authority.writer_mode = 'SHADOW' then
        raise exception using errcode = '23514',
            message = 'SHADOW Hearing epoch cannot create formal V035 facts';
    end if;
    if authority.writer_mode = 'TEMPORAL' then
        if current_setting('app.hearing_authority_commit', true) is distinct from 'on' then
            raise exception using errcode = '23514',
                message = 'TEMPORAL Hearing flow creation requires the fenced Java authority commit';
        end if;
        if new.id <> authority.room_id then
            raise exception using errcode = '23514',
                message = 'TEMPORAL Hearing flow does not match the exact room authority';
        end if;
        configured_namespace := current_setting('app.hearing_temporal_namespace', true);
        if coalesce(btrim(configured_namespace), '') = '' then
            raise exception using errcode = '23514',
                message = 'TEMPORAL Hearing flow requires an exact Temporal namespace';
        end if;
        if authority.lifecycle_status = 'PROVISIONING'
           and authority.provisioning_status = 'PROVISIONING'
           and authority.room_temporal_run_id is null then
            selected_run_id := 'provisioning:' || authority.id;
        elsif authority.lifecycle_status = 'ACTIVE'
           and authority.provisioning_status = 'READY'
           and coalesce(btrim(authority.room_temporal_run_id), '') <> '' then
            selected_run_id := authority.room_temporal_run_id;
        else
            raise exception using errcode = '23514',
                message = 'TEMPORAL Hearing flow requires a coherent provisioning authority';
        end if;
    else
        if authority.lifecycle_status <> 'ACTIVE' then
            raise exception using errcode = '23514',
                message = 'LEGACY Hearing flow requires an ACTIVE case_room_epoch authority';
        end if;
        configured_namespace := null;
        selected_run_id := null;
    end if;

    insert into hearing_temporal_projection (
        flow_instance_id, case_id, schema_version, tenant_surrogate, epoch_id,
        room_type, hearing_epoch, writer_mode, process_revision, room_revision,
        fencing_token, current_stage, stage_sequence, stage_deadline_at,
        temporal_namespace, temporal_workflow_id, temporal_run_id,
        temporal_build_or_deployment, projected_at, updated_at
    ) values (
        new.id, new.case_id, 'hearing-stage-projection.v1', authority.tenant_surrogate,
        authority.id, 'HEARING', authority.room_epoch, authority.writer_mode,
        authority.process_revision, authority.room_revision, authority.fencing_token,
        new.current_stage, new.stage_sequence, new.shared_deadline_at,
        configured_namespace,
        coalesce(authority.room_temporal_workflow_id, authority.temporal_workflow_id),
        selected_run_id,
        coalesce(
            authority.room_workflow_build_id,
            authority.temporal_build_id,
            'legacy-java.v1'
        ),
        new.created_at, greatest(new.updated_at, new.created_at)
    );
    return new;
end
$$;
