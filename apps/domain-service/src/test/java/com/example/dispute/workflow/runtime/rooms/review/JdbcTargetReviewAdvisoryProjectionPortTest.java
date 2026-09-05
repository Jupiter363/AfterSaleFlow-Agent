package com.example.dispute.workflow.runtime.rooms.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real pgjdbc proof: a mock PreparedStatement cannot detect unsupported Instant binding. */
@Testcontainers
class JdbcTargetReviewAdvisoryProjectionPortTest {
  private static final Instant NOW = Instant.parse("2026-09-06T02:03:04.123456Z");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
      "public.ecr.aws/docker/library/postgres@sha256:e013e867e712fec275706a6c51c966f0bb0c93cfa8f51000f85a15f9865a28cb"))
      .withEnv("POSTGRES_USER", "projection_test")
      .withEnv("POSTGRES_PASSWORD", "isolated_test_password")
      .withEnv("POSTGRES_DB", "projection_test")
      .withExposedPorts(5432)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

  @Test
  void persistsUtcInstantsAndReplaysWithoutAnotherEventOrTimestamp() throws Exception {
    try (Connection connection = fixture()) {
      var request = request("a".repeat(64));
      var receipt = port(NOW).append(connection, request);
      connection.commit();
      assertThat(port(NOW.plusSeconds(900)).append(connection, request)).isEqualTo(receipt);
      connection.commit();
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("select event_time, created_at, sequence_no, event_json::text "
              + "from case_timeline_event")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getTimestamp(1).toInstant()).isEqualTo(NOW);
        assertThat(rows.getTimestamp(2).toInstant()).isEqualTo(NOW);
        assertThat(rows.getLong(3)).isEqualTo(1);
        var payload = MAPPER.readTree(rows.getString(4));
        assertThat(payload.path("proposal_authority").asText()).isEqualTo("ADVISORY_ONLY");
        assertThat(payload.has("decision_action")).isFalse();
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void conflictingReplayRejectsWithoutChangingTheStoredProjection() throws Exception {
    try (Connection connection = fixture()) {
      var receipt = port(NOW).append(connection, request("a".repeat(64)));
      connection.commit();
      assertThatThrownBy(() -> port(NOW).append(connection, request("b".repeat(64))))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts with replay");
      connection.rollback();
      assertThat(port(NOW).append(connection, request("a".repeat(64)))).isEqualTo(receipt);
    }
  }

  @Test
  void callerOwnsCommitAndAutocommitIsRejected() throws Exception {
    try (Connection connection = fixture()) {
      port(NOW).append(connection, request("a".repeat(64)));
      connection.rollback();
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("select count(*) from case_timeline_event")) {
        rows.next();
        assertThat(rows.getLong(1)).isZero();
      }
      connection.setAutoCommit(true);
      assertThatThrownBy(() -> port(NOW).append(connection, request("a".repeat(64))))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("caller transaction");
    }
  }

  private static JdbcTargetReviewAdvisoryProjectionPort port(Instant time) {
    return new JdbcTargetReviewAdvisoryProjectionPort(MAPPER, Clock.fixed(time, ZoneOffset.UTC));
  }

  private static TargetReviewFinalizationRequest request(String resultHash) {
    var request = mock(TargetReviewFinalizationRequest.class, RETURNS_DEEP_STUBS);
    when(request.request().command().caseId()).thenReturn("CASE_PROJECTION_TEST");
    when(request.request().command().logicalRunId()).thenReturn("review-projection-run");
    when(request.request().command().commandId()).thenReturn("review-command");
    when(request.activationId()).thenReturn("projection-activation");
    when(request.commandHash()).thenReturn("c".repeat(64));
    when(request.commandEnvelopeHash()).thenReturn("d".repeat(64));
    when(request.result().resultHash()).thenReturn(resultHash);
    when(request.humanDecision().decisionRecordId()).thenReturn("human-decision");
    when(request.humanDecision().decisionRecordHash()).thenReturn("e".repeat(64));
    return request;
  }

  private static Connection fixture() throws Exception {
    Connection connection = DriverManager.getConnection(
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/projection_test", "projection_test", "isolated_test_password");
    try (var statement = connection.createStatement()) {
      statement.execute("set time zone 'Asia/Shanghai'");
      statement.execute("create temporary table fulfillment_dispute_case (id text primary key)");
      statement.execute("insert into fulfillment_dispute_case values ('CASE_PROJECTION_TEST')");
      statement.execute("""
          create temporary table case_timeline_event (
            id text primary key, case_id text not null, dossier_id text, sequence_no bigint not null,
            room_id text, event_type text not null, event_time timestamptz not null,
            source_refs_json jsonb not null, event_json jsonb not null, audience_json jsonb not null,
            audience_actor_ids_json jsonb not null, event_key text not null,
            created_at timestamptz not null, created_by text not null,
            unique (case_id, event_key), unique (case_id, sequence_no))
          """);
    }
    connection.setAutoCommit(false);
    return connection;
  }
}
