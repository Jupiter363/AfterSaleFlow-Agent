package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReviewEpochTaskBindingMigrationIntegrationTest {

    private static final String DATABASE_NAME = "dispute_system";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(DockerImageName.parse(
                            "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE_NAME)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    @Test
    void admitsTheFirstZeroBasedReviewEpochButRejectsNegativeCoordinates()
            throws SQLException {
        String jdbcUrl = "jdbc:postgresql://" + POSTGRESQL.getHost() + ':'
                + POSTGRESQL.getMappedPort(5432) + '/' + DATABASE_NAME;
        Flyway beforeV075 = Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .target("74")
                .load();

        assertThat(beforeV075.migrate().migrationsExecuted).isPositive();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            try {
                assertThat(statement.executeUpdate(remedyPlanInsert(
                                "PLAN_LEGACY_HEARING_V2",
                                "CASE_LEGACY_HEARING_V2",
                                "HEARING_V2")))
                        .isEqualTo(1);
            } finally {
                statement.execute("set session_replication_role = origin");
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            String definition = scalar(statement, """
                    select pg_get_constraintdef(oid)
                      from pg_constraint
                     where conname = 'ck_review_epoch_task_coordinates'
                    """);
            assertThat(definition)
                    .contains("room_epoch >= 0")
                    .contains("room_fencing_token > 0");
            String outcomeDefinition = scalar(statement, """
                    select pg_get_constraintdef(oid)
                      from pg_constraint
                     where conname = 'ck_target_e2e_outcome_completion_shape'
                    """);
            String nonExecutionDefinition = scalar(statement, """
                    select pg_get_constraintdef(oid)
                      from pg_constraint
                     where conname = 'ck_target_review_non_execution_shape'
                    """);
            assertThat(outcomeDefinition).contains("outcome_epoch >= 0");
            assertThat(nonExecutionDefinition).contains("source_room_epoch >= 0");
            String remedyRouteDefinition = scalar(statement, """
                    select pg_get_constraintdef(oid)
                      from pg_constraint
                     where conname = 'ck_remedy_plan_source_route'
                    """);
            assertThat(remedyRouteDefinition)
                    .contains("TRANSFERRED")
                    .contains("SIMPLE_HEARING")
                    .contains("FULL_HEARING");
            assertThat(scalar(statement, """
                    select source_route
                      from remedy_plan
                     where id = 'PLAN_LEGACY_HEARING_V2'
                    """))
                    .isEqualTo("FULL_HEARING");

            // Isolate the coordinate CHECK from unrelated composite foreign keys. PostgreSQL
            // CHECK constraints remain active while replication-role disables FK triggers.
            statement.execute("set session_replication_role = replica");
            try {
                assertThat(statement.executeUpdate("""
                        insert into target_e2e_review_epoch_task_binding (
                            epoch_id, tenant_surrogate, case_id, room_epoch,
                            room_fencing_token, review_task_id, plan_id,
                            policy_decision_id, source_handoff_id, created_by
                        ) values (
                            'EPOCH_REVIEW_ZERO', 'tenant-zero', 'CASE_REVIEW_ZERO', 0,
                            1, 'TASK_REVIEW_ZERO', 'PLAN_REVIEW_ZERO',
                            'POLICY_REVIEW_ZERO', 'HANDOFF_REVIEW_ZERO', 'migration-test'
                        )
                        """))
                        .isEqualTo(1);

                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into target_e2e_review_epoch_task_binding (
                            epoch_id, tenant_surrogate, case_id, room_epoch,
                            room_fencing_token, review_task_id, plan_id,
                            policy_decision_id, source_handoff_id, created_by
                        ) values (
                            'EPOCH_REVIEW_NEGATIVE', 'tenant-negative', 'CASE_REVIEW_NEGATIVE', -1,
                            1, 'TASK_REVIEW_NEGATIVE', 'PLAN_REVIEW_NEGATIVE',
                            'POLICY_REVIEW_NEGATIVE', 'HANDOFF_REVIEW_NEGATIVE', 'migration-test'
                        )
                        """))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_review_epoch_task_coordinates");

                assertThat(statement.executeUpdate("""
                        insert into target_e2e_outcome_completion_fact (
                            workflow_id, case_id, outcome_epoch, fencing_token,
                            human_receipt_id, human_receipt_hash, fact_kind,
                            revision, committed_event_sequence, payload_json,
                            payload_hash, committed_at, committed_by
                        ) values (
                            'outcome:zero', 'CASE_OUTCOME_ZERO', 0, 1,
                            'RECEIPT_OUTCOME_ZERO', repeat('a', 64), 'OPERATION_COMMAND',
                            1, 1, '{}'::jsonb, repeat('b', 64), now(), 'migration-test'
                        )
                        """))
                        .isEqualTo(1);

                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into target_e2e_outcome_completion_fact (
                            workflow_id, case_id, outcome_epoch, fencing_token,
                            human_receipt_id, human_receipt_hash, fact_kind,
                            revision, committed_event_sequence, payload_json,
                            payload_hash, committed_at, committed_by
                        ) values (
                            'outcome:negative', 'CASE_OUTCOME_NEGATIVE', -1, 1,
                            'RECEIPT_OUTCOME_NEGATIVE', repeat('a', 64), 'OPERATION_COMMAND',
                            1, 1, '{}'::jsonb, repeat('b', 64), now(), 'migration-test'
                        )
                        """))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_target_e2e_outcome_completion_shape");

                assertThat(statement.executeUpdate(nonExecutionInsert("RNE_ZERO", 0)))
                        .isEqualTo(1);
                assertThatThrownBy(
                                () -> statement.executeUpdate(
                                        nonExecutionInsert("RNE_NEGATIVE", -1)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_target_review_non_execution_shape");

                assertThat(statement.executeUpdate(remedyPlanInsert(
                                "PLAN_FULL_HEARING",
                                "CASE_FULL_HEARING",
                                "FULL_HEARING")))
                        .isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(remedyPlanInsert(
                                "PLAN_INVALID_ROUTE",
                                "CASE_INVALID_ROUTE",
                                "HEARING_V2")))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_remedy_plan_source_route");
            } finally {
                statement.execute("set session_replication_role = origin");
            }
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static String nonExecutionInsert(String suffix, long sourceRoomEpoch) {
        return """
                insert into target_e2e_review_non_execution_completion (
                    receipt_id, schema_version, tenant_surrogate, case_id,
                    case_workflow_id, case_workflow_run_id, decision_type,
                    decision_record_id, decision_record_hash, command_id,
                    admission_id, activation_id, command_hash, command_envelope_hash,
                    source_room_epoch, source_fencing_token,
                    source_process_revision, source_room_revision,
                    terminal_process_revision, terminal_room_revision,
                    receipt_canonical_json, receipt_sha256, committed_at, committed_by
                ) values (
                    '%1$s', 'target-review-non-execution-disposition.v1',
                    'tenant-%1$s', 'CASE_%1$s', 'case:%1$s', 'run:%1$s', 'REJECT',
                    'DECISION_%1$s', repeat('a', 64), 'COMMAND_%1$s',
                    'ADMISSION_%1$s', 'ACTIVATION_%1$s', repeat('b', 64), repeat('c', 64),
                    %2$d, 1, 8, 4, 9, 5,
                    '{}', repeat('d', 64), now(), 'migration-test'
                )
                """.formatted(suffix, sourceRoomEpoch);
    }

    private static String remedyPlanInsert(String planId, String caseId, String sourceRoute) {
        return """
                insert into remedy_plan (
                    id, case_id, adjudication_draft_id, plan_version, source_route,
                    plan_status, risk_level, total_amount, currency, actions_json,
                    preconditions_json, notification_plan_json, requires_human_review,
                    created_by, updated_by
                ) values (
                    '%1$s', '%2$s', null, 1, '%3$s',
                    'PENDING_HUMAN_REVIEW', 'MEDIUM', 0, 'CNY', '[]'::jsonb,
                    '[]'::jsonb, '[]'::jsonb, true, 'migration-test', 'migration-test'
                )
                """.formatted(planId, caseId, sourceRoute);
    }
}
