-- Content-addressed authorization records and immutable Agent execution provenance.
-- Large payloads stay in versioned object storage or existing immutable domain ledgers.

create table immutable_payload_snapshot (
    id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32),
    snapshot_type varchar(64) not null,
    source_type varchar(64) not null,
    source_id varchar(128) not null,
    schema_version varchar(128) not null,
    object_uri varchar(1024) not null,
    object_version varchar(128),
    content_sha256 varchar(64) not null,
    size_bytes bigint not null,
    content_type varchar(128),
    visibility varchar(32) not null,
    encryption_key_ref varchar(256),
    legal_hold boolean not null default false,
    retained_until timestamptz,
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_immutable_payload_snapshot_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_immutable_payload_snapshot_binding
        unique (id, tenant_surrogate, case_id, content_sha256),
    constraint ck_immutable_payload_snapshot_room
        check (room_type is null or room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_immutable_payload_snapshot_uri
        check (object_uri ~ '^(s3|minio|urn):'),
    constraint ck_immutable_payload_snapshot_hash
        check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_immutable_payload_snapshot_size
        check (size_bytes between 0 and 1073741824),
    constraint ck_immutable_payload_snapshot_visibility
        check (visibility in ('PRIVATE', 'PARTIES', 'PLATFORM', 'INTERNAL'))
);

create unique index uq_payload_snapshot_source
    on immutable_payload_snapshot(tenant_surrogate, source_type, source_id);

create unique index uq_payload_snapshot_case_hash
    on immutable_payload_snapshot(tenant_surrogate, case_id, content_sha256);

create index idx_payload_snapshot_case_visibility
    on immutable_payload_snapshot(case_id, visibility, created_at);

create index idx_payload_snapshot_retention
    on immutable_payload_snapshot(retained_until)
    where retained_until is not null and legal_hold = false;

