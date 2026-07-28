package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomically commits the Java Evidence completion fact, fenced coordinates, and command terminal state. */
public final class JdbcTargetEvidencePartyCompletionActivities
    implements TargetEvidencePartyCompletionActivities {
  public static final String COMPLETION_INVALID = "TARGET_EVIDENCE_PARTY_COMPLETION_INVALID";
  private static final String WRITER = "target-e2e-evidence-party-finalizer";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final EvidenceDossierFreezer dossierFreezer;

  public JdbcTargetEvidencePartyCompletionActivities(
      DataSource dataSource, TransactionTemplate transaction, EvidenceDossierFreezer dossierFreezer) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.dossierFreezer = Objects.requireNonNull(dossierFreezer, "dossierFreezer");
  }

  @Override
  public Result finalizeCompletion(Request request) {
    try {
      return transaction.execute(status -> finalizeInTransaction(Objects.requireNonNull(request, "request")));
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), COMPLETION_INVALID);
    }
  }

  private Result finalizeInTransaction(Request request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (connection.getAutoCommit()) throw new IllegalStateException("target Evidence completion requires a transaction");
      requireRequest(request);
      Command command = lockCommand(connection, request);
      String role = boundRole(request);
      lockParticipant(connection, request.command().caseId(), request.command().actorRef().actorId(), role);
      Epoch epoch = lockEpoch(connection, request);
      lockProjection(connection, request.command().caseId(), request.expectedProcessRevision());
      String completionId = completionId(request);
      String receiptHash = receiptHash(request, completionId);
      if ("APPLIED".equals(command.status())) {
        requireStoredCompletion(connection, request, completionId, role);
        return result(request, completionId, receiptHash);
      }
      if (!"ORCHESTRATION_ACCEPTED".equals(command.status())
          || epoch.processRevision() != request.expectedProcessRevision()
          || epoch.roomRevision() != request.expectedRoomRevision()) {
        throw new IllegalStateException("target Evidence party completion authority drifted");
      }
      int dossierVersion = dossierFreezer.targetVersion(request.start().caseId());
      insertCompletion(connection, request, completionId, role, dossierVersion);
      long processRevision = Math.incrementExact(request.expectedProcessRevision());
      long roomRevision = Math.incrementExact(request.expectedRoomRevision());
      updateEpoch(connection, epoch.id(), processRevision, roomRevision);
      updateProjection(connection, request.start().caseId(), processRevision);
      markApplied(connection, command.id(), completionId, receiptHash);
      return result(request, completionId, receiptHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence party completion failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private static void requireRequest(Request request) {
    if (request.command().roomType() != RoomType.EVIDENCE
        || request.command().commandType() != CommandType.PARTY_EVIDENCE_COMPLETE
        || request.command().roomEpoch() != request.start().roomEpoch()
        || request.command().expectedProcessRevision() != request.expectedProcessRevision()
        || !request.start().caseId().equals(request.command().caseId())
        || !request.start().tenantSurrogate().equals(request.command().tenantSurrogate())) {
      throw new IllegalArgumentException("target Evidence party completion request is inconsistent");
    }
  }

  private static Command lockCommand(Connection c, Request r) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select id, command_status from case_command
         where tenant_surrogate = ? and case_id = ? and command_id = ?
           and case_command_sequence = ? and request_hash = ? and room_type = 'EVIDENCE'
           and room_epoch = ? and actor_id = ? for update
        """)) {
      s.setString(1, r.command().tenantSurrogate()); s.setString(2, r.command().caseId());
      s.setString(3, r.command().commandId()); s.setLong(4, r.command().caseCommandSequence());
      s.setString(5, r.command().requestHash()); s.setLong(6, r.command().roomEpoch());
      s.setString(7, r.command().actorRef().actorId());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence case command is absent");
        Command value = new Command(row.getString(1), row.getString(2));
        if (row.next()) throw new IllegalStateException("target Evidence case command is ambiguous");
        return value;
      }
    }
  }

  private static String boundRole(Request request) {
    String actorId = request.command().actorRef().actorId();
    if (actorId.equals(request.participants().initiatorParticipantId())) return "USER";
    if (actorId.equals(request.participants().respondentParticipantId())) return "MERCHANT";
    throw new IllegalArgumentException("target Evidence actor is not in the persisted participant binding");
  }

  private static void lockParticipant(Connection c, String caseId, String actorId, String role) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select actor_id from case_participant where case_id = ? and actor_id = ?
          and participant_role = ? and participant_status = 'ACTIVE' for update
        """)) {
      s.setString(1, caseId); s.setString(2, actorId); s.setString(3, role);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next() || row.next()) throw new IllegalStateException("target Evidence participant authority drifted");
      }
    }
  }

  private static Epoch lockEpoch(Connection c, Request r) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select id, process_revision, room_revision from case_room_epoch
         where case_id = ? and room_type = 'EVIDENCE' and room_epoch = ? and fencing_token = ?
           and lifecycle_status = 'ACTIVE' and writer_mode = 'TEMPORAL' for update
        """)) {
      s.setString(1, r.start().caseId()); s.setLong(2, r.start().roomEpoch()); s.setLong(3, r.start().fencingToken());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence epoch is absent");
        Epoch value = new Epoch(row.getString(1), row.getLong(2), row.getLong(3));
        if (row.next()) throw new IllegalStateException("target Evidence epoch is ambiguous");
        return value;
      }
    }
  }

  private static void lockProjection(Connection c, String caseId, long expectedRevision) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(
        "select process_revision from case_process_projection where case_id = ? for update")) {
      s.setString(1, caseId);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next() || row.getLong(1) != expectedRevision || row.next()) {
          throw new IllegalStateException("target Evidence process projection drifted");
        }
      }
    }
  }

  private static void insertCompletion(Connection c, Request r, String id, String role, int version) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        insert into evidence_party_completion (id, case_id, dossier_version, participant_role, participant_id,
          completion_status, idempotency_key, completed_at, created_by)
        values (?, ?, ?, ?, ?, 'COMPLETED', ?, now(), ?) on conflict (case_id, idempotency_key) do nothing
        """)) {
      s.setString(1, id); s.setString(2, r.start().caseId()); s.setInt(3, version); s.setString(4, role);
      s.setString(5, r.command().actorRef().actorId()); s.setString(6, r.command().commandId()); s.setString(7, WRITER);
      s.executeUpdate();
    }
    requireStoredCompletion(c, r, id, role);
  }

  private static void requireStoredCompletion(Connection c, Request r, String id, String role) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select id, participant_role, participant_id, completion_status from evidence_party_completion
         where case_id = ? and idempotency_key = ? for update
        """)) {
      s.setString(1, r.start().caseId()); s.setString(2, r.command().commandId());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next() || !id.equals(row.getString(1)) || !role.equals(row.getString(2))
            || !r.command().actorRef().actorId().equals(row.getString(3)) || !"COMPLETED".equals(row.getString(4))
            || row.next()) throw new IllegalStateException("target Evidence completion receipt drifted");
      }
    }
  }

  private static void updateEpoch(Connection c, String id, long process, long room) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_room_epoch set process_revision = ?, room_revision = ?, updated_at = now(), version = version + 1
         where id = ? and lifecycle_status = 'ACTIVE'
        """)) { s.setLong(1, process); s.setLong(2, room); s.setString(3, id); if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence epoch update failed"); }
  }

  private static void updateProjection(Connection c, String caseId, long process) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_process_projection set process_revision = ?, updated_at = now(), version = version + 1
         where case_id = ? and process_revision < ?
        """)) { s.setLong(1, process); s.setString(2, caseId); s.setLong(3, process); if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence projection update failed"); }
  }

  private static void markApplied(Connection c, String id, String receiptId, String receiptHash) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_command set command_status = 'APPLIED', status_reason_code = null, result_uri = ?, result_sha256 = ?,
          applied_at = now(), updated_at = now(), version = version + 1
         where id = ? and command_status = 'ORCHESTRATION_ACCEPTED'
        """)) { s.setString(1, "target-evidence-completion:" + receiptId); s.setString(2, receiptHash); s.setString(3, id); if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence command completion failed"); }
  }

  private static String completionId(Request r) { return "EVDPC_" + ContractJson.sha256Hex(MAPPER.valueToTree(List.of(r.start().caseId(), r.command().commandId()))).substring(0, 32); }
  private static String receiptHash(Request r, String id) { return ContractJson.sha256Hex(MAPPER.valueToTree(List.of("target-e2e-evidence-party-receipt.v1", id, r.command().requestHash(), r.expectedProcessRevision(), r.expectedRoomRevision()))); }
  private static Result result(Request r, String id, String hash) { return new Result(id, new TargetRoomProgressReceipt(RoomType.EVIDENCE, r.start().roomEpoch(), r.start().fencingToken(), Math.incrementExact(r.expectedProcessRevision()), Math.incrementExact(r.expectedRoomRevision()), id, hash)); }
  private record Command(String id, String status) {}
  private record Epoch(String id, long processRevision, long roomRevision) {}
}
