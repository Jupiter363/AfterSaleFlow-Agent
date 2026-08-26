-- Freeze the Graph-first failure receipt before one local transaction closes the V4 AgentRun and
-- V081 technical assembly. Existing legacy FAILED_UNCOMMITTED rows remain readable, but every new
-- transition is required to have this immutable receipt by transaction commit.

alter table intake_parallel_frame_set
    add constraint uq_intake_parallel_frame_set_failure_authority
        unique (
            frame_set_id,
            agent_run_id,
            agent_run_attempt_id,
            command_id,
            command_request_sha256
        );

-- Every execute cycle publishes the exact admission receipt before crossing the HTTP boundary.
-- Frame-local retry may advance this authority once, so immutable history and a monotonic current
-- pointer are separate. Failure termination reads this pointer and never re-enters planning.
create table intake_parallel_admission_receipt_history (
    frame_set_id varchar(128) not null,
    receipt_generation bigint not null,
    receipt_sha256 varchar(64) not null,
    agent_run_id varchar(128) not null,
    agent_run_attempt_id varchar(128) not null,
    command_id varchar(128) not null,
    command_request_sha256 varchar(64) not null,
    java_receipt_id varchar(128) not null,
    authority_sha256 varchar(64) not null,
    canonical_receipt_bytes bytea not null,
    receipt_size_bytes integer not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (frame_set_id, receipt_generation),
    constraint uq_intake_parallel_admission_receipt_hash
        unique (frame_set_id, receipt_sha256),
    constraint uq_intake_parallel_admission_receipt_exact
        unique (frame_set_id, receipt_generation, receipt_sha256),
    constraint fk_intake_parallel_admission_receipt_frame_set
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
    constraint ck_intake_parallel_admission_receipt_identity
        check (
            receipt_generation > 0
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and command_request_sha256 ~ '^[0-9a-f]{64}$'
            and authority_sha256 ~ '^[0-9a-f]{64}$'
            and java_receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        ),
    constraint ck_intake_parallel_admission_receipt_bytes
        check (
            receipt_size_bytes between 2 and 12288
            and receipt_size_bytes = octet_length(canonical_receipt_bytes)
        )
);

create table intake_parallel_admission_receipt_authority (
    frame_set_id varchar(128) primary key,
    current_receipt_generation bigint not null,
    current_receipt_sha256 varchar(64) not null,
    version bigint not null default 0,
    updated_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_admission_receipt_current
        foreign key (
            frame_set_id,
            current_receipt_generation,
            current_receipt_sha256
        ) references intake_parallel_admission_receipt_history (
            frame_set_id,
            receipt_generation,
            receipt_sha256
        ),
    constraint ck_intake_parallel_admission_receipt_current
        check (
            current_receipt_generation > 0
            and current_receipt_sha256 ~ '^[0-9a-f]{64}$'
            and version >= 0
        )
);

create trigger trg_intake_parallel_admission_receipt_history_no_update
    before update or delete on intake_parallel_admission_receipt_history
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_admission_receipt_history_no_truncate
    before truncate on intake_parallel_admission_receipt_history
    for each statement execute function reject_append_only_mutation();

create function enforce_intake_parallel_admission_receipt_authority_transition()
returns trigger
language plpgsql
as $$
begin
    if new.frame_set_id is distinct from old.frame_set_id
        or new.current_receipt_generation <> old.current_receipt_generation + 1
        or new.current_receipt_sha256 is not distinct from old.current_receipt_sha256
        or new.version <> old.version + 1
        or new.updated_at < old.updated_at
    then
        raise exception using errcode = '23514',
            message = 'parallel Intake admission receipt authority drifted';
    end if;
    return new;
end
$$;

create trigger trg_intake_parallel_admission_receipt_authority_transition
    before update on intake_parallel_admission_receipt_authority
    for each row execute function
        enforce_intake_parallel_admission_receipt_authority_transition();

create trigger trg_intake_parallel_admission_receipt_authority_no_delete
    before delete on intake_parallel_admission_receipt_authority
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_admission_receipt_authority_no_truncate
    before truncate on intake_parallel_admission_receipt_authority
    for each statement execute function reject_append_only_mutation();

