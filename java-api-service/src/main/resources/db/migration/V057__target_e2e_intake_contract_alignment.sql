-- Target E2E admits the durable room proposal source while preserving the
-- historical Intake wire contract. Keep the replacement additive until the
-- existing rows have been proven compatible with the stronger constraint.
alter table case_intake_graph_thread_binding
    add constraint ck_intake_graph_thread_constants_v057
    check (
        schema_version = 'graph-private-thread-registration.v1'
        and room_type = 'INTAKE'
        and state_schema_version = 'intake-graph-state.v2'
        and (
            (
                graph_key = 'intake.v2'
                and output_schema_version = 'intake-turn-proposal.v2'
            )
            or (
                writer_mode = 'TEMPORAL'
                and graph_key = 'all-rooms.target-e2e.v1'
                and graph_version = 'target-e2e-graph.2026-07-27.1'
                and checkpoint_schema_version = 'target-e2e-checkpoint.v1'
                and output_schema_version in (
                    'intake-turn-proposal.v2',
                    'target-e2e-room-proposal-source.v1'
                )
            )
        )
    ) not valid;

alter table case_intake_graph_thread_binding
    validate constraint ck_intake_graph_thread_constants_v057;

alter table case_intake_graph_thread_binding
    drop constraint ck_intake_graph_thread_constants;

alter table case_intake_graph_thread_binding
    rename constraint ck_intake_graph_thread_constants_v057
        to ck_intake_graph_thread_constants;
