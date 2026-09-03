-- Fail closed instead of guessing which room owns a case when legacy data is inconsistent.
do $$
begin
    if exists (
        select case_id
          from case_room_epoch
         where lifecycle_status = 'ACTIVE'
         group by case_id
        having count(*) > 1
    ) then
        raise exception using
            errcode = '23514',
            message = 'V040.3 cannot enforce case-global ACTIVE room epochs: duplicate ACTIVE case_id values exist';
    end if;
end
$$;

do $$
begin
    if exists (
        select 1
          from case_room_epoch epoch
          left join case_room room
            on room.id = epoch.room_id
           and room.case_id = epoch.case_id
           and room.room_type = epoch.room_type
         where epoch.lifecycle_status = 'ACTIVE'
           and (epoch.room_id is null or room.id is null)
    ) then
        raise exception using
            errcode = '23514',
            message = 'V040.3 cannot enforce ACTIVE room ownership: an ACTIVE epoch has no valid room binding';
    end if;
end
$$;

alter table case_room_epoch
    add column selection_schema_version varchar(64),
    add column process_contract_version varchar(64),
    add column workflow_type varchar(128);

update case_room_epoch
   set selection_schema_version = 'room-epoch-selection.v1',
       process_contract_version = 'case-process-contract.v1',
       workflow_type = case
           when writer_mode = 'LEGACY' then 'LegacyJavaRoomState'
           else 'CaseProcessWorkflow'
       end,
       temporal_build_id = coalesce(temporal_build_id, 'legacy-java.v1'),
       graph_key = coalesce(graph_key, lower(room_type) || '.legacy'),
       graph_version = coalesce(graph_version, 'legacy.v1'),
       checkpoint_schema_version = coalesce(
           checkpoint_schema_version,
           'legacy-checkpoint.v1'
       ),
       stream_protocol = coalesce(stream_protocol, 'agent-stream.v1');

update case_process_projection
   set temporal_build_id = coalesce(temporal_build_id, 'legacy-java.v1');

do $$
begin
    if exists (
        select 1
          from case_room_epoch
         where btrim(selection_schema_version) = ''
            or btrim(process_contract_version) = ''
            or btrim(workflow_type) = ''
            or btrim(temporal_build_id) = ''
            or btrim(graph_key) = ''
            or btrim(graph_version) = ''
            or btrim(checkpoint_schema_version) = ''
            or btrim(stream_protocol) = ''
            or (
                writer_mode in ('SHADOW', 'TEMPORAL')
                and coalesce(btrim(temporal_workflow_id), '') = ''
            )
            or (
                writer_mode = 'TEMPORAL'
                and coalesce(btrim(temporal_run_id), '') = ''
            )
    ) or exists (
        select 1
          from case_process_projection
         where btrim(temporal_build_id) = ''
            or (
                writer_mode in ('SHADOW', 'TEMPORAL')
                and coalesce(btrim(temporal_workflow_id), '') = ''
            )
            or (
                writer_mode = 'TEMPORAL'
                and coalesce(btrim(temporal_run_id), '') = ''
            )
    ) then
        raise exception using
            errcode = '23514',
            message = 'V040.3 cannot enforce nonblank room epoch execution bindings';
    end if;
end
$$;

alter table case_room_epoch
    alter column selection_schema_version set not null,
    alter column process_contract_version set not null,
    alter column workflow_type set not null,
    alter column temporal_build_id set not null,
    alter column graph_key set not null,
    alter column graph_version set not null,
    alter column checkpoint_schema_version set not null,
    alter column stream_protocol set not null,
    drop constraint ck_case_room_epoch_temporal_binding,
    add constraint ck_case_room_epoch_execution_selection
        check (
            length(btrim(selection_schema_version)) between 1 and 64
            and length(btrim(process_contract_version)) between 1 and 64
            and length(btrim(workflow_type)) between 1 and 128
            and length(btrim(temporal_build_id)) between 1 and 128
            and length(btrim(graph_key)) between 1 and 128
            and length(btrim(graph_version)) between 1 and 128
            and length(btrim(checkpoint_schema_version)) between 1 and 128
            and length(btrim(stream_protocol)) between 1 and 64
        ),
    add constraint ck_case_room_epoch_time_interval
        check (
            updated_at >= activated_at
            and (
                terminal_at is null
                or (terminal_at >= activated_at and terminal_at <= updated_at)
            )
        ),
    add constraint ck_case_room_epoch_active_room_binding
        check (lifecycle_status <> 'ACTIVE' or room_id is not null),
    add constraint ck_case_room_epoch_writer_binding
        check (
            (
                writer_mode = 'LEGACY'
                and temporal_workflow_id is null
                and temporal_run_id is null
            )
            or
            (
                writer_mode = 'SHADOW'
                and temporal_workflow_id is not null
                and length(btrim(temporal_workflow_id)) > 0
                and fencing_token > 0
            )
            or
            (
                writer_mode = 'TEMPORAL'
                and temporal_workflow_id is not null
                and length(btrim(temporal_workflow_id)) > 0
                and temporal_run_id is not null
                and length(btrim(temporal_run_id)) > 0
                and fencing_token > 0
            )
        );

