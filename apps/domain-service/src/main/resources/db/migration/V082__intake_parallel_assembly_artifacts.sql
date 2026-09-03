-- Canonical immutable artifacts for exact-three Intake Frame assembly.
-- This remains technical staging only. It grants neither formal dossier nor
-- AgentRun finalization authority.

do $$
begin
    if exists (select 1 from intake_parallel_proposal_artifact) then
        raise exception using errcode = '23514',
            message = 'legacy Intake parallel proposal rows lack canonical byte authority';
    end if;
end
$$;

alter table intake_parallel_proposal_artifact
    add column canonical_proposal_bytes bytea;

alter table intake_parallel_proposal_artifact
    alter column canonical_proposal_bytes set not null,
    drop constraint ck_intake_parallel_proposal_reference,
    add constraint ck_intake_parallel_proposal_reference_v2
        check (
            artifact_id = 'intake.proposal.' || left(proposal_sha256, 32)
            and artifact_uri = 'urn:target-e2e:proposal:intake:' || proposal_sha256
            and input_set_sha256 ~ '^[0-9a-f]{64}$'
            and proposal_sha256 ~ '^[0-9a-f]{64}$'
            and size_bytes between 2 and 65536
            and size_bytes = octet_length(canonical_proposal_bytes)
            and length(btrim(profile_manifest_id)) between 1 and 128
        );

create unique index uq_intake_parallel_proposal_uri
    on intake_parallel_proposal_artifact(artifact_uri);

create table intake_parallel_graph_result_artifact (
    result_artifact_id varchar(128) primary key,
    frame_set_id varchar(128) not null unique,
    input_set_sha256 varchar(64) not null,
    schema_version varchar(128) not null,
    result_ref varchar(1024) not null unique,
    graph_result_sha256 varchar(64) not null,
    canonical_graph_result_bytes bytea not null,
    graph_result_size_bytes bigint not null,
    proposal_artifact_id varchar(128) not null,
    proposal_sha256 varchar(64) not null,
    canonical_command_envelope_bytes bytea not null,
    command_envelope_sha256 varchar(64) not null,
    command_envelope_size_bytes bigint not null,
    canonical_proposal_source_bytes bytea not null,
    target_proposal_sha256 varchar(64) not null,
    proposal_source_size_bytes bigint not null,
    canonical_result_envelope_bytes bytea not null,
    result_envelope_sha256 varchar(64) not null,
    result_envelope_size_bytes bigint not null,
    checkpoint_ns varchar(128) not null,
    registry_binding_sha256 varchar(64) not null,
    tool_policy_version varchar(128) not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_graph_result_set
        foreign key (frame_set_id) references intake_parallel_frame_set(frame_set_id),
    constraint fk_intake_parallel_graph_result_proposal
        foreign key (
            proposal_artifact_id, frame_set_id, input_set_sha256, proposal_sha256
        ) references intake_parallel_proposal_artifact(
            artifact_id, frame_set_id, input_set_sha256, proposal_sha256
        ) deferrable initially deferred,
    constraint uq_intake_parallel_graph_result_exact
        unique (
            result_artifact_id, frame_set_id, input_set_sha256, graph_result_sha256
        ),
    constraint ck_intake_parallel_graph_result_schema
        check (schema_version = 'room-graph-result.v1'),
    constraint ck_intake_parallel_graph_result_reference
        check (
            result_artifact_id = 'intake.graph-result.' || left(graph_result_sha256, 32)
            and result_ref = 'urn:target-e2e:result:intake:' || graph_result_sha256
            and input_set_sha256 ~ '^[0-9a-f]{64}$'
            and graph_result_sha256 ~ '^[0-9a-f]{64}$'
            and proposal_artifact_id = 'intake.proposal.' || left(proposal_sha256, 32)
            and proposal_sha256 ~ '^[0-9a-f]{64}$'
            and command_envelope_sha256 ~ '^[0-9a-f]{64}$'
            and target_proposal_sha256 ~ '^[0-9a-f]{64}$'
            and result_envelope_sha256 ~ '^[0-9a-f]{64}$'
            and registry_binding_sha256 ~ '^[0-9a-f]{64}$'
            and length(btrim(checkpoint_ns)) between 1 and 128
            and length(btrim(tool_policy_version)) between 1 and 128
        ),
    constraint ck_intake_parallel_graph_result_sizes
        check (
            graph_result_size_bytes between 2 and 131072
            and graph_result_size_bytes = octet_length(canonical_graph_result_bytes)
            and command_envelope_size_bytes between 2 and 65536
            and command_envelope_size_bytes = octet_length(canonical_command_envelope_bytes)
            and proposal_source_size_bytes between 2 and 65536
            and proposal_source_size_bytes = octet_length(canonical_proposal_source_bytes)
            and result_envelope_size_bytes between 2 and 131072
            and result_envelope_size_bytes = octet_length(canonical_result_envelope_bytes)
        )
);

alter table intake_parallel_frame_set
    add column graph_result_artifact_id varchar(128);

