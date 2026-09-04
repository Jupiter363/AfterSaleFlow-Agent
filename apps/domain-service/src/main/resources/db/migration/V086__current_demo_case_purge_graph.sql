-- Bring the reviewer-only demo purge boundary forward to the current schema.
-- Global Production-Runtime activation, case-id claim/reservation, and tombstone authority
-- deliberately remain outside the case-owned purge graph.

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
        where registration.thread_registration_id =
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

create or replace function demo_case_purge_scope_allowed()
returns boolean
language plpgsql
stable
as $$
declare
    purge_case_id text;
    purge_reviewer_role text;
begin
    purge_case_id := current_setting('app.demo_case_purge_case_id', true);
    purge_reviewer_role :=
        current_setting('app.demo_case_purge_reviewer_role', true);

    return purge_reviewer_role = 'PLATFORM_REVIEWER'
        and purge_case_id is not null
        and exists (
            select 1
            from fulfillment_dispute_case dispute_case
            where dispute_case.id = purge_case_id
              and (
                  dispute_case.source_type = 'INTAKE_CREATED'
                  or (
                      dispute_case.source_type = 'EXTERNAL_IMPORT'
                      and dispute_case.source_system in (
                          'TEMPLATE_SIMULATED_OMS',
                          'LLM_SIMULATED_OMS'
                      )
                  )
              )
        );
end;
$$;

create or replace function demo_case_purge_delete_allowed(
    p_table_name text,
    p_row jsonb
)
returns boolean
language plpgsql
stable
as $$
begin
    return demo_case_purge_scope_allowed()
        and demo_case_purge_row_case_id(p_table_name, p_row) =
            current_setting('app.demo_case_purge_case_id', true);
end;
$$;

create or replace function reject_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception '% is append-only', tg_table_name
        using errcode = '55000';
end;
$$;

create or replace function reject_production_runtime_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE'
       and tg_table_name not in (
           'production_runtime_activation',
           'production_runtime_environment_generation_watermark',
           'production_runtime_case_id_claim',
           'production_runtime_case_reservation',
           'production_runtime_generated_case_tombstone'
       )
    then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception using errcode = '55000',
        message = tg_table_name || ' is append-only';
end;
$$;

create or replace function reject_r15_authority_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception using errcode = '23514',
        message = 'P4-R1.5 authority binding is append-only';
end;
$$;

create or replace function reject_evidence_finalization_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception using
        errcode = '23514',
        message = 'Evidence finalization receipts and sidecars are append-only';
end;
$$;

create or replace function restrict_evidence_authority_snapshot_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
        raise exception using
            errcode = '23514',
            message = 'Evidence authority snapshots cannot be deleted';
    end if;
    if not old.is_current
        or new.is_current
        or (to_jsonb(new) - 'is_current') is distinct from (to_jsonb(old) - 'is_current')
    then
        raise exception using
            errcode = '23514',
            message = 'Evidence authority snapshots are immutable except current retirement';
    end if;
    return new;
end;
$$;

create or replace function reject_evidence_graph_binding_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception using
        errcode = '23514',
        message = 'Evidence Graph authority bindings are append-only';
end;
$$;

create or replace function reject_review_epoch_task_binding_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if tg_level = 'ROW'
           and demo_case_purge_delete_allowed(tg_table_name, to_jsonb(old))
        then
            return old;
        elsif tg_level = 'STATEMENT' and demo_case_purge_scope_allowed() then
            return null;
        end if;
    end if;

    raise exception using errcode = '23514',
        message = 'target Review epoch task binding is append-only';
end;
$$;

do $migration$
declare
    purge_definition text;
