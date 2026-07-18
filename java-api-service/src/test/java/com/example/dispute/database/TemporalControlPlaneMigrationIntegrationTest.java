package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TemporalControlPlaneMigrationIntegrationTest {

    private static final String DATABASE_NAME = "temporal_control_plane";
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
    void upgradesLegacyCasesWithoutStartingWorkflowsAndEnforcesControlPlaneContracts()
            throws SQLException {
        String jdbcUrl = jdbcUrl();
        Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .target("38")
                .load()
                .migrate();

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertLegacyCase(connection);
        }

        var v040Result =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .target("40")
                        .load()
                        .migrate();
        assertThat(v040Result.migrationsExecuted).isEqualTo(2);

        var result =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(4);

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(loadTables(connection))
                    .contains(
                            "case_command",
                            "case_command_outbox",
                            "case_process_projection",
                            "case_room_epoch",
                            "room_epoch_bootstrap_outbox",
                            "domain_operation",
                            "process_reconciliation_issue",
                            "immutable_payload_snapshot",
                            "agent_execution_manifest");

            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select writer_mode || ':' || room_epoch || ':' ||
                                           process_revision || ':' || fencing_token
                                    from case_process_projection
                                    where case_id = 'CASE_LEGACY_CONTROL'
                                    """))
                    .isEqualTo("LEGACY:0:0:0");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select writer_mode || ':' || room_epoch || ':' ||
                                           process_revision || ':' || fencing_token
                                    from case_room_epoch
                                    where case_id = 'CASE_LEGACY_CONTROL'
                                      and room_type = 'EVIDENCE'
                                    """))
                    .isEqualTo("LEGACY:0:0:0");
            assertThat(
                            count(
                                    connection,
                                    """
                                    select count(*)
                                    from case_process_projection
                                    where temporal_workflow_id is not null
                                       or temporal_run_id is not null
                                    """))
                    .isZero();
            assertThat(count(connection, "select count(*) from case_command_outbox"))
                    .isZero();
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select selection_schema_version || ':' ||
                                           process_contract_version || ':' ||
                                           workflow_type || ':' || temporal_build_id || ':' ||
                                           graph_key || ':' || graph_version || ':' ||
                                           checkpoint_schema_version || ':' || stream_protocol
                                      from case_room_epoch
                                     where case_id = 'CASE_LEGACY_CONTROL'
                                       and room_type = 'EVIDENCE'
                                    """))
                    .isEqualTo(
                            "room-epoch-selection.v1:case-process-contract.v1:"
                                    + "LegacyJavaRoomState:legacy-java.v1:evidence.legacy:"
                                    + "legacy.v1:legacy-checkpoint.v1:agent_stream.v1");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select character_maximum_length::text
                                      from information_schema.columns
                                     where table_schema = 'public'
                                       and table_name = 'case_room_epoch'
                                       and column_name = 'lifecycle_status'
                                    """))
                    .isEqualTo("24");

            assertCommandConstraints(connection);
            assertSnapshotAndManifestConstraints(connection);
            assertRoomEpochLifecycleConstraints(connection);
        }
    }

    @Test
    void rejectsAnExistingV040ActiveEpochWithoutARealRoomBinding() throws SQLException {
        String schema = "missing_room_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target("38")
                .load()
                .migrate();

        try (Connection connection = schemaConnection(schema)) {
            insertLegacyCase(connection);
            execute(connection, "delete from case_room where id = 'ROOM_LEGACY_CONTROL'");
        }

        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target("40.2")
                .load()
                .migrate();

        assertThatThrownBy(
                        () ->
                                Flyway.configure()
                                        .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                                        .locations("classpath:db/migration")
                                        .schemas(schema)
                                        .defaultSchema(schema)
                                        .createSchemas(true)
                                        .load()
                                        .migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(
                        "an ACTIVE epoch has no valid room binding");
    }

    private static void insertLegacyCase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_LEGACY_CONTROL', 'user-control', 'merchant-control',
                        'legacy-control-idempotency', 'DISPUTE', 'EVIDENCE_OPEN',
                        'USER', 'user-control', 'MERCHANT', 'merchant-control',
                        'HIGH', 'Legacy migration case',
                        'Must remain owned by the legacy writer after migration.',
                        'EVIDENCE', 'migration-test', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (
                        'ROOM_LEGACY_CONTROL', 'CASE_LEGACY_CONTROL', 'EVIDENCE',
                        'OPEN', now(), 'migration-test', 'migration-test'
                    )
                    """);
        }
    }

    private static void assertCommandConstraints(Connection connection) throws SQLException {
        execute(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) values (
                    'CMD_ROW_1', 'command-1', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 1, 'EVIDENCE_SUBMIT', 'EVIDENCE', 0,
                    'user-control', 'USER', '["case:write"]',
                    'evidence-command.v1', 'urn:payload:command-1',
                    repeat('a', 64), 12, 0, now(), now() + interval '5 minutes',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    repeat('b', 64), 'PENDING_ORCHESTRATION'
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_DUPLICATE_ID', command_id, tenant_surrogate, case_id,
                    2, command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version, payload_uri,
                    payload_sha256, payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                from case_command where id = 'CMD_ROW_1'
                """,
                "uq_case_command_tenant_command");

        assertSqlFails(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_DUPLICATE_SEQUENCE', 'command-2', tenant_surrogate,
                    case_id, case_command_sequence, command_type, room_type,
                    room_epoch, actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision, occurred_at,
                    deadline_at, traceparent, request_hash, command_status
                from case_command where id = 'CMD_ROW_1'
                """,
                "uq_case_command_case_sequence");

        assertSqlFails(
                connection,
                "update case_process_projection set process_revision = -1 "
                        + "where case_id = 'CASE_LEGACY_CONTROL'",
                "revisions, sequences, update time and version cannot move backward");

        execute(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_2', 'command-2', tenant_surrogate, case_id,
                    2, command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version,
                    'urn:payload:command-2', payload_sha256, payload_size_bytes,
                    expected_process_revision, occurred_at, deadline_at,
                    traceparent, request_hash, command_status
                from case_command where id = 'CMD_ROW_1'
                """);

        execute(
                connection,
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at
                ) values (
                    'OUTBOX_ROW_1', 'CMD_ROW_1', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 'case:CASE_LEGACY_CONTROL',
                    'CaseProcessWorkflow', 'case-control', 'UPDATE_WITH_START',
                    'command-1', 'PENDING', now()
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at
                ) values (
                    'OUTBOX_WRONG_COMMAND', 'CMD_ROW_2', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 'case:CASE_LEGACY_CONTROL:wrong',
                    'CaseProcessWorkflow', 'case-control', 'UPDATE_WITH_START',
                    'different-command-id', 'PENDING', now()
                )
                """,
                "fk_case_command_outbox_command");
    }

    private static void assertSnapshotAndManifestConstraints(Connection connection)
            throws SQLException {
        execute(
                connection,
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type,
                    source_type, source_id, schema_version, object_uri,
                    object_version, content_sha256, size_bytes, visibility,
                    created_by
                ) values (
                    'SNAPSHOT_1', 'tenant-control', 'CASE_LEGACY_CONTROL',
                    'EVIDENCE', 'COMMAND_INPUT', 'CASE_COMMAND', 'CMD_ROW_1',
                    'evidence-command.v1', 'urn:payload:command-1', 'v1',
                    repeat('c', 64), 12, 'PARTIES', 'migration-test'
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, snapshot_type, source_type,
                    source_id, schema_version, object_uri, content_sha256,
                    size_bytes, visibility, created_by
                ) values (
                    'SNAPSHOT_BAD_HASH', 'tenant-control', 'CASE_LEGACY_CONTROL',
                    'COMMAND_INPUT', 'CASE_COMMAND', 'CMD_ROW_BAD',
                    'evidence-command.v1', 'urn:payload:bad', 'NOT-A-SHA256',
                    1, 'INTERNAL', 'migration-test'
                )
                """,
                "ck_immutable_payload_snapshot_hash");

        execute(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_1', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-1', 'urn:manifest:logical-run-1', repeat('d', 64),
                    'SNAPSHOT_1', repeat('c', 64), 'COMPLETED', now()
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_DUPLICATE', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-1', 'urn:manifest:logical-run-1-duplicate',
                    repeat('e', 64), 'SNAPSHOT_1', repeat('c', 64), 'COMPLETED', now()
                )
                """,
                "uq_agent_execution_manifest_logical_run");

        assertSqlFails(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_WRONG_OUTPUT_HASH', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-wrong-output', 'urn:manifest:wrong-output',
                    repeat('f', 64), 'SNAPSHOT_1', repeat('f', 64),
                    'COMPLETED', now()
                )
                """,
                "fk_agent_execution_manifest_output");

        assertSqlFails(
                connection,
                "update immutable_payload_snapshot set visibility = 'INTERNAL' "
                        + "where id = 'SNAPSHOT_1'",
                "immutable_payload_snapshot is append-only");
        assertSqlFails(
                connection,
                "delete from agent_execution_manifest where id = 'MANIFEST_1'",
                "agent_execution_manifest is append-only");

        assertThat(columns(connection, "immutable_payload_snapshot"))
                .doesNotContain("payload_json", "payload_body", "content_body");
        assertThat(columns(connection, "agent_execution_manifest"))
                    .doesNotContain("manifest_json", "prompt_text", "model_response");
    }

    private static void assertRoomEpochLifecycleConstraints(Connection connection)
            throws SQLException {
        execute(
                connection,
                """
                update case_room_epoch
                   set process_revision = 2,
                       room_revision = 1,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """);
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set process_revision = 1,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "revisions, update time and version cannot move backward");

        execute(
                connection,
                """
                update case_room_epoch
                   set lifecycle_status = 'TERMINAL',
                       process_revision = 3,
                       room_revision = 2,
                       terminal_at = updated_at + interval '1 second',
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """);
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set lifecycle_status = 'ACTIVE',
                       terminal_at = null,
                       process_revision = 4,
                       room_revision = 3,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "TERMINAL lifecycle is immutable");
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set terminal_at = terminal_at + interval '1 second'
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "TERMINAL lifecycle is immutable");
    }

    private String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRESQL.getHost()
                + ":"
                + POSTGRESQL.getMappedPort(5432)
                + "/"
                + DATABASE_NAME;
    }

    private Connection schemaConnection(String schema) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl() + "?currentSchema=" + schema, USERNAME, PASSWORD);
    }

    private static Set<String> loadTables(Connection connection) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select table_name
                                from information_schema.tables
                                where table_schema = 'public'
                                  and table_type = 'BASE TABLE'
                                """)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static Set<String> columns(Connection connection, String table)
            throws SQLException {
        Set<String> values = new TreeSet<>();
        try (var statement =
                connection.prepareStatement(
                        """
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return values;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void assertSqlFails(
            Connection connection, String sql, String expectedMessage) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }
}
