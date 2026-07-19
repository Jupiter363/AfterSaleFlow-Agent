-- Immutable Graph/version/profile bindings and active-reference projection.

create table agent_graph_version_registry (
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    registry_state varchar(32) not null default 'DISABLED',
    state_schema_version varchar(128) not null,
    state_schema_hash varchar(64) not null,
    command_schema_version varchar(128) not null,
    result_schema_version varchar(128) not null,
    prompt_version varchar(128) not null,
    model_profile_id varchar(128) not null,
    output_schema_version varchar(128) not null,
    policy_version varchar(128) not null,
    guardrail_version varchar(128) not null,
    tool_policy_version varchar(128) not null,
    binding_hash varchar(64) not null,
    code_build_id varchar(128) not null,
    loadable boolean not null default true,
    created_at timestamptz not null default clock_timestamp(),
    activated_at timestamptz,
    retired_at timestamptz,
    updated_at timestamptz not null default clock_timestamp(),
    registry_revision bigint not null default 0,
    primary key (graph_key, graph_version, checkpoint_schema_version),
    constraint ck_agent_graph_registry_state
        check (registry_state in ('DISABLED', 'SHADOW', 'RETIRED')),
    constraint ck_agent_graph_registry_hashes
        check (
            state_schema_hash ~ '^[0-9a-f]{64}$'
            and binding_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_registry_revision check (registry_revision >= 0),
    constraint ck_agent_graph_registry_times
        check (
            (
                registry_state = 'SHADOW'
                and loadable
                and activated_at is not null
                and retired_at is null
            )
            or (registry_state = 'RETIRED' and retired_at is not null and loadable)
            or (registry_state = 'DISABLED' and retired_at is null)
        )
);

alter table graph_thread_registry
    add constraint fk_graph_thread_version_registry
    foreign key (graph_key, graph_version, checkpoint_schema_version)
    references agent_graph_version_registry(
        graph_key, graph_version, checkpoint_schema_version
    ) on delete restrict;

create function agent_graph_shadow_comparison_reference_count(
    selected_graph_key varchar,
    selected_graph_version varchar,
    selected_checkpoint_schema_version varchar
)
returns bigint
stable
language plpgsql
as $function$
declare
    reference_count bigint;
begin
    if to_regclass('agent_graph_shadow_comparison') is null then
        return 0;
    end if;
    execute
        'select count(*) from agent_graph_shadow_comparison '
        'where graph_key = $1 and graph_version = $2 '
        'and checkpoint_schema_version = $3'
        into reference_count
        using selected_graph_key, selected_graph_version, selected_checkpoint_schema_version;
    return reference_count;
end;
$function$;

create view agent_graph_version_active_reference as
select
    registry.graph_key,
    registry.graph_version,
    registry.checkpoint_schema_version,
    registry.registry_state,
    registry.loadable,
    (
        select count(*)
          from graph_thread_registry thread
         where thread.graph_key = registry.graph_key
           and thread.graph_version = registry.graph_version
           and thread.checkpoint_schema_version = registry.checkpoint_schema_version
    ) as thread_count,
    (
        select count(*)
          from graph_thread_registry thread
         where thread.graph_key = registry.graph_key
           and thread.graph_version = registry.graph_version
           and thread.checkpoint_schema_version = registry.checkpoint_schema_version
           and thread.lifecycle_status = 'ACTIVE'
    ) as nonterminal_thread_count,
    (
        select count(*)
          from agent_graph_command command
         where command.graph_key = registry.graph_key
           and command.graph_version = registry.graph_version
           and command.checkpoint_schema_version = registry.checkpoint_schema_version
    ) as command_count,
    (
        select count(*)
          from agent_graph_command command
         where command.graph_key = registry.graph_key
           and command.graph_version = registry.graph_version
           and command.checkpoint_schema_version = registry.checkpoint_schema_version
           and command.status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
    ) as nonterminal_command_count,
    (
        select count(*)
          from agent_graph_result result
          join agent_graph_command command
            on command.thread_id = result.thread_id
           and command.command_id = result.command_id
         where command.graph_key = registry.graph_key
           and command.graph_version = registry.graph_version
           and command.checkpoint_schema_version = registry.checkpoint_schema_version
    ) as result_count,
    (
        select count(*)
          from checkpoints
         where checkpoints.metadata ->> 'graph_key' = registry.graph_key
           and checkpoints.metadata ->> 'graph_version' = registry.graph_version
           and checkpoints.metadata ->> 'graph_checkpoint_schema_version'
               = registry.checkpoint_schema_version
    ) as checkpoint_count,
    agent_graph_shadow_comparison_reference_count(
        registry.graph_key, registry.graph_version, registry.checkpoint_schema_version
    ) as shadow_comparison_count
from agent_graph_version_registry registry;

create function guard_agent_graph_version_update()
returns trigger
language plpgsql
as $function$
begin
    if row(
        new.graph_key, new.graph_version, new.checkpoint_schema_version,
        new.state_schema_version, new.state_schema_hash,
        new.command_schema_version, new.result_schema_version,
        new.prompt_version, new.model_profile_id, new.output_schema_version,
        new.policy_version, new.guardrail_version, new.tool_policy_version,
        new.binding_hash, new.code_build_id
    ) is distinct from row(
        old.graph_key, old.graph_version, old.checkpoint_schema_version,
        old.state_schema_version, old.state_schema_hash,
        old.command_schema_version, old.result_schema_version,
        old.prompt_version, old.model_profile_id, old.output_schema_version,
        old.policy_version, old.guardrail_version, old.tool_policy_version,
        old.binding_hash, old.code_build_id
    ) then
        raise exception using errcode = '23514',
            message = 'graph version and profile binding is immutable';
    end if;
    if old.registry_state = 'RETIRED' and new.registry_state <> 'RETIRED' then
        raise exception using errcode = '23514', message = 'retired graph cannot reactivate';
    end if;
    if new.registry_state = 'RETIRED' and old.registry_state <> 'RETIRED'
        and exists (
            select 1
              from agent_graph_version_active_reference active_reference
             where active_reference.graph_key = old.graph_key
               and active_reference.graph_version = old.graph_version
               and active_reference.checkpoint_schema_version = old.checkpoint_schema_version
               and (
                   active_reference.nonterminal_thread_count > 0
                   or active_reference.nonterminal_command_count > 0
                   or active_reference.checkpoint_count > 0
                   or active_reference.shadow_comparison_count > 0
               )
        ) then
        raise exception using errcode = '23514',
            message = 'referenced graph version cannot retire';
    end if;
    if new.registry_revision < old.registry_revision then
        raise exception using errcode = '23514', message = 'registry revision cannot decrease';
    end if;
    return new;
end;
$function$;

create function reject_agent_graph_version_delete()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'graph version registry rows are append-only';
end;
$function$;

create trigger trg_guard_agent_graph_version_update
before update on agent_graph_version_registry
for each row execute function guard_agent_graph_version_update();

create trigger trg_reject_agent_graph_version_delete
before delete on agent_graph_version_registry
for each row execute function reject_agent_graph_version_delete();
