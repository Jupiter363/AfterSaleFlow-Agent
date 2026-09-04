-- V086 introduced an exact resolver for append-only rows without case_id.
-- The child event-slot column is thread_registration_id, while the parent
-- registration primary key is named registration_id.

create or replace function demo_case_purge_row_case_id(
    p_table_name text,
    p_row jsonb
)
returns text
language plpgsql
stable
as $$
declare
    resolved_case_id text;
begin
    resolved_case_id := p_row ->> 'case_id';
    if resolved_case_id is not null then
        return resolved_case_id;
    end if;

    if p_row ? 'agent_run_id' then
        select run.case_id
        into resolved_case_id
        from agent_run run
        where run.id = p_row ->> 'agent_run_id';
    elsif p_row ? 'frame_set_id' then
        select frame_set.case_id
        into resolved_case_id
        from intake_parallel_frame_set frame_set
        where frame_set.frame_set_id = p_row ->> 'frame_set_id';
    elsif p_row ? 'thread_registration_id' then
        select registration.case_id
        into resolved_case_id
        from case_intake_graph_thread_binding registration
        where registration.registration_id =
            p_row ->> 'thread_registration_id';
    elsif p_row ? 'graph_binding_id' then
        select graph_binding.case_id
        into resolved_case_id
        from case_evidence_graph_binding graph_binding
        where graph_binding.graph_binding_id = p_row ->> 'graph_binding_id';
    elsif p_row ? 'admission_id' then
        select admission.case_id
        into resolved_case_id
        from production_runtime_command_admission admission
        where admission.admission_id = p_row ->> 'admission_id';
    end if;

    return resolved_case_id;
end;
$$;

do $migration$
declare
    purge_definition text;
    old_registration_lookup constant text :=
        'select thread_registration_id' || chr(10) ||
        '        from case_intake_graph_thread_binding';
    new_registration_lookup constant text :=
        'select registration_id' || chr(10) ||
        '        from case_intake_graph_thread_binding';
begin
    select pg_get_functiondef(
        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
    ) into purge_definition;

    if position(old_registration_lookup in purge_definition) = 0 then
        raise exception 'V087 could not locate the Intake registration purge lookup';
    end if;

    execute replace(
        purge_definition,
        old_registration_lookup,
        new_registration_lookup
    );
end;
$migration$;