create table agent_execution_manifest (
    id varchar(64) primary key,
    schema_version varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    process_revision bigint not null,
    fencing_token bigint not null,
    logical_agent_run_id varchar(128) not null,
    attempt_id varchar(128),
    workflow_id varchar(128),
    workflow_run_id varchar(128),
    workflow_type varchar(128),
    workflow_build_id varchar(128),
    graph_key varchar(128),
    graph_version varchar(128),
    checkpoint_schema_version varchar(128),
    checkpoint_id varchar(128),
    prompt_version varchar(128),
    model_profile_id varchar(128),
    provider varchar(128),
    model_version varchar(128),
    policy_version varchar(128),
    guardrail_version varchar(128),
    manifest_uri varchar(1024) not null,
    manifest_sha256 varchar(64),
    input_snapshot_refs_json jsonb not null default '[]'::jsonb,
    output_snapshot_id varchar(64) not null,
    output_sha256 varchar(64) not null,
    traceparent varchar(55),
    terminal_status varchar(32) not null,
    finalized_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint fk_agent_execution_manifest_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_agent_execution_manifest_output
        foreign key (
            output_snapshot_id, tenant_surrogate, case_id, output_sha256
        ) references immutable_payload_snapshot(
            id, tenant_surrogate, case_id, content_sha256
        ),
    constraint ck_agent_execution_manifest_room
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_agent_execution_manifest_revision
        check (room_epoch >= 0 and process_revision >= 0 and fencing_token >= 0),
    constraint ck_agent_execution_manifest_uri
        check (manifest_uri ~ '^(s3|minio|urn):'),
    constraint ck_agent_execution_manifest_hashes
        check (
            (manifest_sha256 is null or manifest_sha256 ~ '^[0-9a-f]{64}$')
            and output_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_execution_manifest_inputs
        check (
            jsonb_typeof(input_snapshot_refs_json) = 'array'
            and jsonb_array_length(input_snapshot_refs_json) <= 128
            and octet_length(input_snapshot_refs_json::text) <= 65536
        ),
    constraint ck_agent_execution_manifest_traceparent
        check (
            traceparent is null
            or traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
        ),
    constraint ck_agent_execution_manifest_status
        check (terminal_status in ('COMPLETED', 'FAILED', 'ABORTED', 'LEGACY_IMPORTED'))
);

create unique index uq_agent_execution_manifest_logical_run
    on agent_execution_manifest(tenant_surrogate, case_id, logical_agent_run_id);

create unique index uq_agent_execution_manifest_uri
    on agent_execution_manifest(tenant_surrogate, manifest_uri);

create index idx_agent_execution_manifest_case_time
    on agent_execution_manifest(case_id, finalized_at);

create trigger trg_immutable_payload_snapshot_append_only
    before update or truncate on immutable_payload_snapshot
    for each statement execute function reject_append_only_mutation();

create trigger trg_immutable_payload_snapshot_delete_append_only
    before delete on immutable_payload_snapshot
    for each row execute function reject_append_only_mutation();

create trigger trg_agent_execution_manifest_append_only
    before update or truncate on agent_execution_manifest
    for each statement execute function reject_append_only_mutation();

create trigger trg_agent_execution_manifest_delete_append_only
    before delete on agent_execution_manifest
    for each row execute function reject_append_only_mutation();

-- Backfill only resolvable evidence object references with valid SHA-256 values.
insert into immutable_payload_snapshot (
    id, tenant_surrogate, case_id, room_type, snapshot_type,
    source_type, source_id, schema_version, object_uri,
    content_sha256, size_bytes, content_type, visibility,
    created_at, created_by
)
select
    'SNP_' || md5(evidence.id || ':' || lower(evidence.file_hash)),
    'legacy-default',
    evidence.case_id,
    'EVIDENCE',
    'EVIDENCE',
    'EVIDENCE_ITEM',
    evidence.id,
    'evidence-item.v1',
    's3://' || evidence.file_bucket || '/' || evidence.file_object_key,
    lower(evidence.file_hash),
    coalesce(evidence.file_size, 0),
    evidence.content_type,
    case
        when evidence.visibility in ('PRIVATE', 'PARTIES', 'PLATFORM', 'INTERNAL')
            then evidence.visibility
        else 'INTERNAL'
    end,
    evidence.created_at,
    evidence.created_by
from evidence_item evidence
where evidence.file_bucket is not null
  and evidence.file_object_key is not null
  and lower(evidence.file_hash) ~ '^[0-9a-f]{64}$'
on conflict do nothing;

-- Existing V035 artifacts are already immutable. Reference their ledger rows instead
-- of duplicating payload_json into the snapshot table.
insert into immutable_payload_snapshot (
    id, tenant_surrogate, case_id, room_type, snapshot_type,
    source_type, source_id, schema_version, object_uri,
    content_sha256, size_bytes, content_type, visibility,
    created_at, created_by
)
select
    'SNP_' || md5(artifact.id || ':' || artifact.content_hash),
    'legacy-default',
    artifact.case_id,
    'HEARING',
    'AGENT_OUTPUT',
    'HEARING_FLOW_ARTIFACT',
    artifact.id,
    artifact.schema_version,
    'urn:domain:hearing-flow-artifact:' || artifact.id,
    artifact.content_hash,
    octet_length(artifact.payload_json::text),
    'application/json',
    'INTERNAL',
    artifact.created_at,
    artifact.created_by
from hearing_flow_artifact artifact
on conflict do nothing;

-- One conservative legacy manifest reference per existing AgentRun. Missing modern
-- workflow/graph/model fields stay null and are never fabricated during backfill.
insert into agent_execution_manifest (
    id, schema_version, tenant_surrogate, case_id, room_type,
    room_epoch, process_revision, fencing_token,
    logical_agent_run_id, attempt_id, manifest_uri, manifest_sha256,
    input_snapshot_refs_json, output_snapshot_id, output_sha256,
    terminal_status, finalized_at, created_at
)
select
    'MAN_' || md5(selected.case_id || ':' || selected.agent_run_id),
    'agent-execution-manifest.legacy-ref.v1',
    'legacy-default',
    selected.case_id,
    'HEARING',
    0,
    0,
    0,
    selected.agent_run_id,
    selected.agent_run_id,
    'urn:legacy:agent-run:' || selected.agent_run_id,
    null,
    '[]'::jsonb,
    snapshot.id,
    selected.content_hash,
    'LEGACY_IMPORTED',
    selected.created_at,
    selected.created_at
from (
    select distinct on (artifact.case_id, artifact.agent_run_id)
        artifact.case_id,
        artifact.agent_run_id,
        artifact.id as artifact_id,
        artifact.content_hash,
        artifact.created_at
    from hearing_flow_artifact artifact
    order by artifact.case_id, artifact.agent_run_id, artifact.created_at desc, artifact.id desc
) selected
join immutable_payload_snapshot snapshot
  on snapshot.tenant_surrogate = 'legacy-default'
 and snapshot.case_id = selected.case_id
 and snapshot.content_sha256 = selected.content_hash
on conflict do nothing;

-- Extend the existing reviewer-only demo purge explicitly. Production case deletion
-- remains restricted by foreign keys and append-only triggers.
create or replace function purge_simulated_dispute_case(
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
        raise exception 'only the platform reviewer can delete cases'
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

    if dispute_case.source_type = 'INTAKE_CREATED' then
        null;
    elsif dispute_case.source_type = 'EXTERNAL_IMPORT'
          and dispute_case.source_system is not null
          and dispute_case.source_system in (
              'TEMPLATE_SIMULATED_OMS',
              'LLM_SIMULATED_OMS'
          ) then
        null;
    else
        raise exception 'only intake-created or simulated imported cases can be deleted'
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
        'notifications', (select count(*) from notification where case_id = p_case_id),
        'case_commands', (select count(*) from case_command where case_id = p_case_id),
        'domain_operations', (select count(*) from domain_operation where case_id = p_case_id),
        'room_epochs', (select count(*) from case_room_epoch where case_id = p_case_id),
        'snapshots', (select count(*) from immutable_payload_snapshot where case_id = p_case_id),
        'execution_manifests', (select count(*) from agent_execution_manifest where case_id = p_case_id)
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

    -- Temporal command, operation, projection and immutable provenance ledgers.
    delete from agent_execution_manifest where case_id = p_case_id;
    delete from immutable_payload_snapshot where case_id = p_case_id;
    delete from process_reconciliation_issue where case_id = p_case_id;
    delete from domain_operation where case_id = p_case_id;
    delete from case_command_outbox where case_id = p_case_id;
    delete from case_command where case_id = p_case_id;
    delete from case_room_epoch where case_id = p_case_id;
    delete from case_process_projection where case_id = p_case_id;

    -- Hearing V2 aggregate, immutable children before mutable parents.
    delete from hearing_flow_artifact where case_id = p_case_id;
    delete from hearing_trial_dossier where case_id = p_case_id;
    delete from hearing_flow_action where case_id = p_case_id;
    delete from hearing_flow_stage where case_id = p_case_id;
    delete from hearing_flow_instance where case_id = p_case_id;

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

    -- Append-only room stream. Guarded row triggers validate case_id.
    delete from room_message where case_id = p_case_id;
    delete from case_timeline_event where case_id = p_case_id;
    delete from room_turn_memory where case_id = p_case_id;

    -- Scoped access, conversation sessions and dossier state.
    delete from agent_session_dossier where case_id = p_case_id;
    delete from case_intake_party_completion where case_id = p_case_id;
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
    is 'Physically deletes reviewer-approved demo cases, including Temporal control-plane records, and preserves an audit snapshot.';
