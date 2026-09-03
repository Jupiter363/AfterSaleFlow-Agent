-- Preserve one Graph-issued abandonment before Java marks only its exact current STARTED lanes
-- ambiguous. This is technical staging authority; it never writes the formal Intake proposal.

create table intake_parallel_frame_abandonment_receipt (
    abandonment_id varchar(128) primary key,
    frame_set_id varchar(128) not null,
    agent_run_id varchar(128) not null,
    agent_run_attempt_id varchar(128) not null,
    command_id varchar(128) not null,
    command_request_sha256 varchar(64) not null,
    thread_id varchar(64) not null,
    admission_receipt_sha256 varchar(64) not null,
    authority_sha256 varchar(64) not null,
    graph_execution_id varchar(128) not null,
    provider_call_count_before integer not null,
    provider_call_count_after integer not null,
    graph_owner_id varchar(128) not null,
    graph_fencing_token bigint not null,
    abandoned_at timestamptz not null,
    abandonment_sha256 varchar(64) not null unique,
    ambiguous_frame_types jsonb not null,
    canonical_graph_receipt_bytes bytea not null,
    receipt_size_bytes integer not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint uq_intake_parallel_frame_abandonment_receipt
        unique (frame_set_id, admission_receipt_sha256),
    constraint fk_intake_parallel_frame_abandonment_frame_set
        foreign key (
            frame_set_id,
            agent_run_id,
            agent_run_attempt_id,
            command_id,
            command_request_sha256
        ) references intake_parallel_frame_set (
            frame_set_id,
            agent_run_id,
            agent_run_attempt_id,
            command_id,
            command_request_sha256
        ),
    constraint fk_intake_parallel_frame_abandonment_admission
        foreign key (frame_set_id, admission_receipt_sha256)
        references intake_parallel_admission_receipt_history (
            frame_set_id, receipt_sha256
        ),
    constraint ck_intake_parallel_frame_abandonment_identity
        check (
            abandonment_id = 'parallel-receipt-abandonment.'
                || left(admission_receipt_sha256, 24)
                || '.' || graph_fencing_token::text
            and graph_execution_id = 'parallel-receipt-execution.'
                || left(admission_receipt_sha256, 24)
                || '.' || graph_fencing_token::text
            and command_request_sha256 ~ '^[0-9a-f]{64}$'
            and admission_receipt_sha256 ~ '^[0-9a-f]{64}$'
            and authority_sha256 ~ '^[0-9a-f]{64}$'
            and abandonment_sha256 ~ '^[0-9a-f]{64}$'
            and graph_fencing_token >= 1
            and provider_call_count_before >= 0
            and provider_call_count_after > provider_call_count_before
        ),
    constraint ck_intake_parallel_frame_abandonment_lanes
        check (
            jsonb_typeof(ambiguous_frame_types) = 'array'
            and jsonb_array_length(ambiguous_frame_types) between 1 and 3
            and ambiguous_frame_types <@ '[
                "DIALOGUE_FRAME", "DOSSIER_FRAME", "QUALITY_FRAME"
            ]'::jsonb
        ),
    constraint ck_intake_parallel_frame_abandonment_bytes
        check (
            receipt_size_bytes between 2 and 65536
            and receipt_size_bytes = octet_length(canonical_graph_receipt_bytes)
        )
);

create trigger trg_intake_parallel_frame_abandonment_no_update
    before update or delete on intake_parallel_frame_abandonment_receipt
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_frame_abandonment_no_truncate
    before truncate on intake_parallel_frame_abandonment_receipt
    for each statement execute function reject_append_only_mutation();

-- Extend the reviewed physical purge before admission history and Frame-set rows are removed.
do $migration$
declare
    purge_definition text;
    old_anchor constant text :=
        'delete from intake_parallel_failure_termination_receipt' || chr(10) ||
        '    where frame_set_id in (';
    new_anchor constant text :=
        'delete from intake_parallel_frame_abandonment_receipt' || chr(10) ||
        '    where frame_set_id in (' || chr(10) ||
        '        select frame_set_id from intake_parallel_frame_set' || chr(10) ||
        '        where case_id = p_case_id' || chr(10) ||
        '    );' || chr(10) ||
        '    delete from intake_parallel_failure_termination_receipt' || chr(10) ||
        '    where frame_set_id in (';
begin
    select pg_get_functiondef(
        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
    ) into purge_definition;

    if position(old_anchor in purge_definition) = 0 then
        raise exception 'V092 could not locate the parallel failure purge anchor';
    end if;
    execute replace(purge_definition, old_anchor, new_anchor);
end;
$migration$;

revoke all on intake_parallel_frame_abandonment_receipt from public;
