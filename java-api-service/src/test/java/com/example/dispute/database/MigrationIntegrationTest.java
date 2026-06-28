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

        assertThat(first.migrationsExecuted).isEqualTo(6);
        assertThat(second.migrationsExecuted).isZero();

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(loadTables(connection))
                    .containsExactlyInAnyOrder(
                            "fulfillment_case",
                            "evidence_dossier",
                            "evidence_item",
                            "party_claim",
                            "issue",
                            "claim_issue_evidence_matrix",
                            "evidence_request",
                            "party_submission",
                            "hearing_state",
                            "hearing_record",
                            "adjudication_draft",
                            "remedy_plan",
                            "review_packet",
                            "review_task",
                            "approval_record",
                            "action_record",
                            "audit_log",
                            "policy_rule",
                            "evaluation_trace",
                            "route_decision",
                            "flow_conclusion");
            assertThat(columnType(connection, "evidence_item", "metadata_json"))
                    .isEqualTo("jsonb");
            assertThat(columnType(connection, "action_record", "execution_time"))
                    .isEqualTo("timestamp with time zone");
            assertThat(numericDefinition(connection, "remedy_plan", "total_amount"))
                    .isEqualTo("18:2");
            assertThat(loadIndexes(connection))
                    .contains(
                            "idx_fulfillment_case_user_id",
                            "idx_evidence_item_case_id",
                            "idx_review_task_status",
                            "idx_action_record_case_id",
                            "idx_audit_log_case_id",
                            "uq_policy_rule_code_version",
                            "idx_route_decision_type_created",
                            "idx_flow_conclusion_status");
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
}