begin
    select pg_get_functiondef(
        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
    ) into purge_definition;

    if position(
        'delete from agent_execution_manifest where case_id = p_case_id;'
        in purge_definition
    ) = 0 then
        raise exception 'V086 could not locate the manifest purge anchor';
    end if;

    purge_definition := replace(
        purge_definition,
        'delete from agent_execution_manifest where case_id = p_case_id;',
        $purge$
    -- Production-Runtime material and completion leaves. Global activation,
    -- case-id claim/reservation, and generated tombstone authority is retained.
    delete from production_runtime_command_completion
    where admission_id in (
        select admission_id
        from production_runtime_command_admission
        where case_id = p_case_id
    );
    delete from production_runtime_evidence_command_material where case_id = p_case_id;
    delete from production_runtime_evidence_completion_command_material where case_id = p_case_id;
    delete from production_runtime_hearing_command_material where case_id = p_case_id;
    delete from production_runtime_intake_command_material where case_id = p_case_id;
    delete from production_runtime_review_command_material where case_id = p_case_id;
    delete from production_runtime_review_non_execution_completion where case_id = p_case_id;
    delete from production_runtime_evidence_terminal_receipt where case_id = p_case_id;
    delete from production_runtime_outcome_completion_fact where case_id = p_case_id;
    delete from production_runtime_review_epoch_task_binding where case_id = p_case_id;
    delete from production_runtime_room_object_binding where case_id = p_case_id;
    delete from production_runtime_room_object_index where case_id = p_case_id;

    -- Public-frame and exact-three technical staging leaves.
    delete from hearing_public_frame_binding_v4 where case_id = p_case_id;
    delete from intake_parallel_frame_projection_item
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_frame_ingress
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_frame_slot
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_frame_result
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_frame_generation
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_graph_result_artifact
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_proposal_artifact
    where frame_set_id in (
        select frame_set_id
        from intake_parallel_frame_set
        where case_id = p_case_id
    );
    delete from intake_parallel_frame_set where case_id = p_case_id;

    -- Agent stream leaves which are not all ON DELETE CASCADE from AgentRun.
    delete from agent_run_stream_archive_receipt
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_archive_manifest
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_delivery_high_watermark
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_event_delivery
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_event_delivery_default
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_event_identity
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_public_frame
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run_stream_event
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );

    -- Evidence Graph authority, leaf to root.
    delete from evidence_fact_edge_v2 where case_id = p_case_id;
    delete from evidence_content_authority where case_id = p_case_id;
    delete from evidence_parse_outbox where case_id = p_case_id;
    delete from evidence_turn_projection_v2 where case_id = p_case_id;
    delete from case_evidence_terminal_summary where case_id = p_case_id;
    delete from case_evidence_finalization_receipt_load_binding where case_id = p_case_id;
    delete from case_evidence_operational_recovery where case_id = p_case_id;
    delete from case_evidence_finalization_receipt where case_id = p_case_id;
    delete from case_evidence_current_authority_snapshot where case_id = p_case_id;
    delete from case_evidence_asset_load_receipt
    where graph_binding_id in (
        select graph_binding_id
        from case_evidence_graph_binding
        where case_id = p_case_id
    );
    delete from case_evidence_graph_binding where case_id = p_case_id;

    -- Intake command/party/event authority, leaf to root.
    delete from case_intake_shadow_comparison where case_id = p_case_id;
    delete from case_intake_synthetic_activity_admission where case_id = p_case_id;
    delete from case_intake_command_authority where case_id = p_case_id;
    delete from case_intake_command_payload_authority where case_id = p_case_id;
    delete from case_intake_epoch_party_authority where case_id = p_case_id;
    delete from case_intake_event_slot_authority
    where thread_registration_id in (
        select thread_registration_id
        from case_intake_graph_thread_binding
        where case_id = p_case_id
    );
    delete from case_intake_snapshot_binding where case_id = p_case_id;
    delete from case_intake_epoch_selection_binding where case_id = p_case_id;
    delete from case_intake_graph_thread_binding where case_id = p_case_id;

    -- Hearing and Outcome immutable descendants.
    delete from hearing_closure_fact where case_id = p_case_id;
    delete from hearing_public_transcript_binding where case_id = p_case_id;
    delete from hearing_issue_state_set where case_id = p_case_id;
    delete from hearing_review_handoff_fact where case_id = p_case_id;
    delete from hearing_domain_receipt where case_id = p_case_id;
    delete from hearing_temporal_projection where case_id = p_case_id;
    delete from outcome_compensation_parent_binding where case_id = p_case_id;
    delete from outcome_operation_attempt_observation where case_id = p_case_id;
    delete from outcome_operation_receipt where case_id = p_case_id;
    delete from outcome_operation where case_id = p_case_id;
    delete from outcome_process_projection where case_id = p_case_id;

    -- Target command/epoch and finalization authority. The receipt must be
    -- removed after parallel frame sets and before its execution manifest.
    delete from production_runtime_command_admission where case_id = p_case_id;
    delete from production_runtime_room_epoch_binding where case_id = p_case_id;
    delete from production_runtime_finalization_receipt where case_id = p_case_id;
    delete from agent_execution_manifest where case_id = p_case_id;$purge$
    );

    -- V041 inserted an explicit attempt delete before the logical run. Current
    -- logical runs hold deferred back-references to their committed attempts;
    -- deleting the run lets the run->attempt cascade remove the cycle safely.
    purge_definition := replace(
        purge_definition,
        $old$delete from agent_run_attempt
    where agent_run_id in (
        select id from agent_run where case_id = p_case_id
    );
    delete from agent_run where case_id = p_case_id;$old$,
        'delete from agent_run where case_id = p_case_id;'
    );

    execute purge_definition;
end;
$migration$;

comment on function purge_simulated_dispute_case(varchar, varchar, varchar)
    is 'Physically deletes reviewer-approved demo cases across the current case-owned graph, preserves an audit snapshot, and retains global Production-Runtime anti-reuse authority.';
