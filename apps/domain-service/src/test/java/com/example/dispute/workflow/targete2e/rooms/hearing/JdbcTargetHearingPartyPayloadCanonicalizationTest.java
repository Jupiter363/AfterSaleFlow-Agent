package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTargetHearingPartyPayloadCanonicalizationTest {

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "hearing_party_payload")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  @Test
  void canonicalizesAnswerAndEvidencePayloadsAfterPostgresJsonbChangesTheirText() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/hearing_party_payload",
            "target_test",
            "target_test");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table party_payload (id integer primary key, payload_json jsonb not null)");

    ObjectMapper mapper = JsonMapper.builder().build();
    List<JsonNode> payloads =
        List.of(
            mapper.readTree(
                """
                {
                  "schema_version":"hearing_answer_bundle.v4",
                  "participant_role":"USER",
                  "answers":[{"question_id":"HQ_01","answer_text":"accepted"}]
                }
                """),
            mapper.readTree(
                """
                {
                  "schema_version":"hearing_evidence_batch.v1",
                  "participant_role":"MERCHANT",
                  "evidence_ids":["EVIDENCE_01"],
                  "batch_note":"accepted"
                }
                """));

    for (int index = 0; index < payloads.size(); index++) {
      String producerCanonical = ContractJson.canonicalString(payloads.get(index));
      jdbc.update(
          "insert into party_payload (id, payload_json) values (?, cast(? as jsonb))",
          index + 1,
          producerCanonical);
      String postgresText =
          jdbc.queryForObject(
              "select payload_json::text from party_payload where id = ?",
              String.class,
              index + 1);

      assertThat(postgresText).isNotEqualTo(producerCanonical);
      assertThat(
              JdbcTargetHearingFormalizationActivities.canonicalizePersistedPayload(
                  mapper, postgresText))
          .isEqualTo(producerCanonical);
    }
  }

  @Test
  void partyReceiptCountQueriesUseThePostgresHearingEpochColumn() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/hearing_party_payload",
            "target_test",
            "target_test");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table hearing_flow_action (
          id text primary key,
          flow_instance_id text not null,
          stage_id text not null,
          case_id text not null,
          action_type text not null,
          submission_status text not null)
        """);
    jdbc.execute("""
        create table hearing_domain_receipt (
          tenant_surrogate text not null,
          case_id text not null,
          flow_instance_id text not null,
          epoch_id text not null,
          hearing_epoch bigint not null,
          fencing_token bigint not null,
          writer_mode text not null,
          operation_type text not null,
          source_stage text not null,
          source_stage_sequence bigint not null,
          result_ref text not null)
        """);

    Integer pending = jdbc.queryForObject(
        JdbcTargetHearingFormalizationActivities.PENDING_SUBMITTED_ACTION_COUNT_SQL,
        Integer.class,
        "FLOW", "STAGE", "CASE", "ANSWER_BUNDLE", "TENANT", "EPOCH", 0L, 7L,
        "PARTY_ANSWERS_OPEN", 5L);
    Integer committed = jdbc.queryForObject(
        JdbcTargetHearingFormalizationActivities.COMMITTED_TERMINAL_COUNT_SQL,
        Integer.class,
        "TENANT", "CASE", "FLOW", "EPOCH", 0L, 7L, "PARTY_ANSWERS_OPEN", 5L);

    assertThat(pending).isZero();
    assertThat(committed).isZero();
    assertThat(JdbcTargetHearingFormalizationActivities.PENDING_SUBMITTED_ACTION_COUNT_SQL)
        .contains("receipt.hearing_epoch = ?")
        .doesNotContain("receipt.room_epoch");
    assertThat(JdbcTargetHearingFormalizationActivities.COMMITTED_TERMINAL_COUNT_SQL)
        .contains("hearing_epoch = ?")
        .doesNotContain("room_epoch");
  }
}
