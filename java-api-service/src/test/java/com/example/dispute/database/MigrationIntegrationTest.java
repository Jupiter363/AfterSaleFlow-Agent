package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MigrationIntegrationTest {

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

    @Test
    void migrationsApplyOnceAndCreateTheCompletePostgresqlSchema() throws SQLException {
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ":"
                        + POSTGRESQL.getMappedPort(5432)
                        + "/"
                        + DATABASE_NAME;
        Flyway flyway =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.migrationsExecuted).isEqualTo(26);
        assertThat(second.migrationsExecuted).isZero();

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(loadTables(connection))
                    .containsExactlyInAnyOrder(
                            "fulfillment_dispute_case",
                            "evidence_dossier",
                            "evidence_dossier_item",
                            "evidence_item",
                            "party_claim",
                            "issue",
                            "claim_issue_evidence_link",
                            "evidence_request",
                            "dispute_submission",
                            "case_timeline_event",
                            "hearing_state",
                            "hearing_stage_record",
                            "adjudication_draft",
                            "deliberation_report",
                            "deliberation_finding",
                            "remedy_plan",
                            "remedy_action",
                            "approval_policy_decision",
                            "review_packet",
                            "review_task",
                            "human_review_record",
                            "action_record",
                            "agent_run",
                            "agent_tool_call",
                            "agent_guardrail_event",
                            "agent_memory_entry",
                            "skill_version",
                            "prompt_version",
                            "audit_log",
                            "policy_rule",
                            "evaluation_record",
                            "route_decision",
                            "flow_conclusion",
                            "case_participant",
                            "case_room",
                            "room_message",
                            "case_phase_clock",
                            "evidence_verification",
                            "evidence_party_completion",
                            "hearing_round",
                            "hearing_round_party_submission",
                            "settlement_proposal",
                            "settlement_confirmation",
                            "notification",
                            "notification_outbox",
                            "room_turn_memory",
                            "case_intake_dossier",
                            "case_access_session",
                            "agent_conversation_session",
                            "agent_session_dossier",
                            "evidence_submission_batch",
                            "agent_a2a_message");
            assertThat(columnType(connection, "evidence_item", "metadata_json"))
                    .isEqualTo("jsonb");
            assertThat(columnType(connection, "action_record", "execution_time"))
                    .isEqualTo("timestamp with time zone");
            assertThat(
                            columnType(
                                    connection,
                                    "review_packet",
                                    "agent_run_refs_json"))
                    .isEqualTo("jsonb");
            assertThat(
                            columnType(
                                    connection,
                                    "human_review_record",
                                    "approval_expires_at"))
                    .isEqualTo("timestamp with time zone");
            assertThat(
                            columnType(
                                    connection,
                                    "action_record",
                                    "external_result_ref"))
                    .isEqualTo("character varying");
            assertThat(numericDefinition(connection, "remedy_plan", "total_amount"))
                    .isEqualTo("18:2");
            assertThat(loadIndexes(connection))
                    .contains(
                            "idx_fulfillment_case_user_id",
                            "idx_fulfillment_dispute_case_status",
                            "idx_evidence_item_case_id",
                            "idx_review_task_status",
                            "idx_action_record_case_id",
                            "idx_agent_run_case",
                            "idx_deliberation_case",
                            "idx_audit_log_case_id",
                            "uq_policy_rule_code_version",
                            "idx_route_decision_type_created",
                            "idx_flow_conclusion_status",
                            "uq_dispute_external_source",
                            "uq_case_room_type",
                            "uq_settlement_confirmation_role",
                            "uq_notification_business_recipient",
                            "uq_agent_a2a_jury_review_report");
            assertThat(
                            countRows(
                                    connection,
                                    "fulfillment_dispute_case",
                                    "source_type = 'EXTERNAL_IMPORT'"))
                    .isZero();
            assertHearingRoundFiveIsSupported(connection);
            assertThat(loadTriggers(connection))
                    .contains(
                            "trg_room_message_append_only",
                            "trg_case_timeline_event_append_only");
            assertFormalJuryReportUniqueness(connection);
            assertAppendOnlyTablesRejectMutation(connection);
        }
    }

    private static Set<String> loadTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select table_name
                                from information_schema.tables
                                where table_schema = 'public'
                                  and table_type = 'BASE TABLE'
                                  and table_name <> 'flyway_schema_history'
                                """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static String columnType(Connection connection, String table, String column)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        select data_type
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = ?
                          and column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static String numericDefinition(
            Connection connection, String table, String column) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        select numeric_precision, numeric_scale
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = ?
                          and column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1) + ":" + result.getInt(2);
            }
        }
    }

    private static Set<String> loadIndexes(Connection connection) throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select indexname from pg_indexes where schemaname = 'public'")) {
            while (result.next()) {
                indexes.add(result.getString(1));
            }
        }
        return indexes;
    }

    private static long countRows(
            Connection connection, String table, String condition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select count(*) from " + table + " where " + condition)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Set<String> loadTriggers(Connection connection) throws SQLException {
        Set<String> triggers = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select trigger_name
                                from information_schema.triggers
                                where trigger_schema = 'public'
                                """)) {
            while (result.next()) {
                triggers.add(result.getString(1));
            }
        }
        return triggers;
    }

    private static void assertAppendOnlyTablesRejectMutation(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, risk_level, title, description,
                        created_by, updated_by
                    ) values (
                        'CASE_APPEND_ONLY', 'user-local', 'merchant-local', 'append-only-case',
                        'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'HIGH', 'Append-only test',
                        'Database immutability test', 'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (
                        'ROOM_APPEND_ONLY', 'CASE_APPEND_ONLY', 'EVIDENCE', 'OPEN', now(),
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into room_message (
                        id, case_id, room_id, sequence_no, sender_type, sender_role,
                        sender_id, message_type, message_text, idempotency_key, created_by
                    ) values (
                        'MESSAGE_APPEND_ONLY', 'CASE_APPEND_ONLY', 'ROOM_APPEND_ONLY', 1,
                        'PARTY', 'USER', 'user-local', 'PARTY_TEXT', 'original',
                        'append-only-message', 'user-local'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_timeline_event (
                        id, case_id, sequence_no, event_type, event_time, created_by
                    ) values (
                        'EVENT_APPEND_ONLY', 'CASE_APPEND_ONLY', 1, 'ROOM_MESSAGE_CREATED',
                        now(), 'test'
                    )
                    """);
        }

        assertThatSqlFails(
                connection,
                "update room_message set message_text = 'mutated' where id = 'MESSAGE_APPEND_ONLY'",
                "room_message is append-only");
        assertThatSqlFails(
                connection,
                "delete from case_timeline_event where id = 'EVENT_APPEND_ONLY'",
                "case_timeline_event is append-only");
    }

    private static void assertHearingRoundFiveIsSupported(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, hearing_route, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_ROUND_FIVE', 'user-local', 'merchant-local',
                        'round-five-case', 'DISPUTE', 'HEARING', 'USER', 'FULL_HEARING',
                        'HIGH', 'Round five test',
                        'Database constraint must match configurable hearing rounds.',
                        'HEARING', 'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into hearing_round (
                        id, case_id, round_no, round_status, dossier_version,
                        opened_at, round_deadline_at, summary_json,
                        created_by, updated_by
                    ) values (
                        'HROUND_FIVE', 'CASE_ROUND_FIVE', 5, 'OPEN', 1,
                        now(), now() + interval '5 minutes', '{}',
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into hearing_round_party_submission (
                        id, case_id, round_id, round_no, participant_role,
                        participant_id, submission_source, submission_json,
                        submitted_at, created_by, updated_by
                    ) values (
                        'HROUND_SUB_FIVE', 'CASE_ROUND_FIVE', 'HROUND_FIVE',
                        5, 'USER', 'user-local', 'PARTY_ACTION', '{}',
                        now(), 'user-local', 'user-local'
                    )
                    """);
        }
    }

    private static void assertFormalJuryReportUniqueness(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, risk_level, title, description,
                        created_by, updated_by
                    ) values (
                        'CASE_A2A_UNIQUENESS', 'user-local', 'merchant-local',
                        'a2a-uniqueness-case', 'DISPUTE', 'HEARING', 'USER', 'HIGH',
                        'A2A uniqueness test', 'Formal jury reports must be unique.',
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_a2a_message (
                        id, case_id, round_no, from_agent, to_agent, message_type,
                        input_refs_json, payload_json, visibility, created_at, created_by
                    ) values (
                        'A2A_JURY_REPORT_1', 'CASE_A2A_UNIQUENESS', 3,
                        'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_REVIEW_REPORT',
                        '{}', '{}', 'REVIEWER_VISIBLE', now(), 'jury-panel'
                    )
                    """);
        }

        assertThatSqlFails(
                connection,
                """
                insert into agent_a2a_message (
                    id, case_id, round_no, from_agent, to_agent, message_type,
                    input_refs_json, payload_json, visibility, created_at, created_by
                ) values (
                    'A2A_JURY_REPORT_2', 'CASE_A2A_UNIQUENESS', 3,
                    'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_REVIEW_REPORT',
                    '{}', '{}', 'REVIEWER_VISIBLE', now(), 'jury-panel'
                )
                """,
                "uq_agent_a2a_jury_review_report");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_a2a_message (
                        id, case_id, round_no, from_agent, to_agent, message_type,
                        input_refs_json, payload_json, visibility, created_at, created_by
                    ) values
                        (
                            'A2A_SILENT_NOTE_1', 'CASE_A2A_UNIQUENESS', 2,
                            'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_SILENT_NOTE',
                            '{}', '{}', 'SYSTEM_AUDIT_ONLY', now(), 'jury-panel'
                        ),
                        (
                            'A2A_SILENT_NOTE_2', 'CASE_A2A_UNIQUENESS', 2,
                            'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_SILENT_NOTE',
                            '{}', '{}', 'SYSTEM_AUDIT_ONLY', now(), 'jury-panel'
                        )
                    """);
        }
    }

    private static void assertThatSqlFails(
            Connection connection, String sql, String expectedMessage) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> {
                            try (Statement statement = connection.createStatement()) {
                                statement.executeUpdate(sql);
                            }
                        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }
}
