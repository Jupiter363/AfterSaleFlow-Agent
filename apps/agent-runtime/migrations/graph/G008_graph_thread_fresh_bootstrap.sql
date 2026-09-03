-- Permit the one durable terminal transition emitted by a fresh graph thread.
--
-- G007 permits at most one cognitive-revision increment.  A fresh LangGraph
-- thread begins at revision 0 with no durable checkpoint, while its first
-- persisted checkpoint is revision 1 and its terminal materialization is
-- revision 2.  The terminal checkpoint and its RESULT_CHECKPOINTED command
-- binding must already be durable. Keep that bootstrap transition narrowly
-- scoped; every other G007 invariant remains unchanged.

create or replace function guard_graph_thread_update()
returns trigger
language plpgsql
as $function$
declare
    valid_fresh_bootstrap_checkpoint boolean;
    valid_same_revision_child boolean;
begin
    if row(
        new.thread_id, new.tenant_surrogate, new.case_id, new.room_type,
        new.room_epoch, new.actor_scope_json, new.actor_scope_hash,
        new.agent_session_id, new.shared_session, new.graph_key,
        new.graph_version, new.checkpoint_schema_version
    ) is distinct from row(
        old.thread_id, old.tenant_surrogate, old.case_id, old.room_type,
        old.room_epoch, old.actor_scope_json, old.actor_scope_hash,
        old.agent_session_id, old.shared_session, old.graph_key,
        old.graph_version, old.checkpoint_schema_version
    ) then
        raise exception using errcode = '23514',
            message = 'graph thread identity and version bindings are immutable';
    end if;
    if new.cognitive_revision < old.cognitive_revision then
        raise exception using errcode = '23514',
            message = 'graph cognitive revision cannot decrease';
    end if;
    if new.cognitive_revision > old.cognitive_revision + 1 then
        if not (
            old.cognitive_revision = 0
            and old.last_checkpoint_ns is null
            and old.last_checkpoint_id is null
            and new.cognitive_revision = 2
            and new.last_checkpoint_ns is not null
            and new.last_checkpoint_id is not null
            and new.last_checkpoint_id <> ''
            and old.lifecycle_status = 'ACTIVE'
            and new.lifecycle_status = 'ACTIVE'
        ) then
            raise exception using errcode = '23514',
                message = 'graph cognitive revision cannot jump';
        end if;
        select exists (
            select 1
              from checkpoints checkpoint
              join agent_graph_command command
                on command.thread_id = checkpoint.thread_id
               and checkpoint.metadata ->> 'graph_command_id' = command.command_id
               and checkpoint.metadata ->> 'graph_request_hash' = command.request_hash
               and checkpoint.metadata ->> 'graph_fencing_token'
                   = command.fencing_token::text
               and checkpoint.metadata ->> 'graph_result_hash' = command.result_hash
               and checkpoint.metadata ->> 'graph_result_ref' = command.result_ref
               and checkpoint.metadata ->> 'graph_execution_lane' = command.execution_mode
               and checkpoint.metadata ->> 'graph_activation_id' is not distinct from
                   command.activation_id
               and checkpoint.metadata ->> 'graph_room_fencing_token' is not distinct from
                   command.room_fencing_token::text
               and checkpoint.metadata ->> 'graph_command_hash' is not distinct from
                   command.command_hash
               and checkpoint.metadata ->> 'graph_command_envelope_hash' is not distinct from
                   command.command_envelope_hash
             where checkpoint.thread_id = new.thread_id
               and checkpoint.checkpoint_ns = new.last_checkpoint_ns
               and checkpoint.checkpoint_id = new.last_checkpoint_id
               and checkpoint.metadata ->> 'graph_thread_id' = new.thread_id
               and checkpoint.metadata ->> 'graph_room_epoch' = new.room_epoch::text
               and checkpoint.metadata ->> 'graph_key' = new.graph_key
               and checkpoint.metadata ->> 'graph_version' = new.graph_version
               and checkpoint.metadata ->> 'graph_checkpoint_schema_version'
                   = new.checkpoint_schema_version
               and checkpoint.metadata ->> 'graph_cognitive_revision' = '2'
               and command.room_epoch = new.room_epoch
               and command.graph_key = new.graph_key
               and command.graph_version = new.graph_version
               and command.checkpoint_schema_version = new.checkpoint_schema_version
               and command.status = 'RESULT_CHECKPOINTED'
               and command.committed_checkpoint_ns = new.last_checkpoint_ns
               and command.committed_checkpoint_id = new.last_checkpoint_id
        ) into valid_fresh_bootstrap_checkpoint;
        if not valid_fresh_bootstrap_checkpoint then
            raise exception using errcode = '23514',
                message = 'graph cognitive revision cannot jump';
        end if;
    end if;
    if old.lifecycle_status <> 'ACTIVE' and new.lifecycle_status <> old.lifecycle_status then
        raise exception using errcode = '23514',
            message = 'terminal graph thread lifecycle cannot reactivate';
    end if;
    if row(new.last_checkpoint_ns, new.last_checkpoint_id)
        is distinct from row(old.last_checkpoint_ns, old.last_checkpoint_id)
        and new.cognitive_revision = old.cognitive_revision then
        select exists (
            select 1
              from checkpoints checkpoint
             where checkpoint.thread_id = new.thread_id
               and checkpoint.checkpoint_ns = new.last_checkpoint_ns
               and checkpoint.checkpoint_id = new.last_checkpoint_id
               and checkpoint.parent_checkpoint_id = old.last_checkpoint_id
               and checkpoint.metadata ->> 'graph_thread_id' = new.thread_id
               and checkpoint.metadata ->> 'graph_room_epoch' = new.room_epoch::text
               and checkpoint.metadata ->> 'graph_key' = new.graph_key
               and checkpoint.metadata ->> 'graph_version' = new.graph_version
               and checkpoint.metadata ->> 'graph_checkpoint_schema_version'
                   = new.checkpoint_schema_version
               and checkpoint.metadata ->> 'graph_cognitive_revision'
                   = new.cognitive_revision::text
        ) into valid_same_revision_child;
        if not valid_same_revision_child then
            raise exception using errcode = '23514',
                message = 'thread checkpoint change must follow the durable parent chain';
        end if;
    end if;
    return new;
end;
$function$;
