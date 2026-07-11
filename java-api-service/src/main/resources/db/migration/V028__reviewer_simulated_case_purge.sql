-- Reviewer-only physical purge for demo cases. The audit snapshot deliberately has
-- no foreign key so the deletion itself remains reviewable after the case is gone.

create table demo_case_purge_audit (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    source_type varchar(32) not null,
    source_system varchar(64) not null,
    external_case_ref varchar(128),
    reviewer_id varchar(128) not null,
    reviewer_role varchar(32) not null,
    case_snapshot_json jsonb not null,
    related_counts_json jsonb not null default '{}'::jsonb,
    purged_at timestamptz not null default now(),
    constraint ck_demo_case_purge_reviewer
        check (reviewer_role = 'PLATFORM_REVIEWER'),
    constraint ck_demo_case_purge_source
        check (
            source_type = 'EXTERNAL_IMPORT'
            and source_system in (
                'TEMPLATE_SIMULATED_OMS',
                'LLM_SIMULATED_OMS'
            )
        )
);

create index idx_demo_case_purge_audit_case
    on demo_case_purge_audit(case_id, purged_at desc);

-- V013/V016 made the courtroom stream append-only. Keep ordinary updates,
-- deletes and truncates forbidden, but permit row-level deletes only while the
-- narrowly-scoped purge function is deleting the exact simulated case that it
-- already validated and locked.
create or replace function reject_append_only_mutation()
returns trigger
language plpgsql
as $$
declare
    purge_case_id text;
    purge_reviewer_role text;
begin
    if tg_op = 'DELETE' and tg_level = 'ROW' then
        purge_case_id := current_setting('app.demo_case_purge_case_id', true);
        purge_reviewer_role :=
            current_setting('app.demo_case_purge_reviewer_role', true);

        if purge_reviewer_role = 'PLATFORM_REVIEWER'
           and purge_case_id is not null
           and old.case_id = purge_case_id
           and exists (
               select 1
               from fulfillment_dispute_case dispute_case
               where dispute_case.id = purge_case_id
                 and dispute_case.source_type = 'EXTERNAL_IMPORT'
                 and dispute_case.source_system in (
                     'TEMPLATE_SIMULATED_OMS',
                     'LLM_SIMULATED_OMS'
                 )
           ) then
            return old;
        end if;
    end if;

    raise exception '% is append-only', tg_table_name
        using errcode = '55000';
end;
$$;

drop trigger trg_room_message_append_only on room_message;
create trigger trg_room_message_append_only
    before update or truncate on room_message
    for each statement
    execute function reject_append_only_mutation();
create trigger trg_room_message_delete_append_only
    before delete on room_message
    for each row
    execute function reject_append_only_mutation();

drop trigger trg_case_timeline_event_append_only on case_timeline_event;
create trigger trg_case_timeline_event_append_only
    before update or truncate on case_timeline_event
    for each statement
    execute function reject_append_only_mutation();
create trigger trg_case_timeline_event_delete_append_only
    before delete on case_timeline_event
    for each row
    execute function reject_append_only_mutation();

drop trigger trg_room_turn_memory_append_only on room_turn_memory;
create trigger trg_room_turn_memory_append_only
    before update or truncate on room_turn_memory
    for each statement
    execute function reject_append_only_mutation();
create trigger trg_room_turn_memory_delete_append_only
    before delete on room_turn_memory
    for each row
    execute function reject_append_only_mutation();

create function purge_simulated_dispute_case(
    p_case_id varchar,
    p_reviewer_id varchar,
    p_reviewer_role varchar
)
returns varchar
language plpgsql
as $$
declare
    dispute_case fulfillment_dispute_case%rowtype;
    purge_audit_id varchar(64);
    related_counts jsonb;
