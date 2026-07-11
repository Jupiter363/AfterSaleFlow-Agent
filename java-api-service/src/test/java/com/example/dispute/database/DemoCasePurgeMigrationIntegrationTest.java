package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DemoCasePurgeMigrationIntegrationTest {

    private static final String DATABASE_NAME = "dispute_system";
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

    private static String jdbcUrl;

    @BeforeAll
    static void migrate() {
        jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ":"
                        + POSTGRESQL.getMappedPort(5432)
                        + "/"
                        + DATABASE_NAME;
        Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void purgesTheSimulatedCaseGraphAndRetainsAnIndependentAuditSnapshot()
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertSimulatedCaseGraph(connection, "TEMPLATE_SIMULATED_OMS");

            String auditId;
            try (var statement =
                    connection.prepareStatement(
                            "select purge_simulated_dispute_case(?, ?, ?)")) {
                statement.setString(1, "CASE_PURGE_DEMO");
                statement.setString(2, "reviewer-local");
                statement.setString(3, "PLATFORM_REVIEWER");
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    auditId = result.getString(1);
                }
            }

            assertThat(countById(connection, "fulfillment_dispute_case", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "case_room", "CASE_PURGE_DEMO")).isZero();
            assertThat(count(connection, "room_message", "CASE_PURGE_DEMO")).isZero();
            assertThat(count(connection, "case_timeline_event", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "room_turn_memory", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "case_access_session", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "agent_conversation_session", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "agent_session_dossier", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "agent_memory_entry", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "agent_a2a_message", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "notification", "CASE_PURGE_DEMO"))
                    .isZero();
            assertThat(count(connection, "audit_log", "CASE_PURGE_DEMO"))
                    .isZero();

            try (var statement =
                    connection.prepareStatement(
                            """
                            select case_id, source_system, reviewer_id,
                                   reviewer_role,
                                   case_snapshot_json ->> 'title',
                                   (related_counts_json ->> 'messages')::integer
                            from demo_case_purge_audit
                            where id = ?
                            """)) {
                statement.setString(1, auditId);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("CASE_PURGE_DEMO");
                    assertThat(result.getString(2)).isEqualTo("TEMPLATE_SIMULATED_OMS");
                    assertThat(result.getString(3)).isEqualTo("reviewer-local");
                    assertThat(result.getString(4)).isEqualTo("PLATFORM_REVIEWER");
                    assertThat(result.getString(5)).isEqualTo("Demo purge case");
                    assertThat(result.getInt(6)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void acceptsTheLegacySimulatedSourceButRejectsAdminAndFormalCases()
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertBareCase(
                    connection,
                    "CASE_PURGE_LEGACY",
                    "EXTERNAL_IMPORT",
                    "LLM_SIMULATED_OMS");
            callPurge(connection, "CASE_PURGE_LEGACY", "PLATFORM_REVIEWER");
            assertThat(countById(connection, "fulfillment_dispute_case", "CASE_PURGE_LEGACY"))
                    .isZero();

            insertBareCase(
                    connection,
                    "CASE_PURGE_ADMIN",
                    "EXTERNAL_IMPORT",
                    "TEMPLATE_SIMULATED_OMS");
            assertThatThrownBy(
                            () ->
                                    callPurge(
                                            connection,
                                            "CASE_PURGE_ADMIN",
                                            "ADMIN"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "only the platform reviewer can delete simulated cases");
            assertThat(countById(connection, "fulfillment_dispute_case", "CASE_PURGE_ADMIN"))
                    .isOne();

            insertBareCase(
                    connection,
                    "CASE_PURGE_FORMAL",
                    "INTAKE_CREATED",
                    null);
            assertThatThrownBy(
                            () ->
                                    callPurge(
                                            connection,
                                            "CASE_PURGE_FORMAL",
                                            "PLATFORM_REVIEWER"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("only simulated imported cases can be deleted");
            assertThat(countById(connection, "fulfillment_dispute_case", "CASE_PURGE_FORMAL"))
                    .isOne();
        }
    }

    @Test
    void appendOnlyRecordsStillRejectOrdinaryDeletion() throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertBareCase(
                    connection,
                    "CASE_PURGE_APPEND",
                    "EXTERNAL_IMPORT",
                    "TEMPLATE_SIMULATED_OMS");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        """
                        insert into case_room (
                            id, case_id, room_type, room_status, opened_at,
                            created_by, updated_by
                        ) values (
                            'ROOM_PURGE_APPEND', 'CASE_PURGE_APPEND', 'INTAKE',
                            'OPEN', now(), 'test', 'test'
                        )
                        """);
                statement.executeUpdate(
                        """
                        insert into room_message (
                            id, case_id, room_id, sequence_no, sender_type,
                            sender_role, sender_id, message_type, message_text,
                            idempotency_key, created_by
                        ) values (
                            'MESSAGE_PURGE_APPEND', 'CASE_PURGE_APPEND',
                            'ROOM_PURGE_APPEND', 1, 'PARTY', 'USER', 'user-local',
                            'PARTY_TEXT', 'demo message', 'purge-append-message',
                            'user-local'
                        )
                        """);
            }

            assertThatThrownBy(
                            () -> {
                                try (Statement statement = connection.createStatement()) {
                                    statement.executeUpdate(
                                            "delete from room_message where case_id = 'CASE_PURGE_APPEND'");
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("room_message is append-only");
        }
    }

    @Test
    void purgeFunctionExplicitlyCoversEveryCaseScopedTable() throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            Set<String> caseScopedTables = new HashSet<>();
            try (Statement statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    """
                                    select table_name
                                    from information_schema.columns
                                    where table_schema = 'public'
                                      and column_name = 'case_id'
                                      and table_name <> 'demo_case_purge_audit'
                                    """)) {
                while (result.next()) {
                    caseScopedTables.add(result.getString(1));
                }
            }

            String functionDefinition;
            try (Statement statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    """
                                    select pg_get_functiondef(
                                        'purge_simulated_dispute_case(varchar,varchar,varchar)'::regprocedure
                                    )
                                    """)) {
                assertThat(result.next()).isTrue();
                functionDefinition = result.getString(1).toLowerCase();
            }

            assertThat(caseScopedTables).isNotEmpty();
            for (String table : caseScopedTables) {
                assertThat(functionDefinition)
                        .as("purge SQL for case-scoped table %s", table)
                        .contains("delete from " + table);
            }
            assertThat(functionDefinition)
                    .contains("delete from fulfillment_dispute_case");
        }
    }

    private static void insertSimulatedCaseGraph(
            Connection connection, String sourceSystem) throws SQLException {
        insertBareCase(
                connection,
                "CASE_PURGE_DEMO",
                "EXTERNAL_IMPORT",
                sourceSystem);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (
                        'ROOM_PURGE_DEMO', 'CASE_PURGE_DEMO', 'INTAKE', 'OPEN', now(),
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into room_message (
                        id, case_id, room_id, sequence_no, sender_type, sender_role,
                        sender_id, message_type, message_text, idempotency_key, created_by
                    ) values (
                        'MESSAGE_PURGE_DEMO', 'CASE_PURGE_DEMO', 'ROOM_PURGE_DEMO', 1,
                        'PARTY', 'USER', 'user-local', 'PARTY_TEXT', 'demo message',
                        'purge-demo-message', 'user-local'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_timeline_event (
                        id, case_id, sequence_no, room_id, event_type, event_time,
                        created_by
                    ) values (
                        'EVENT_PURGE_DEMO', 'CASE_PURGE_DEMO', 1, 'ROOM_PURGE_DEMO',
                        'ROOM_MESSAGE_CREATED', now(), 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_access_session (
                        id, case_id, actor_id, actor_role, permission_level, created_by
                    ) values (
                        'ACCESS_PURGE_DEMO', 'CASE_PURGE_DEMO', 'user-local', 'USER',
                        'PARTY_USER', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_conversation_session (
                        id, case_id, room_type, actor_id, actor_role, agent_key,
                        access_session_id, prompt_profile_id, memory_policy_id,
                        conversation_scope, created_by
                    ) values (
                        'SESSION_PURGE_DEMO', 'CASE_PURGE_DEMO', 'INTAKE', 'user-local',
                        'USER', 'INTAKE_OFFICER', 'ACCESS_PURGE_DEMO', 'prompt-v1',
                        'memory-v1', 'CASE_PURGE_DEMO:INTAKE:user-local', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_session_dossier (
                        id, agent_session_id, case_id, room_type, actor_id, actor_role,
                        agent_key, created_by, updated_by
                    ) values (
                        'SESSION_DOSSIER_PURGE_DEMO', 'SESSION_PURGE_DEMO',
                        'CASE_PURGE_DEMO', 'INTAKE', 'user-local', 'USER',
                        'INTAKE_OFFICER', 'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into room_turn_memory (
                        id, case_id, room_type, turn_no, actor_id, answer_role,
                        answer_content, agent_session_id, access_session_id, created_by
                    ) values (
                        'MEMORY_PURGE_DEMO', 'CASE_PURGE_DEMO', 'INTAKE', 1,
                        'user-local', 'USER', 'demo answer', 'SESSION_PURGE_DEMO',
                        'ACCESS_PURGE_DEMO', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, run_status,
                        started_at, trace_id, created_by
                    ) values (
                        'RUN_PURGE_DEMO', 'CASE_PURGE_DEMO', 'agent-demo',
                        'INTAKE_OFFICER', 'profile-v1', 'prompt-v1', 'skill-v1',
                        'rules-v1', 'COMPLETED', now(), 'trace-demo', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_memory_entry (
                        id, case_id, agent_run_id, memory_scope, memory_key,
                        memory_version, created_by
                    ) values (
                        'AGENT_MEMORY_PURGE_DEMO', 'CASE_PURGE_DEMO', 'RUN_PURGE_DEMO',
                        'CASE', 'demo-memory', 1, 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_a2a_message (
                        id, case_id, round_no, from_agent, to_agent, message_type,
                        input_refs_json, payload_json, visibility, created_at, created_by
                    ) values (
                        'A2A_PURGE_DEMO', 'CASE_PURGE_DEMO', 1, 'EVIDENCE_CLERK',
                        'JUDGE', 'EVIDENCE_UPDATE', '[]', '{}', 'SYSTEM_AUDIT_ONLY',
                        now(), 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into notification (
                        id, case_id, business_event_key, recipient_id, recipient_role,
                        notification_type, title, body, deep_link
                    ) values (
                        'NOTIFY_PURGE_DEMO', 'CASE_PURGE_DEMO', 'purge-demo-event',
                        'user-local', 'USER', 'INTAKE_ACCEPTED', 'Demo', 'Demo',
                        '/disputes/CASE_PURGE_DEMO/intake'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into audit_log (
                        id, case_id, trace_id, request_id, role, service, action,
                        resource_type, outcome, created_by
                    ) values (
                        'AUDIT_PURGE_DEMO', 'CASE_PURGE_DEMO', 'trace-demo',
                        'request-demo', 'SYSTEM', 'test', 'DEMO', 'CASE', 'SUCCESS',
                        'test'
                    )
                    """);
        }
    }

    private static void insertBareCase(
            Connection connection,
            String caseId,
            String sourceType,
            String sourceSystem)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        insert into fulfillment_dispute_case (
                            id, user_id, merchant_id, creation_idempotency_key,
                            case_type, case_status, initiator_role, risk_level,
                            title, description, source_type, source_system,
                            external_case_ref, created_by, updated_by
                        ) values (?, 'user-local', 'merchant-local', ?, 'DISPUTE',
                                  'INTAKE_PENDING', 'USER', 'MEDIUM', ?, 'Demo',
                                  ?, ?, ?, 'test', 'test')
                        """)) {
            statement.setString(1, caseId);
            statement.setString(2, "idem-" + caseId);
            statement.setString(3, caseId.equals("CASE_PURGE_DEMO")
                    ? "Demo purge case"
                    : "Demo case");
            statement.setString(4, sourceType);
            statement.setString(5, sourceSystem);
            statement.setString(6, "external-" + caseId);
            statement.executeUpdate();
        }
    }

    private static void callPurge(
            Connection connection, String caseId, String reviewerRole)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "select purge_simulated_dispute_case(?, ?, ?)")) {
            statement.setString(1, caseId);
            statement.setString(2, "reviewer-local");
            statement.setString(3, reviewerRole);
            statement.executeQuery();
        }
    }

    private static long count(Connection connection, String table, String caseId)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "select count(*) from " + table + " where case_id = ?")) {
            statement.setString(1, caseId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static long countById(
            Connection connection, String table, String id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "select count(*) from " + table + " where id = ?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }
}