alter table case_process_projection
    alter column temporal_build_id set not null,
    drop constraint ck_case_process_projection_temporal_binding,
    add constraint ck_case_process_projection_writer_binding
        check (
            length(btrim(temporal_build_id)) between 1 and 128
            and (
                (
                    writer_mode = 'LEGACY'
                    and temporal_workflow_id is null
                    and temporal_run_id is null
                )
                or
                (
                    writer_mode = 'SHADOW'
                    and temporal_workflow_id is not null
                    and length(btrim(temporal_workflow_id)) > 0
                    and fencing_token > 0
                )
                or
                (
                    writer_mode = 'TEMPORAL'
                    and temporal_workflow_id is not null
                    and length(btrim(temporal_workflow_id)) > 0
                    and temporal_run_id is not null
                    and length(btrim(temporal_run_id)) > 0
                    and fencing_token > 0
                )
            )
        );

drop index uq_case_room_epoch_active;
drop index uq_case_room_epoch_workflow;

create unique index uq_case_room_epoch_active_case
    on case_room_epoch(case_id)
    where lifecycle_status = 'ACTIVE';

create unique index uq_case_room_epoch_active_workflow
    on case_room_epoch(temporal_workflow_id)
    where lifecycle_status = 'ACTIVE'
      and temporal_workflow_id is not null;

create index idx_case_room_epoch_workflow_history
    on case_room_epoch(temporal_workflow_id, room_epoch, id)
    where temporal_workflow_id is not null;

create or replace function reject_case_room_epoch_selection_rewrite()
returns trigger
language plpgsql
as $$
begin
    if new.id is distinct from old.id
        or new.tenant_surrogate is distinct from old.tenant_surrogate
        or new.case_id is distinct from old.case_id
        or new.room_id is distinct from old.room_id
        or new.room_type is distinct from old.room_type
        or new.room_epoch is distinct from old.room_epoch
        or new.writer_mode is distinct from old.writer_mode
        or new.fencing_token is distinct from old.fencing_token
        or new.temporal_workflow_id is distinct from old.temporal_workflow_id
        or new.temporal_build_id is distinct from old.temporal_build_id
        or new.graph_key is distinct from old.graph_key
        or new.graph_version is distinct from old.graph_version
        or new.checkpoint_schema_version is distinct from old.checkpoint_schema_version
        or new.stream_protocol is distinct from old.stream_protocol
        or new.selection_schema_version is distinct from old.selection_schema_version
        or new.process_contract_version is distinct from old.process_contract_version
        or new.workflow_type is distinct from old.workflow_type
        or new.activated_at is distinct from old.activated_at
        or new.created_at is distinct from old.created_at
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch immutable execution selection cannot be rewritten';
    end if;

    if new.process_revision < old.process_revision
        or new.room_revision < old.room_revision
        or new.updated_at < old.updated_at
        or new.version < old.version
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch revisions, update time and version cannot move backward';
    end if;

    if old.lifecycle_status = 'TERMINAL' then
        if new.lifecycle_status is distinct from old.lifecycle_status
            or new.terminal_at is distinct from old.terminal_at
            or new.process_revision is distinct from old.process_revision
            or new.room_revision is distinct from old.room_revision
            or new.temporal_run_id is distinct from old.temporal_run_id
            or new.updated_at is distinct from old.updated_at
            or new.version is distinct from old.version
        then
            raise exception using
                errcode = '23514',
                message = 'case_room_epoch TERMINAL lifecycle is immutable';
        end if;
        return new;
    end if;

    if new.lifecycle_status = 'TERMINAL' then
        if new.terminal_at is null
            or new.terminal_at < old.updated_at
            or new.process_revision <= old.process_revision
        then
            raise exception using
                errcode = '23514',
                message = 'case_room_epoch terminal transition must advance revision and time';
        end if;
    elsif new.lifecycle_status <> 'ACTIVE' then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch lifecycle transition is invalid';
    end if;

    if new.temporal_run_id is distinct from old.temporal_run_id
        and new.process_revision <= old.process_revision
    then
        raise exception using
            errcode = '23514',
            message = 'case_room_epoch run binding requires a process revision advance';
    end if;
    return new;
end
$$;

create trigger trg_case_room_epoch_immutable_selection
before update on case_room_epoch
for each row
execute function reject_case_room_epoch_selection_rewrite();