create table intake_parallel_failure_termination_receipt (
    receipt_id varchar(128) primary key,
    frame_set_id varchar(128) not null unique,
    agent_run_id varchar(128) not null,
    agent_run_attempt_id varchar(128) not null,
    command_id varchar(128) not null,
    command_request_sha256 varchar(64) not null,
    admission_receipt_sha256 varchar(64) not null,
    requested_failure_code varchar(128) not null,
    graph_command_status varchar(16) not null,
    graph_attempt_status varchar(16) not null,
    graph_error_code varchar(128) not null,
    graph_error_classification varchar(128) not null,
    provider_permit_statuses jsonb not null,
    receipt_sha256 varchar(64) not null unique,
    canonical_receipt_bytes bytea not null,
    receipt_size_bytes integer not null,
    created_at timestamptz not null default clock_timestamp(),
    constraint fk_intake_parallel_failure_receipt_frame_set
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
    constraint fk_intake_parallel_failure_receipt_admission
        foreign key (frame_set_id, admission_receipt_sha256)
        references intake_parallel_admission_receipt_history (
            frame_set_id, receipt_sha256
        ),
    constraint ck_intake_parallel_failure_receipt_identity
        check (
            receipt_id = 'parallel-failure-terminal.' ||
                left(admission_receipt_sha256, 24)
            and receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            and command_request_sha256 ~ '^[0-9a-f]{64}$'
            and admission_receipt_sha256 ~ '^[0-9a-f]{64}$'
            and receipt_sha256 ~ '^[0-9a-f]{64}$'
            and requested_failure_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
            and graph_error_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
            and graph_error_classification ~ '^[A-Z][A-Z0-9_]{2,127}$'
        ),
    constraint ck_intake_parallel_failure_receipt_terminal
        check (
            graph_command_status in ('ABORTED', 'CANCELLED')
            and graph_attempt_status in (
                'FAILED', 'LEASE_LOST', 'CANCELLED', 'ABSENT'
            )
            and jsonb_typeof(provider_permit_statuses) = 'array'
        ),
    constraint ck_intake_parallel_failure_receipt_bytes
        check (
            receipt_size_bytes between 2 and 65536
            and receipt_size_bytes = octet_length(canonical_receipt_bytes)
        )
);

create trigger trg_intake_parallel_failure_receipt_no_update
    before update or delete on intake_parallel_failure_termination_receipt
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_parallel_failure_receipt_no_truncate
    before truncate on intake_parallel_failure_termination_receipt
    for each statement execute function reject_append_only_mutation();

create or replace function require_intake_parallel_failure_receipt()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
        from intake_parallel_failure_termination_receipt receipt
        where receipt.frame_set_id = new.frame_set_id
          and receipt.agent_run_id = new.agent_run_id
          and receipt.agent_run_attempt_id = new.agent_run_attempt_id
          and receipt.command_id = new.command_id
          and receipt.command_request_sha256 = new.command_request_sha256
          and receipt.requested_failure_code = new.failure_code
    ) then
        raise exception using errcode = '23514',
            message = 'parallel Intake failure lacks its immutable Graph receipt';
    end if;
    return null;
end
$$;

create constraint trigger trg_intake_parallel_failure_receipt_required
after update of assembly_state on intake_parallel_frame_set
deferrable initially deferred
for each row
when (
    new.assembly_state = 'FAILED_UNCOMMITTED'
    and old.assembly_state is distinct from new.assembly_state
)
execute function require_intake_parallel_failure_receipt();

-- Extend the reviewer-audited physical purge graph without weakening append-only protection.
do $migration$
declare
    purge_definition text;
    old_anchor constant text :=
        'delete from intake_parallel_frame_set where case_id = p_case_id;';
    new_anchor constant text :=
        'delete from intake_parallel_failure_termination_receipt' || chr(10) ||
        '    where frame_set_id in (' || chr(10) ||
        '        select frame_set_id from intake_parallel_frame_set' || chr(10) ||
        '        where case_id = p_case_id' || chr(10) ||
        '    );' || chr(10) ||
        '    delete from intake_parallel_admission_receipt_authority' || chr(10) ||
        '    where frame_set_id in (' || chr(10) ||
        '        select frame_set_id from intake_parallel_frame_set' || chr(10) ||
        '        where case_id = p_case_id' || chr(10) ||
        '    );' || chr(10) ||
        '    delete from intake_parallel_admission_receipt_history' || chr(10) ||
        '    where frame_set_id in (' || chr(10) ||
        '        select frame_set_id from intake_parallel_frame_set' || chr(10) ||
        '        where case_id = p_case_id' || chr(10) ||
        '    );' || chr(10) ||
        '    delete from intake_parallel_frame_set where case_id = p_case_id;';
begin
    select pg_get_functiondef(
        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
    ) into purge_definition;

    if position(old_anchor in purge_definition) = 0 then
        raise exception 'V091 could not locate the parallel Frame-set purge anchor';
    end if;
    execute replace(purge_definition, old_anchor, new_anchor);
end;
$migration$;

revoke all on intake_parallel_failure_termination_receipt from public;
revoke all on intake_parallel_admission_receipt_history from public;
revoke all on intake_parallel_admission_receipt_authority from public;
