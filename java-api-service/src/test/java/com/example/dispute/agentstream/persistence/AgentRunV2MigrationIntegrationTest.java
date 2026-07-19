package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AgentRunV2MigrationIntegrationTest {

    private static final String DATABASE_NAME = "agent_run_v2_migration";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE_NAME)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    @Test
    void expandsAndBackfillsLegacyRowsWithoutRewritingTheirManifest() throws SQLException {
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ':'
                        + POSTGRESQL.getMappedPort(5432)
                        + '/'
                        + DATABASE_NAME;
        Flyway beforeV041 =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .target("40.4")
                        .load();
        beforeV041.migrate();

        String manifestBefore;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertLegacyFixture(connection);
            manifestBefore = scalar(connection, "select to_jsonb(manifest)::text from agent_execution_manifest manifest where id = 'MANIFEST_LEGACY_V2'");
        }

        Flyway latest =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(latest.migrate().migrationsExecuted).isZero();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select protocol || ':' || logical_idempotency_key || ':' ||
                                           finalization_status || ':' || committed_attempt_id
                                    from agent_run where id = 'RUN_LEGACY_V2'
                                    """))
                    .isEqualTo("agent_stream.v1:legacy-stream-key:LEGACY_COMMITTED:RUN_LEGACY_V2");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select id || ':' || attempt_no || ':' || attempt_status || ':' ||
                                           executor_kind || ':' || coalesce(total_tokens, 0)
                                    from agent_run_attempt where agent_run_id = 'RUN_LEGACY_V2'
                                    """))
                    .isEqualTo("RUN_LEGACY_V2:1:COMPLETED:LEGACY_WORKER:21");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select agent_run_attempt_id || ':' || stream_protocol
                                    from agent_run_stream_event where id = 'EVENT_LEGACY_V2'
                                    """))
                    .isEqualTo("RUN_LEGACY_V2:agent_stream.v1");
            assertThat(
                            scalar(
                                    connection,
                                    "select to_jsonb(manifest)::text from agent_execution_manifest manifest where id = 'MANIFEST_LEGACY_V2'"))
                    .isEqualTo(manifestBefore);
            assertThat(
                            scalar(
                                    connection,
                                    "select coalesce(lineage_schema_version, 'legacy') from agent_run_attempt where id = 'RUN_LEGACY_V2'"))
                    .isEqualTo("legacy");

            assertRollbackV1WriterCompatibility(connection);
            assertV2Uniqueness(connection);
            assertLineageConstraints(connection);
        }
    }

    private static void insertLegacyFixture(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_LEGACY_V2', 'user-v2', 'merchant-v2', 'legacy-v2-idem',
                        'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-v2',
                        'MERCHANT', 'merchant-v2', 'MEDIUM', 'Legacy V2 migration',
                        'Legacy AgentRun row', 'EVIDENCE', 'migration-test', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, model,
                        run_status, token_usage, started_at, completed_at,
                        trace_id, created_by, stream_request_hash,
                        stream_result_json, stream_idempotency_key, updated_at
                    ) values (
                        'RUN_LEGACY_V2', 'CASE_LEGACY_V2', 'legacy-agent', 'SYSTEM',
                        'legacy-profile', 'legacy-prompt', 'legacy-skill', 'legacy-rules',
                        'legacy-model', 'COMPLETED', 21, now() - interval '1 second', now(),
                        'legacy-trace', 'migration-test', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        '{"status":"completed"}', 'legacy-stream-key', now()
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_event (
                        id, agent_run_id, sequence_no, event_type, payload_json, created_by
                    ) values (
                        'EVENT_LEGACY_V2', 'RUN_LEGACY_V2', 0, 'visible_delta',
                        '{"delta":"legacy"}', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into immutable_payload_snapshot (
                        id, tenant_surrogate, case_id, room_type, snapshot_type,
                        source_type, source_id, schema_version, object_uri,
                        content_sha256, size_bytes, visibility, created_by
                    ) values (
                        'SNAP_LEGACY_V2', 'legacy-default', 'CASE_LEGACY_V2', 'HEARING',
                        'AGENT_OUTPUT', 'TEST', 'RUN_LEGACY_V2', 'legacy-output.v1',
                        'urn:test:legacy-output',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        1, 'INTERNAL', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_execution_manifest (
                        id, schema_version, tenant_surrogate, case_id, room_type,
                        room_epoch, process_revision, fencing_token,
                        logical_agent_run_id, attempt_id, manifest_uri,
                        input_snapshot_refs_json, output_snapshot_id, output_sha256,
                        terminal_status, finalized_at
                    ) values (
                        'MANIFEST_LEGACY_V2', 'agent-execution-manifest.legacy-ref.v1',
                        'legacy-default', 'CASE_LEGACY_V2', 'HEARING', 0, 0, 0,
                        'RUN_LEGACY_V2', 'RUN_LEGACY_V2', 'urn:legacy:RUN_LEGACY_V2',
                        '[]', 'SNAP_LEGACY_V2',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        'LEGACY_IMPORTED', now()
                    )
                    """);
        }
    }

    private static void assertRollbackV1WriterCompatibility(Connection connection)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, run_status,
                        started_at, trace_id, created_by, stream_operation,
                        stream_idempotency_key, updated_at
                    ) values (
                        'RUN_ROLLBACK_V1', 'CASE_LEGACY_V2', 'rollback-agent', 'SYSTEM',
                        'v1', 'v1', 'v1', 'agent_stream.v1', 'PENDING', now(),
                        'rollback-trace', 'rollback-writer', 'EVIDENCE_ANALYZE',
                        'rollback-v1-key', now()
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_event (
                        id, agent_run_id, sequence_no, event_type, payload_json, created_by
                    ) values (
                        'EVENT_ROLLBACK_V1', 'RUN_ROLLBACK_V1', 0, 'start',
                        '{"status":"started"}', 'rollback-writer'
                    )
                    """);
            statement.executeUpdate(
                    """
                    update agent_run
                    set run_status = 'COMPLETED',
                        stream_result_json = '{"status":"completed"}',
                        completed_at = now(),
                        updated_at = now()
                    where id = 'RUN_ROLLBACK_V1'
                    """);
        }

        assertThat(
                        scalar(
                                connection,
                                """
                                select run.logical_idempotency_key || ':' || attempt.id || ':' ||
                                       attempt.attempt_status || ':' || run.finalization_status
                                from agent_run run
                                join agent_run_attempt attempt on attempt.agent_run_id = run.id
                                where run.id = 'RUN_ROLLBACK_V1'
                                """))
                .isEqualTo("rollback-v1-key:RUN_ROLLBACK_V1:COMPLETED:LEGACY_COMMITTED");
        assertThat(
                        scalar(
                                connection,
                                """
                                select agent_run_attempt_id || ':' || stream_protocol
                                from agent_run_stream_event where id = 'EVENT_ROLLBACK_V1'
                                """))
                .isEqualTo("RUN_ROLLBACK_V1:agent_stream.v1");
    }

    private static void assertV2Uniqueness(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, run_status,
                        started_at, trace_id, created_by, stream_operation,
                        stream_idempotency_key, tenant_surrogate, protocol,
                        logical_idempotency_key, executor_kind, finalization_status,
                        room_epoch_id, room_type, room_epoch, process_revision,
                        fencing_token, request_hash, attempt_limit, deadline_at
                    ) values (
                        'RUN_DB_V2', 'CASE_LEGACY_V2', 'agent-v2', 'SYSTEM', 'v2',
                        'v2', 'v2', 'agent-stream.v2', 'RUNNING', now(), 'trace-v2',
                        'migration-test', 'EVIDENCE_ANALYZE', 'db-v2-key',
                        'tenant-v2', 'agent-stream.v2', 'db-v2-key',
                        'TEMPORAL_ACTIVITY', 'UNCOMMITTED', 'EPOCH_DB_V2', 'EVIDENCE',
                        1, 1, 1,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        2, now() + interval '10 minutes'
                    )
                    """);
            statement.executeUpdate(attemptInsert("ATTEMPT_DB_V2_1", 1));
            statement.executeUpdate(attemptInsert("ATTEMPT_DB_V2_2", 2));
            statement.executeUpdate(eventInsert("EVENT_DB_V2_1", "ATTEMPT_DB_V2_1", 0));
            statement.executeUpdate(eventInsert("EVENT_DB_V2_2", "ATTEMPT_DB_V2_2", 0));
        }

        assertUniqueViolation(
                connection,
                attemptInsert("ATTEMPT_DB_V2_DUPLICATE", 2),
                "uq_agent_run_attempt_number");
        assertUniqueViolation(
                connection,
                eventInsert("EVENT_DB_V2_DUPLICATE", "ATTEMPT_DB_V2_2", 0),
                "uq_agent_run_stream_event_attempt_sequence_v2");
        assertUniqueViolation(
                connection,
                """
                insert into agent_run (
                    id, case_id, agent_id, agent_role, profile_version,
                    prompt_version, skill_version, ruleset_version, run_status,
                    started_at, trace_id, created_by, logical_idempotency_key
                ) values (
                    'RUN_DB_LOGICAL_DUP', 'CASE_LEGACY_V2', 'legacy-agent', 'SYSTEM',
                    'v1', 'v1', 'v1', 'v1', 'PENDING', now(), 'trace-duplicate',
                    'migration-test', 'db-v2-key'
                )
                """,
                "uq_agent_run_logical_idempotency_v2");
        assertUniqueViolation(
                connection,
                """
                insert into agent_run_stream_event (
                    id, agent_run_id, sequence_no, event_type, payload_json, created_by
                ) values (
                    'EVENT_LEGACY_V2_DUP', 'RUN_LEGACY_V2', 0, 'visible_delta',
                    '{"delta":"duplicate"}', 'migration-test'
                )
                """,
                "uq_agent_run_stream_event_sequence");
    }

    private static String attemptInsert(String id, long attemptNo) {
        return """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind,
                    graph_key, graph_version, checkpoint_schema_version,
                    model_profile_id, prompt_version, output_schema_version,
                    policy_version, guardrail_version, request_hash,
                    started_at, created_by
                ) values (
                    '%s', 'RUN_DB_V2', %d, 'RUNNING', 'TEMPORAL_ACTIVITY',
                    'evidence.graph', 'graph-v2', 'checkpoint-v2',
                    'model-v2', 'prompt-v2', 'result-v1', 'policy-v2', 'guardrail-v2',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    now(), 'migration-test'
                )
                """.formatted(id, attemptNo);
    }

    private static String eventInsert(String id, String attemptId, long sequenceNo) {
        return """
                insert into agent_run_stream_event (
                    id, agent_run_id, agent_run_attempt_id, sequence_no,
                    event_type, payload_json, stream_protocol, audience,
                    payload_hash, created_by
                ) values (
                    '%s', 'RUN_DB_V2', '%s', %d, 'attempt_started',
                    '{"attempt":1}', 'agent-stream.v2', 'USER',
                    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                    'migration-test'
                )
                """.formatted(id, attemptId, sequenceNo);
    }

    private static void assertLineageConstraints(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    update agent_run
                    set lineage_schema_version = 'agent-run-lineage.v1',
                        logical_input_hash = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
                    where id = 'RUN_DB_V2'
                    """);
            statement.executeUpdate(
                    lineageAttemptInsert(
                            "ATTEMPT_DB_V2_3",
                            3,
                            "COMMAND_DB_V2_3",
                            "ATTEMPT_DB_V2_2",
                            true,
                            1));
        }

        assertUniqueViolation(
                connection,
                lineageAttemptInsert(
                        "ATTEMPT_DB_V2_4_DUP_COMMAND",
                        4,
                        "COMMAND_DB_V2_3",
                        "ATTEMPT_DB_V2_3",
                        false,
                        0),
                "uq_agent_run_attempt_command");
        assertCheckViolation(
                connection,
                lineageAttemptInsert(
                        "ATTEMPT_DB_V2_4_BAD_OFFSET",
                        4,
                        "COMMAND_DB_V2_4",
                        "ATTEMPT_DB_V2_3",
                        true,
                        0),
                "ck_agent_run_attempt_sequence_offset");
    }

    private static String lineageAttemptInsert(
            String id,
            long attemptNo,
            String commandId,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset) {
        return """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind,
                    graph_key, graph_version, checkpoint_schema_version,
                    model_profile_id, prompt_version, output_schema_version,
                    policy_version, guardrail_version, request_hash,
                    lineage_schema_version, command_id, command_request_hash,
                    logical_input_hash, command_json, previous_attempt_id,
                    reset_required, public_sequence_offset, started_at, created_by
                ) values (
                    '%s', 'RUN_DB_V2', %d, 'RUNNING', 'TEMPORAL_ACTIVITY',
                    'evidence.graph', 'graph-v2', 'checkpoint-v2',
                    'model-v2', 'prompt-v2', 'result-v1', 'policy-v2', 'guardrail-v2',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'agent-run-attempt-lineage.v1', '%s',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '{"command_id":"%s"}', '%s', %s, %d, now(), 'migration-test'
                )
                """.formatted(
                id,
                attemptNo,
                commandId,
                commandId,
                previousAttemptId,
                resetRequired,
                publicSequenceOffset);
    }

    private static void assertUniqueViolation(
            Connection connection, String sql, String constraint) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure -> {
                            SQLException sqlFailure = (SQLException) failure;
                            assertThat(sqlFailure.getSQLState()).isEqualTo("23505");
                            assertThat(sqlFailure.getMessage()).contains(constraint);
                        });
    }

    private static void assertCheckViolation(
            Connection connection, String sql, String constraint) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure -> {
                            SQLException sqlFailure = (SQLException) failure;
                            assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
                            assertThat(sqlFailure.getMessage()).contains(constraint);
                        });
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
