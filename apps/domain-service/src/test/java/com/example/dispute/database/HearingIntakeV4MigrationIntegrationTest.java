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
class HearingIntakeV4MigrationIntegrationTest {

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
  void retainsHistoricalSchemasButRejectsEveryNewLegacyIntakeAction() throws SQLException {
    String jdbcUrl = "jdbc:postgresql://" + POSTGRESQL.getHost() + ':'
        + POSTGRESQL.getMappedPort(5432) + '/' + DATABASE_NAME;
    Flyway flyway = Flyway.configure()
        .dataSource(jdbcUrl, USERNAME, PASSWORD)
        .locations("classpath:db/migration")
        .load();

    assertThat(flyway.migrate().migrationsExecuted).isPositive();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
        Statement statement = connection.createStatement()) {
      String schemaConstraint = scalar(statement, """
          select pg_get_constraintdef(oid)
            from pg_constraint
           where conname = 'ck_hearing_flow_action_schema'
          """);
      assertThat(schemaConstraint)
          .contains("hearing_question_set.v1", "hearing_question_set.v4")
          .contains("hearing_answer_bundle.v1", "hearing_answer_bundle.v4");

      String triggerDefinition = scalar(statement, """
          select pg_get_triggerdef(oid)
            from pg_trigger
           where tgname = 'trg_hearing_flow_action_intake_v4_insert'
             and not tgisinternal
          """);
      assertThat(triggerDefinition)
          .contains("BEFORE INSERT ON public.hearing_flow_action")
          .doesNotContain("UPDATE", "DELETE");

      statement.execute("""
          create temp table hearing_intake_v4_action_insert_probe (
              action_type varchar(32) not null,
              schema_version varchar(64) not null
          )
          """);
      statement.execute("""
          create trigger trg_hearing_intake_v4_action_insert_probe
              before insert on hearing_intake_v4_action_insert_probe
              for each row execute function enforce_hearing_intake_v4_action_insert()
          """);
      statement.executeUpdate("""
          insert into hearing_intake_v4_action_insert_probe(action_type, schema_version)
          values
              ('QUESTION_SET', 'hearing_question_set.v4'),
              ('ANSWER_BUNDLE', 'hearing_answer_bundle.v4'),
              ('EVIDENCE_REQUEST_SET', 'hearing_evidence_request_set.v1')
          """);
      assertThat(scalar(statement,
          "select count(*)::text from hearing_intake_v4_action_insert_probe"))
          .isEqualTo("3");

      assertLegacyInsertRejected(statement,
          "QUESTION_SET", "hearing_question_set.v1");
      assertLegacyInsertRejected(statement,
          "ANSWER_BUNDLE", "hearing_answer_bundle.v1");
      assertLegacyInsertRejected(statement,
          "ANSWER_BUNDLE", "hearing_party_statement.v1");
    }
  }

  private static String scalar(Statement statement, String sql) throws SQLException {
    try (var result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static void assertLegacyInsertRejected(
      Statement statement, String actionType, String schemaVersion) {
    assertThatThrownBy(() -> statement.executeUpdate("""
        insert into hearing_intake_v4_action_insert_probe(action_type, schema_version)
        values ('%s', '%s')
        """.formatted(actionType, schemaVersion)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("HEARING_INTAKE_V4_LEGACY_INSERT_FORBIDDEN")
        .hasMessageContaining(actionType + '/' + schemaVersion);
  }
}
