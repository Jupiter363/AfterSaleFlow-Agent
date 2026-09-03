-- Permit durable LangGraph checkpoint transitions within one cognitive revision.
--
-- G001 treated every pointer change as a cognitive-revision increment. LangGraph
-- persists several internal checkpoints for a single cognitive revision, so the
-- pointer may move only to a persisted child checkpoint that carries the exact
-- durable thread and graph bindings.

create or replace function guard_graph_thread_update()
returns trigger
language plpgsql
as $function$
declare
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
        raise exception using errcode = '23514',
            message = 'graph cognitive revision cannot jump';
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
