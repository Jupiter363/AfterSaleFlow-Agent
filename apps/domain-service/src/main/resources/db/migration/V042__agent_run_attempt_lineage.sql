-- Add proof-carrying AgentRun lineage without inferring facts for pre-change attempts.
-- Existing V1 and SHADOW rows remain readable with a null lineage_schema_version.

alter table agent_run
    add column lineage_schema_version varchar(64),
    add column logical_input_hash varchar(64);

alter table agent_run
    add constraint ck_agent_run_logical_input_hash
        check (
            logical_input_hash is null
            or logical_input_hash ~ '^[0-9a-f]{64}$'
        ),
    add constraint ck_agent_run_lineage_shape
        check (
            (lineage_schema_version is null and logical_input_hash is null)
            or (
                lineage_schema_version = 'agent-run-lineage.v1'
                and logical_input_hash is not null
            )
        );

alter table agent_run_attempt
    add column lineage_schema_version varchar(64),
    add column command_id varchar(128),
    add column command_request_hash varchar(64),
    add column logical_input_hash varchar(64),
    add column command_json jsonb,
    add column previous_attempt_id varchar(128),
    add column reset_required boolean not null default false,
    add column public_sequence_offset integer not null default 0,
    add column termination_code varchar(128);

alter table agent_run_attempt
    add constraint fk_agent_run_attempt_previous
        foreign key (previous_attempt_id, agent_run_id)
        references agent_run_attempt(id, agent_run_id),
    add constraint ck_agent_run_attempt_lineage_hashes
        check (
            (command_request_hash is null
                or command_request_hash ~ '^[0-9a-f]{64}$')
            and (logical_input_hash is null
                or logical_input_hash ~ '^[0-9a-f]{64}$')
        ),
    add constraint ck_agent_run_attempt_sequence_offset
        check (
            public_sequence_offset between 0 and 1
            and public_sequence_offset = case when reset_required then 1 else 0 end
        ),
    add constraint ck_agent_run_attempt_lineage_shape
        check (
            (
                lineage_schema_version is null
                and command_id is null
                and command_request_hash is null
                and logical_input_hash is null
                and command_json is null
                and previous_attempt_id is null
                and not reset_required
                and public_sequence_offset = 0
            )
            or (
                lineage_schema_version = 'agent-run-attempt-lineage.v1'
                and command_id is not null
                and command_request_hash is not null
                and logical_input_hash is not null
                and command_json is not null
                and (
                    (attempt_no = 1 and previous_attempt_id is null)
                    or (attempt_no > 1 and previous_attempt_id is not null)
                )
            )
        );

create unique index uq_agent_run_attempt_command
    on agent_run_attempt(agent_run_id, command_id)
    where command_id is not null;

create index idx_agent_run_attempt_previous
    on agent_run_attempt(agent_run_id, previous_attempt_id)
    where previous_attempt_id is not null;
