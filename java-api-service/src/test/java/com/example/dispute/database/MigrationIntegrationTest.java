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

        assertThat(first.migrationsExecuted).isEqualTo(12);
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
                            "settlement_proposal",
                            "settlement_confirmation",
                            "notification",
                            "notification_outbox");
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
                            "uq_notification_business_recipient");
            assertThat(
                            countRows(
                                    connection,
                                    "fulfillment_dispute_case",
                                    "source_type = 'EXTERNAL_IMPORT'"))
                    .isZero();
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
}
