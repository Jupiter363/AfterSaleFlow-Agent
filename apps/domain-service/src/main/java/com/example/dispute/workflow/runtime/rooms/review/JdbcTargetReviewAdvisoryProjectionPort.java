package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Stores a replay-stable advisory projection in the immutable case timeline. The payload contains
 * no decision field and is deliberately unusable as an Outcome command.
 */
public final class JdbcTargetReviewAdvisoryProjectionPort implements TargetReviewAdvisoryProjectionPort {
  private static final String EVENT_TYPE = "TARGET_REVIEW_ADVISORY_PROJECTION";
  private static final String SCHEMA = "production-runtime-review-advisory-projection.v1";
  private final ObjectMapper mapper;
  private final Clock clock;

  public JdbcTargetReviewAdvisoryProjectionPort(ObjectMapper mapper, Clock clock) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override public ProjectionReceipt append(Connection transaction, TargetReviewFinalizationRequest request) {
    Objects.requireNonNull(transaction, "transaction"); Objects.requireNonNull(request, "request");
    try {
      if (transaction.getAutoCommit()) throw new IllegalStateException("Review advisory projection requires caller transaction");
      var graph = request.request().command();
      String eventKey = "target-review-advisory:" + graph.logicalRunId();
      String projectionId = "tradv_" + ContractJson.sha256Hex(mapper.valueToTree(eventKey)).substring(0, 58);
      ObjectNode value = mapper.createObjectNode();
      value.put("schema_version", SCHEMA); value.put("activation_id", request.activationId());
      value.put("command_id", graph.commandId()); value.put("command_hash", request.commandHash());
      value.put("command_envelope_hash", request.commandEnvelopeHash()); value.put("result_hash", request.result().resultHash());
      value.put("proposal_authority", "ADVISORY_ONLY");
      value.put("human_decision_record_id", request.humanDecision().decisionRecordId());
      value.put("human_decision_record_hash", request.humanDecision().decisionRecordHash());
      String projectionHash = ContractJson.sha256Hex(value);
      Existing existing = existing(transaction, graph.caseId(), eventKey);
      if (existing != null) {
        if (!projectionId.equals(existing.id()) || !projectionHash.equals(existing.projectionHash())) {
          throw new IllegalStateException("target Review advisory projection conflicts with replay");
        }
        return new ProjectionReceipt(projectionId, projectionHash);
      }
      lockCase(transaction, graph.caseId());
      long sequence = nextSequence(transaction, graph.caseId());
      insert(transaction, projectionId, graph.caseId(), sequence, eventKey,
          ContractJson.canonicalString(value), clock.instant());
      return new ProjectionReceipt(projectionId, projectionHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Review advisory projection persistence failed", failure);
    }
  }

  private Existing existing(Connection connection, String caseId, String eventKey) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select id, event_json::text from case_timeline_event where case_id = ? and event_key = ? for update")) {
      statement.setString(1, caseId); statement.setString(2, eventKey);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        String id = rows.getString(1); String json = rows.getString(2);
        if (rows.next()) throw new IllegalStateException("target Review advisory projection is ambiguous");
        var node = mapper.readTree(json);
        if (!SCHEMA.equals(node.path("schema_version").asText())) {
          throw new IllegalStateException("target Review advisory projection schema conflicts with replay");
        }
        return new Existing(id, ContractJson.sha256Hex(node));
      } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
        throw new IllegalStateException("stored Review advisory projection is malformed", failure);
      }
    }
  }

  private static void lockCase(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select id from fulfillment_dispute_case where id = ? for update")) {
      statement.setString(1, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.next()) throw new IllegalStateException("target Review case is absent or ambiguous");
      }
    }
  }

  private static long nextSequence(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select coalesce(max(sequence_no), 0) + 1 from case_timeline_event where case_id = ?")) {
      statement.setString(1, caseId);
      try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("timeline sequence is absent"); return rows.getLong(1); }
    }
  }

  private static void insert(Connection connection, String id, String caseId, long sequence, String eventKey,
      String eventJson, Instant createdAt) throws SQLException {
    String sql = """
        insert into case_timeline_event (id, case_id, dossier_id, sequence_no, room_id, event_type, event_time,
          source_refs_json, event_json, audience_json, audience_actor_ids_json, event_key, created_at, created_by)
        values (?, ?, null, ?, null, ?, ?, '{}'::jsonb, cast(? as jsonb), '[]'::jsonb, '[]'::jsonb, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id); statement.setString(2, caseId); statement.setLong(3, sequence);
      // pgjdbc supports OffsetDateTime for timestamptz, not Instant via setObject.
      var timestamp = createdAt.atOffset(ZoneOffset.UTC);
      statement.setString(4, EVENT_TYPE); statement.setObject(5, timestamp); statement.setString(6, eventJson);
      statement.setString(7, eventKey); statement.setObject(8, timestamp);
      statement.setString(9, "production-runtime-review-advisory-projection"); statement.executeUpdate();
    }
  }

  private record Existing(String id, String projectionHash) {}
}
