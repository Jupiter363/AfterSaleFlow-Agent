package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Caller-transaction formal Evidence writer for the target candidate lane.
 *
 * <p>The committed room message intentionally contains no model-authored body. The immutable
 * proposal remains in the Graph/object-store provenance chain and is exposed only through the
 * normal reviewed Evidence workflow. This write creates one visible, replay-safe Evidence-domain
 * fact without claiming authority to complete an activation command.
 */
public final class JdbcTargetEvidenceFormalCommitPort implements TargetEvidenceFormalCommitPort {
  private static final String SENDER_ID = "target-e2e-evidence-clerk";
  private final ObjectMapper objectMapper;

  public JdbcTargetEvidenceFormalCommitPort(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  @Override
  public CommitResult commit(Connection transaction, TargetEvidenceFinalizationRequest request) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(request, "request");
    try {
      if (transaction.getAutoCommit()) {
        throw new IllegalStateException("target Evidence formal commit requires caller transaction");
      }
      var graph = request.command().request().command();
      String idempotencyKey = "target-e2e-evidence-final:" + request.formalOperationId();
      String messageText = "Evidence analysis completed and is pending the normal review workflow."
          + " Proposal hash: " + request.command().result().resultHash() + ".";
      Existing existing = findExisting(transaction, graph.caseId(), idempotencyKey);
      if (existing != null) {
        if (!existing.messageText().equals(messageText)) {
          throw new IllegalStateException("target Evidence formal replay conflicts with persisted fact");
        }
        return new CommitResult(existing.id(), formalHash(request, existing.id()));
      }

      String roomId = lockEvidenceRoom(transaction, graph.caseId());
      long nextSequence = nextSequence(transaction, roomId);
      String id = "EVD_FINAL_" + ContractJson.sha256Hex(objectMapper.valueToTree(List.of(
          request.formalOperationId(), request.commandHash(), request.command().result().resultHash())))
          .substring(0, 32);
      insert(transaction, id, graph.caseId(), roomId, nextSequence, idempotencyKey, messageText,
          request.command().request().agentRunId());
      return new CommitResult(id, formalHash(request, id));
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence formal commit failed", failure);
    }
  }

  private static Existing findExisting(Connection transaction, String caseId, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select id, message_text from room_message
         where case_id = ? and idempotency_key = ? for update
        """)) {
      statement.setString(1, caseId);
      statement.setString(2, idempotencyKey);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        Existing existing = new Existing(row.getString(1), row.getString(2));
        if (row.next()) throw new IllegalStateException("target Evidence formal operation is ambiguous");
        return existing;
      }
    }
  }

  private static String lockEvidenceRoom(Connection transaction, String caseId) throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select id from case_room where case_id = ? and room_type = 'EVIDENCE' for update
        """)) {
      statement.setString(1, caseId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence room is absent");
        String roomId = row.getString(1);
        if (row.next()) throw new IllegalStateException("target Evidence room is ambiguous");
        return roomId;
      }
    }
  }

  private static long nextSequence(Connection transaction, String roomId) throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select coalesce(max(sequence_no), 0) from room_message where room_id = ?
        """)) {
      statement.setString(1, roomId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence sequence read failed");
        return Math.addExact(row.getLong(1), 1L);
      }
    }
  }

  private static void insert(
      Connection transaction,
      String id,
      String caseId,
      String roomId,
      long sequence,
      String idempotencyKey,
      String messageText,
      String agentRunId) throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        insert into room_message (
          id, case_id, room_id, sequence_no, sender_type, sender_role, sender_id,
          audience_json, audience_actor_ids_json, message_source, message_type, message_text,
          attachment_refs_json, agent_run_id, idempotency_key, created_at, trace_id, created_by)
        values (?, ?, ?, ?, 'AGENT', 'EVIDENCE_CLERK', ?, '[]'::jsonb, '[]'::jsonb,
          'AGENT_LLM', 'AGENT_MESSAGE', ?, '[]'::jsonb, ?, ?, now(), null, ?)
        """)) {
      statement.setString(1, id);
      statement.setString(2, caseId);
      statement.setString(3, roomId);
      statement.setLong(4, sequence);
      statement.setString(5, SENDER_ID);
      statement.setString(6, messageText);
      statement.setString(7, agentRunId);
      statement.setString(8, idempotencyKey);
      statement.setString(9, SENDER_ID);
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("target Evidence formal fact was not inserted");
      }
    }
  }

  private String formalHash(TargetEvidenceFinalizationRequest request, String formalObjectId) {
    return ContractJson.sha256Hex(objectMapper.valueToTree(List.of(
        "target-e2e-evidence-formal-commit.v1", formalObjectId, request.activationId(),
        request.admissionId(), request.commandHash(), request.commandEnvelopeHash(),
        request.command().result().resultHash())));
  }

  private record Existing(String id, String messageText) {}
}