begin
    if p_reviewer_role <> 'PLATFORM_REVIEWER' then
        raise exception 'only the platform reviewer can delete simulated cases'
            using errcode = '42501';
    end if;

    select *
    into dispute_case
    from fulfillment_dispute_case
    where id = p_case_id
    for update;

    if not found then
        raise exception 'case was not found'
            using errcode = 'P0002';
    end if;

    if dispute_case.source_type <> 'EXTERNAL_IMPORT'
       or dispute_case.source_system not in (
           'TEMPLATE_SIMULATED_OMS',
           'LLM_SIMULATED_OMS'
       ) then
        raise exception 'only simulated imported cases can be deleted'
            using errcode = '42501';
    end if;

    related_counts := jsonb_build_object(
        'rooms', (select count(*) from case_room where case_id = p_case_id),
        'messages', (select count(*) from room_message where case_id = p_case_id),
        'timeline_events', (select count(*) from case_timeline_event where case_id = p_case_id),
        'evidence_items', (select count(*) from evidence_item where case_id = p_case_id),
        'hearing_rounds', (select count(*) from hearing_round where case_id = p_case_id),
        'review_tasks', (select count(*) from review_task where case_id = p_case_id),
        'action_records', (select count(*) from action_record where case_id = p_case_id),
        'agent_runs', (select count(*) from agent_run where case_id = p_case_id),
        'a2a_messages', (select count(*) from agent_a2a_message where case_id = p_case_id),
        'notifications', (select count(*) from notification where case_id = p_case_id)
    );

    purge_audit_id :=
        'PURGE_' || upper(substr(md5(
            p_case_id || ':' || p_reviewer_id || ':' ||
            clock_timestamp()::text || ':' || random()::text
        ), 1, 32));

    insert into demo_case_purge_audit (
        id,
        case_id,
        source_type,
        source_system,
        external_case_ref,
        reviewer_id,
        reviewer_role,
        case_snapshot_json,
        related_counts_json
    ) values (
        purge_audit_id,
        dispute_case.id,
        dispute_case.source_type,
        dispute_case.source_system,
        dispute_case.external_case_ref,
        p_reviewer_id,
        p_reviewer_role,
        to_jsonb(dispute_case),
        related_counts
    );

    perform set_config('app.demo_case_purge_case_id', p_case_id, true);
    perform set_config(
        'app.demo_case_purge_reviewer_role',
        p_reviewer_role,
        true
    );

    -- Review and execution chain, leaf to root.
    delete from action_record where case_id = p_case_id;
    delete from human_review_record where case_id = p_case_id;
    delete from review_task where case_id = p_case_id;
    delete from approval_policy_decision where case_id = p_case_id;
    delete from remedy_action where case_id = p_case_id;
    delete from review_packet where case_id = p_case_id;
    delete from remedy_plan where case_id = p_case_id;

    -- Deliberation, adjudication, settlement and hearing artifacts.
    delete from deliberation_finding where case_id = p_case_id;
    delete from deliberation_report where case_id = p_case_id;
    delete from settlement_confirmation where case_id = p_case_id;
    delete from settlement_proposal where case_id = p_case_id;
    delete from hearing_round_party_submission where case_id = p_case_id;
    delete from hearing_round where case_id = p_case_id;
    delete from hearing_stage_record where case_id = p_case_id;

    -- Evidence graph and party submissions.
    delete from evidence_verification where case_id = p_case_id;
    delete from evidence_dossier_item where case_id = p_case_id;
    delete from claim_issue_evidence_link where case_id = p_case_id;
    delete from dispute_submission where case_id = p_case_id;
    delete from evidence_request where case_id = p_case_id;
    delete from evidence_submission_batch where case_id = p_case_id;

    -- Append-only room stream. The guarded row triggers validate case_id.
    delete from room_message where case_id = p_case_id;
    delete from case_timeline_event where case_id = p_case_id;
    delete from room_turn_memory where case_id = p_case_id;

    -- Scoped access, conversation sessions and dossier state.
    delete from agent_session_dossier where case_id = p_case_id;
    delete from case_intake_dossier where case_id = p_case_id;
    delete from agent_conversation_session where case_id = p_case_id;
    delete from case_access_session where case_id = p_case_id;

    -- Notifications and room lifecycle.
    delete from notification_outbox where case_id = p_case_id;
    delete from notification where case_id = p_case_id;
    delete from evidence_party_completion where case_id = p_case_id;
    delete from case_phase_clock where case_id = p_case_id;

    -- Agent provenance and A2A records.
    delete from agent_a2a_message where case_id = p_case_id;
    delete from agent_tool_call where case_id = p_case_id;
    delete from agent_guardrail_event where case_id = p_case_id;
    delete from agent_memory_entry
    where case_id = p_case_id
       or agent_run_id in (
           select id from agent_run where case_id = p_case_id
       );

    -- Parents that retain references to evidence, agent runs and hearing state.
    delete from evidence_item where case_id = p_case_id;
    delete from evidence_dossier where case_id = p_case_id;
    delete from adjudication_draft where case_id = p_case_id;
    delete from agent_run where case_id = p_case_id;
    delete from hearing_state where case_id = p_case_id;

    -- Core dispute facts, routing, audit and room ownership.
    delete from party_claim where case_id = p_case_id;
    delete from issue where case_id = p_case_id;
    delete from flow_conclusion where case_id = p_case_id;
    delete from route_decision where case_id = p_case_id;
    delete from evaluation_record where case_id = p_case_id;
    delete from audit_log where case_id = p_case_id;
    delete from case_participant where case_id = p_case_id;
    delete from case_room where case_id = p_case_id;

    delete from fulfillment_dispute_case where id = p_case_id;

    return purge_audit_id;
end;
$$;

comment on function purge_simulated_dispute_case(varchar, varchar, varchar)
    is 'Physically deletes only validated simulated external-import cases and preserves a purge audit snapshot.';
