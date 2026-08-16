package com.example.dispute.hearing.application.query;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only binding from a formal generated set to its exact durable public transcript message. */
@Component
public final class JdbcHearingPublicTranscriptWatermarkQuery {

  private static final String QUESTION_PUBLICATION_KEY = "hearing-v2:4:intake-questions";

  private final JdbcTemplate jdbc;

  public JdbcHearingPublicTranscriptWatermarkQuery(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
  }

  JsonNode bindQuestionSet(
      HearingFlowInstanceEntity instance,
      HearingFlowActionEntity action,
      JsonNode questionSet) {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(action, "action");
    if (!(questionSet instanceof ObjectNode object)) {
      throw new IllegalStateException("formal Hearing question set is not an object");
    }
    String setId = text(
        object.path("question_set_id").asText(null), "formal Hearing question_set_id");
    String agentRunId = text(action.getAgentRunId(), "formal Hearing question AgentRun");
    if (!instance.getId().equals(action.getFlowInstanceId())
        || !instance.getCaseId().equals(action.getCaseId())) {
      throw new IllegalStateException("formal Hearing question set belongs to another flow");
    }
    Long targetRows = jdbc.queryForObject(
        """
        select count(*)
          from hearing_temporal_projection
         where case_id = ? and flow_instance_id = ? and room_type = 'HEARING'
           and writer_mode = 'TEMPORAL'
        """,
        Long.class,
        instance.getCaseId(),
        instance.getId());
    if (targetRows == null || targetRows < 0 || targetRows > 1) {
      throw new IllegalStateException("Hearing transcript writer mode is ambiguous");
    }
    Watermark watermark = targetRows == 1L
        ? targetQuestionWatermark(instance, agentRunId)
        : legacyQuestionWatermark(instance, agentRunId);
    ObjectNode result = object.deepCopy();
    ObjectNode value = result.putObject("transcript_watermark");
    value.put("schema_version", "hearing-transcript-watermark.v1");
    value.put("question_set_id", setId);
    value.put("question_message_id", watermark.messageId());
    value.put("question_message_sequence", watermark.messageSequence());
    value.put("question_agent_run_id", watermark.agentRunId());
    if (watermark.receiptId() != null) {
      value.put("receipt_id", watermark.receiptId());
      value.put("receipt_hash", watermark.receiptHash());
    }
    return result;
  }

  private Watermark targetQuestionWatermark(
      HearingFlowInstanceEntity instance, String agentRunId) {
    List<Watermark> rows = jdbc.query(
        """
        select message.id, message.sequence_no, message.agent_run_id,
               binding.receipt_id, binding.receipt_hash
          from hearing_public_transcript_binding binding
          join room_message message
            on message.id = binding.message_id
           and message.case_id = binding.case_id
           and message.room_id = binding.room_id
           and message.sequence_no = binding.message_sequence_no
           and message.idempotency_key = binding.publication_key
         where binding.case_id = ? and binding.flow_instance_id = ?
           and binding.room_type = 'HEARING' and binding.writer_mode = 'TEMPORAL'
           and binding.source_stage = 'INTAKE_QUESTIONS_GENERATING'
           and binding.source_stage_sequence = 4
           and binding.message_stage = 'INTAKE_QUESTIONS_GENERATING'
           and binding.message_stage_sequence = 4
           and binding.publication_key = ?
           and message.sender_type = 'AGENT'
           and message.sender_role = 'INTAKE_OFFICER'
           and message.message_source = 'AGENT_LLM'
           and message.message_type = 'AGENT_MESSAGE'
           and message.agent_run_id = ?
        """,
        (row, ignored) -> new Watermark(
            row.getString(1),
            row.getLong(2),
            row.getString(3),
            row.getString(4),
            row.getString(5)),
        instance.getCaseId(),
        instance.getId(),
        QUESTION_PUBLICATION_KEY,
        agentRunId);
    return one(rows, "Target Hearing question transcript binding is absent or ambiguous");
  }

  private Watermark legacyQuestionWatermark(
      HearingFlowInstanceEntity instance, String agentRunId) {
    List<Watermark> rows = jdbc.query(
        """
        select message.id, message.sequence_no, message.agent_run_id
          from room_message message
         where message.case_id = ? and message.idempotency_key = ?
           and message.sender_type = 'AGENT'
           and message.sender_role = 'INTAKE_OFFICER'
           and message.message_source = 'AGENT_LLM'
           and message.message_type = 'AGENT_MESSAGE'
           and message.agent_run_id = ?
        """,
        (row, ignored) -> new Watermark(
            row.getString(1), row.getLong(2), row.getString(3), null, null),
        instance.getCaseId(),
        QUESTION_PUBLICATION_KEY,
        agentRunId);
    return one(rows, "Legacy Hearing question transcript binding is absent or ambiguous");
  }

  private static Watermark one(List<Watermark> rows, String message) {
    if (rows.size() != 1) {
      throw new IllegalStateException(message);
    }
    return rows.getFirst();
  }

  private static String text(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(label + " is absent");
    }
    return value;
  }

  private record Watermark(
      String messageId,
      long messageSequence,
      String agentRunId,
      String receiptId,
      String receiptHash) {
    private Watermark {
      messageId = text(messageId, "Hearing transcript message ID");
      agentRunId = text(agentRunId, "Hearing transcript AgentRun ID");
      if (messageSequence < 1) {
        throw new IllegalStateException("Hearing transcript message sequence is invalid");
      }
      if ((receiptId == null) != (receiptHash == null)) {
        throw new IllegalStateException("Hearing transcript receipt binding is partial");
      }
      if (receiptId != null) {
        receiptId = text(receiptId, "Hearing transcript receipt ID");
        if (!receiptHash.matches("[0-9a-f]{64}")) {
          throw new IllegalStateException("Hearing transcript receipt hash is invalid");
        }
      }
    }
  }
}
