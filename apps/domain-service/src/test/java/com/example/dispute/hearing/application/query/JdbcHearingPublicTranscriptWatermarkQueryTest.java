package com.example.dispute.hearing.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcHearingPublicTranscriptWatermarkQueryTest {

  private static final String CASE_ID = "CASE_HEARING_WATERMARK_V4";
  private static final String FLOW_ID = "ROOM_HEARING_WATERMARK_V4";
  private static final String RUN_ID = "target-hearing-run:watermark-v4";
  private static final String MESSAGE_ID = "MESSAGE_HEARING_WATERMARK_V4";
  private static final String QUESTION_SET_ID = "HEARING_QUESTION_SET_WATERMARK_V4";
  private static final String RECEIPT_ID = "HDR_HEARING_WATERMARK_V4";
  private static final String RECEIPT_HASH = "a".repeat(64);

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "hearing_watermark")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void schema() {
    dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/hearing_watermark",
            "target_test",
            "target_test");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        create table hearing_temporal_projection (
          case_id varchar(64), flow_instance_id varchar(64),
          room_type varchar(32), writer_mode varchar(16))
        """);
    jdbc.execute(
        """
        create table hearing_public_transcript_binding (
          case_id varchar(64), flow_instance_id varchar(64), room_id varchar(64),
          room_type varchar(32), writer_mode varchar(16), source_stage varchar(64),
          source_stage_sequence integer, message_stage varchar(64),
          message_stage_sequence integer, publication_key varchar(128),
          message_id varchar(64), message_sequence_no bigint,
          receipt_id varchar(64), receipt_hash varchar(64))
        """);
    jdbc.execute(
        """
        create table room_message (
          id varchar(64), case_id varchar(64), room_id varchar(64), sequence_no bigint,
          idempotency_key varchar(128), sender_type varchar(32), sender_role varchar(64),
          message_source varchar(32), message_type varchar(64), agent_run_id varchar(128))
        """);
  }

  @Test
  void bindsQuestionSetToTheV4LeadFrame() {
    jdbc.update(
        "insert into hearing_temporal_projection values (?, ?, 'HEARING', 'TEMPORAL')",
        CASE_ID,
        FLOW_ID);
    jdbc.update(
        """
        insert into room_message values (
          ?, ?, ?, 9, 'hearing-v2:4:intake-questions-frame-1',
          'AGENT', 'INTAKE_OFFICER', 'AGENT_LLM', 'AGENT_MESSAGE', ?)
        """,
        MESSAGE_ID,
        CASE_ID,
        FLOW_ID,
        RUN_ID);
    jdbc.update(
        """
        insert into hearing_public_transcript_binding values (
          ?, ?, ?, 'HEARING', 'TEMPORAL', 'INTAKE_QUESTIONS_GENERATING', 4,
          'INTAKE_QUESTIONS_GENERATING', 4,
          'hearing-v2:4:intake-questions-frame-1', ?, 9, ?, ?)
        """,
        CASE_ID,
        FLOW_ID,
        FLOW_ID,
        MESSAGE_ID,
        RECEIPT_ID,
        RECEIPT_HASH);

    HearingFlowInstanceEntity instance = mock(HearingFlowInstanceEntity.class);
    when(instance.getId()).thenReturn(FLOW_ID);
    when(instance.getCaseId()).thenReturn(CASE_ID);
    HearingFlowActionEntity action = mock(HearingFlowActionEntity.class);
    when(action.getFlowInstanceId()).thenReturn(FLOW_ID);
    when(action.getCaseId()).thenReturn(CASE_ID);
    when(action.getAgentRunId()).thenReturn(RUN_ID);
    ObjectNode questionSet =
        JsonMapper.builder().build().createObjectNode().put("question_set_id", QUESTION_SET_ID);

    JsonNode bound =
        new JdbcHearingPublicTranscriptWatermarkQuery(dataSource)
            .bindQuestionSet(instance, action, questionSet);

    JsonNode watermark = bound.path("transcript_watermark");
    assertThat(watermark.path("schema_version").asText())
        .isEqualTo("hearing-transcript-watermark.v1");
    assertThat(watermark.path("question_set_id").asText()).isEqualTo(QUESTION_SET_ID);
    assertThat(watermark.path("question_message_id").asText()).isEqualTo(MESSAGE_ID);
    assertThat(watermark.path("question_message_sequence").asLong()).isEqualTo(9L);
    assertThat(watermark.path("question_agent_run_id").asText()).isEqualTo(RUN_ID);
    assertThat(watermark.path("receipt_id").asText()).isEqualTo(RECEIPT_ID);
    assertThat(watermark.path("receipt_hash").asText()).isEqualTo(RECEIPT_HASH);
  }
}