alter table intake_parallel_frame_set
    drop constraint ck_intake_parallel_frame_set_state_fields,
    add constraint ck_intake_parallel_frame_set_state_fields_v2
        check (
            (assembly_state = 'COLLECTING'
                and input_set_sha256 is null
                and proposal_artifact_id is null
                and proposal_sha256 is null
                and graph_result_artifact_id is null
                and graph_result_sha256 is null
                and terminal_receipt_id is null
                and failure_code is null
                and ready_at is null and committed_at is null and failed_at is null)
            or
            (assembly_state = 'READY'
                and input_set_sha256 is not null
                and proposal_artifact_id is not null
                and proposal_sha256 is not null
                and graph_result_artifact_id is not null
                and graph_result_sha256 is not null
                and terminal_receipt_id is null
                and failure_code is null
                and ready_at is not null and committed_at is null and failed_at is null)
            or
            (assembly_state = 'COMMITTED'
                and input_set_sha256 is not null
                and proposal_artifact_id is not null
                and proposal_sha256 is not null
                and graph_result_artifact_id is not null
                and graph_result_sha256 is not null
                and terminal_receipt_id is not null
                and failure_code is null
                and ready_at is not null and committed_at is not null and failed_at is null)
            or
            (assembly_state = 'FAILED_UNCOMMITTED'
                and terminal_receipt_id is null
                and failure_code is not null
                and committed_at is null and failed_at is not null
                and (
                    (input_set_sha256 is null
                        and proposal_artifact_id is null
                        and proposal_sha256 is null
                        and graph_result_artifact_id is null
                        and graph_result_sha256 is null
                        and ready_at is null)
                    or
                    (input_set_sha256 is not null
                        and proposal_artifact_id is not null
                        and proposal_sha256 is not null
                        and graph_result_artifact_id is not null
                        and graph_result_sha256 is not null
                        and ready_at is not null)
                ))
        );

alter table intake_parallel_frame_set
    add constraint fk_intake_parallel_frame_set_graph_result
        foreign key (
            graph_result_artifact_id, frame_set_id, input_set_sha256, graph_result_sha256
        ) references intake_parallel_graph_result_artifact(
            result_artifact_id, frame_set_id, input_set_sha256, graph_result_sha256
        ) deferrable initially deferred;

create trigger trg_intake_parallel_graph_result_no_update
    before update or delete on intake_parallel_graph_result_artifact
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_graph_result_no_truncate
    before truncate on intake_parallel_graph_result_artifact
    for each statement execute function reject_append_only_mutation();

create or replace function enforce_intake_parallel_frame_set_transition()
returns trigger
language plpgsql
as $$
begin
    if new.frame_set_id is distinct from old.frame_set_id
        or new.agent_run_id is distinct from old.agent_run_id
        or new.agent_run_attempt_id is distinct from old.agent_run_attempt_id
        or new.command_id is distinct from old.command_id
        or new.command_request_sha256 is distinct from old.command_request_sha256
        or new.event_binding_id is distinct from old.event_binding_id
        or new.thread_registration_id is distinct from old.thread_registration_id
        or new.logical_sequence is distinct from old.logical_sequence
        or new.binding_generation is distinct from old.binding_generation
        or new.authority_version is distinct from old.authority_version
        or new.context_envelope_sha256 is distinct from old.context_envelope_sha256
        or new.model_context_view_sha256 is distinct from old.model_context_view_sha256
        or new.execution_profile_id is distinct from old.execution_profile_id
        or new.projection_registry_version is distinct from old.projection_registry_version
        or new.model_profile_id is distinct from old.model_profile_id
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set authority is immutable';
    end if;
    if new.version <> old.version + 1 or new.updated_at < old.updated_at then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set version must advance exactly once';
    end if;
    if old.assembly_state = 'READY'
        and (
            new.input_set_sha256 is distinct from old.input_set_sha256
            or new.proposal_artifact_id is distinct from old.proposal_artifact_id
            or new.proposal_sha256 is distinct from old.proposal_sha256
            or new.graph_result_artifact_id is distinct from old.graph_result_artifact_id
            or new.graph_result_sha256 is distinct from old.graph_result_sha256
            or new.ready_at is distinct from old.ready_at
        )
    then
        raise exception using errcode = '23514',
            message = 'READY Intake parallel assembly artifact authority is immutable';
    end if;
    if old.assembly_state = 'COLLECTING'
        and new.assembly_state = 'FAILED_UNCOMMITTED'
        and (
            new.input_set_sha256 is not null
            or new.proposal_artifact_id is not null
            or new.proposal_sha256 is not null
            or new.graph_result_artifact_id is not null
            or new.graph_result_sha256 is not null
            or new.ready_at is not null
        )
    then
        raise exception using errcode = '23514',
            message = 'COLLECTING Intake parallel failure cannot invent artifact authority';
    end if;
    if old.assembly_state = 'COLLECTING'
        and new.assembly_state not in ('READY', 'FAILED_UNCOMMITTED')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set left COLLECTING illegally';
    end if;
    if old.assembly_state = 'READY'
        and new.assembly_state not in ('COMMITTED', 'FAILED_UNCOMMITTED')
    then
        raise exception using errcode = '23514',
            message = 'Intake parallel Frame-set left READY illegally';
    end if;
    if old.assembly_state in ('COMMITTED', 'FAILED_UNCOMMITTED') then
        raise exception using errcode = '23514',
            message = 'terminal Intake parallel Frame-set is immutable';
    end if;
    return new;
end
$$;
