package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only target-lane writer which can turn two Evidence completion facts into a Hearing-open
 * domain transition. It deliberately has no browser, legacy-agent, or Hearing-runtime dependency.
 */
public final class JdbcTargetEvidenceTerminalActivities implements TargetEvidenceTerminalActivities {
  private static final String WRITER = "target-e2e-evidence-terminal";
  private static final Duration HEARING_WINDOW = Duration.ofHours(3);

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final EvidenceDossierFreezer dossierFreezer;
  private final RoomEpochAllocator roomEpochAllocator;
  private final ObjectMapper mapper;
  private final Clock clock;

  public JdbcTargetEvidenceTerminalActivities(
      DataSource dataSource,
      TransactionTemplate transaction,
      EvidenceDossierFreezer dossierFreezer,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper mapper,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.dossierFreezer = Objects.requireNonNull(dossierFreezer, "dossierFreezer");
    this.roomEpochAllocator = Objects.requireNonNull(roomEpochAllocator, "roomEpochAllocator");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public TerminalResult finalizeTerminal(TerminalRequest request) {
    return transaction.execute(status -> finalizeInTransaction(request));
  }

  private TerminalResult finalizeInTransaction(TerminalRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      Stored stored = readStored(connection, request, true);
      if (stored != null) {
        return result(request, stored.receiptId(), stored.receiptHash());
      }

      requireAgentRunReceipts(connection, request);
      String initiatorRole = participantRole(connection, request.start().caseId(), request.start().initiatorParticipantId());
      String respondentRole = participantRole(connection, request.start().caseId(), request.start().respondentParticipantId());
      if (initiatorRole.equals(respondentRole)) {
        throw new IllegalStateException("target Evidence terminal requires opposing party roles");
      }

      int targetVersion = dossierFreezer.targetVersion(request.start().caseId());
      completionFact(connection, request, targetVersion, initiatorRole,
          request.start().initiatorParticipantId(), request.initiatorCompletionId());
      completionFact(connection, request, targetVersion, respondentRole,
          request.start().respondentParticipantId(), request.respondentCompletionId());
      EvidenceDossierEntity frozen = dossierFreezer.freeze(request.start().caseId(), targetVersion, WRITER);
      if (frozen.getDossierVersion() != targetVersion) {
        throw new IllegalStateException("target Evidence terminal froze an unexpected dossier version");
      }

      Instant committedAt = clock.instant();
      String requestHash = hash(request);
      String receiptId = "EVDTERM_" + ContractJson.sha256Hex(mapper.valueToTree(
          List.of(request.start().caseId(), request.start().roomEpoch(), requestHash))).substring(0, 32);
      String hearingRoomId = "ROOM_HEARING_" + ContractJson.sha256Hex(mapper.valueToTree(
          List.of(request.start().caseId(), request.start().roomEpoch(), requestHash))).substring(0, 28);
      Instant hearingDeadline = committedAt.plus(HEARING_WINDOW);
      sealEvidenceAndOpenHearing(connection, request, hearingRoomId, hearingDeadline);
      RoomEpochAllocation hearingEpoch = roomEpochAllocator.transition(new TransitionRoomEpoch(
          request.start().caseId(), RoomType.EVIDENCE, hearingRoomId, RoomType.HEARING,
          "HEARING_OPEN", "PROVISIONING", OffsetDateTime.ofInstant(hearingDeadline, ZoneOffset.UTC),
          OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC)));
      validateHearingAllocation(connection, request, hearingRoomId, hearingDeadline, hearingEpoch);
      long terminalProcessRevision = hearingEpoch.processRevision();
      long terminalRoomRevision = Math.incrementExact(request.expectedRoomRevision());
      String receiptHash = receiptHash(request, frozen, hearingRoomId, hearingDeadline,
          terminalProcessRevision, terminalRoomRevision);
      appendEventAndOutbox(connection, request, frozen, hearingRoomId, hearingDeadline, receiptHash);
      insertReceipt(connection, receiptId, receiptHash, requestHash, request, frozen, hearingRoomId,
          hearingDeadline, terminalProcessRevision, terminalRoomRevision, committedAt);
      return result(request, receiptId, receiptHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence terminal transition failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private TerminalResult result(TerminalRequest request, String receiptId, String receiptHash) {
    return new TerminalResult(new TargetRoomProgressReceipt(RoomType.EVIDENCE, request.start().roomEpoch(),
        request.start().fencingToken(), Math.incrementExact(request.expectedProcessRevision()),
        Math.incrementExact(request.expectedRoomRevision()), receiptId, receiptHash));
  }

  void requireAgentRunReceipts(Connection connection, TerminalRequest request) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select count(*)
          from target_e2e_evidence_command_material material
         where material.tenant_surrogate = ? and material.case_id = ? and material.room_epoch = ?
           and not exists (
             select 1 from target_e2e_finalization_receipt receipt
              where receipt.execution_lane = 'TARGET_E2E_CANDIDATE'
                and receipt.activation_id = material.activation_id
                and receipt.tenant_surrogate = material.tenant_surrogate
                and receipt.case_id = material.case_id
                and receipt.room_type = 'EVIDENCE'
                and receipt.room_epoch = material.room_epoch
                and receipt.room_fencing_token = material.room_fencing_token
                and receipt.logical_run_id = material.material_canonical_json::jsonb #>> '{request,agent_run_id}'
                and receipt.attempt_id = material.material_canonical_json::jsonb #>> '{request,command,attempt_id}'
                and receipt.command_hash = material.command_hash
                and receipt.command_envelope_hash = material.command_envelope_hash
                and receipt.process_revision::text = material.material_canonical_json::jsonb #>> '{request,command,process_revision}'
                and receipt.stage_sequence::text = material.material_canonical_json::jsonb #>> '{request,command,stage_sequence}'
                and receipt.graph_key = material.material_canonical_json::jsonb #>> '{request,command,graph_key}'
                and receipt.graph_version = material.material_canonical_json::jsonb #>> '{request,command,graph_version}'
                and receipt.checkpoint_schema_version = material.material_canonical_json::jsonb #>> '{request,command,checkpoint_schema_version}'
                and receipt.formal_writer = 'JAVA_FINALIZER_ONLY'
                and receipt.domain_commit_status = 'COMMITTED'
           )
        """)) {
      statement.setString(1, request.start().tenantSurrogate());
      statement.setString(2, request.start().caseId());
      statement.setLong(3, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next() || row.getLong(1) != 0) {
          throw new IllegalStateException("all EVIDENCE_SUBMIT AgentRun formal receipts are required");
        }
      }
    }
  }

  private static String participantRole(Connection connection, String caseId, String participantId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select participant_role from case_participant
         where case_id = ? and actor_id = ? and participant_status = 'ACTIVE'
           and participant_role in ('USER', 'MERCHANT') for update
        """)) {
      statement.setString(1, caseId);
      statement.setString(2, participantId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence participant is absent");
        String role = row.getString(1);
        if (row.next()) throw new IllegalStateException("target Evidence participant role is ambiguous");
        return role;
      }
    }
  }

  private void completionFact(Connection connection, TerminalRequest request, int version, String role,
      String participantId, String completionId) throws SQLException {
    String id = "EVDPC_" + ContractJson.sha256Hex(mapper.valueToTree(
        List.of(request.start().caseId(), version, role, completionId))).substring(0, 32);
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into evidence_party_completion (
          id, case_id, dossier_version, participant_role, participant_id, completion_status,
          idempotency_key, completed_at, created_by)
        values (?, ?, ?, ?, ?, 'COMPLETED', ?, now(), ?)
        on conflict (case_id, idempotency_key) do nothing
        """)) {
      statement.setString(1, id); statement.setString(2, request.start().caseId()); statement.setInt(3, version);
      statement.setString(4, role); statement.setString(5, participantId); statement.setString(6, completionId);
      statement.setString(7, WRITER); statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select dossier_version, participant_role, participant_id, completion_status
          from evidence_party_completion where case_id = ? and idempotency_key = ? for update
        """)) {
      statement.setString(1, request.start().caseId()); statement.setString(2, completionId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next() || row.getInt(1) != version || !role.equals(row.getString(2))
            || !participantId.equals(row.getString(3)) || !"COMPLETED".equals(row.getString(4)) || row.next()) {
          throw new IllegalStateException("target Evidence completion fact drifted");
        }
      }
    }
  }

  private void sealEvidenceAndOpenHearing(Connection connection, TerminalRequest request,
      String hearingRoomId, Instant hearingDeadline) throws SQLException {
    execute(connection, "update case_room set room_status = 'SEALED', sealed_at = coalesce(sealed_at, now()), updated_at = now(), updated_by = ?, version = version + 1 where id = ? and case_id = ? and room_type = 'EVIDENCE' and room_status in ('OPEN', 'WAITING', 'SEALED')", WRITER, request.start().roomId(), request.start().caseId());
    execute(connection, "update case_phase_clock set clock_status = 'COMPLETED_EARLY', completed_at = coalesce(completed_at, now()), completion_reason = 'BOTH_PARTIES_COMPLETED', updated_at = now(), updated_by = ?, version = version + 1 where case_id = ? and room_id = ? and clock_type = 'EVIDENCE_SUBMISSION' and clock_status in ('RUNNING', 'COMPLETED_EARLY')", WRITER, request.start().caseId(), request.start().roomId());
    execute(connection, "insert into case_room (id, case_id, room_type, room_status, opened_at, metadata_json, created_by, updated_by) values (?, ?, 'HEARING', 'OPEN', now(), '{}'::jsonb, ?, ?) on conflict (case_id, room_type) do nothing", hearingRoomId, request.start().caseId(), WRITER, WRITER);
    execute(connection, "update case_room set room_status = 'OPEN', opened_at = coalesce(opened_at, now()), updated_at = now(), updated_by = ? where case_id = ? and room_type = 'HEARING' and room_status in ('LOCKED', 'OPEN')", WRITER, request.start().caseId());
    execute(connection, "insert into case_phase_clock (id, case_id, room_id, clock_type, clock_status, started_at, deadline_at, temporal_workflow_id, created_by, updated_by) values (?, ?, ?, 'HEARING', 'RUNNING', now(), ?, ?, ?, ?) on conflict (case_id, clock_type) do nothing", "CLOCK_HEARING_" + hearingRoomId.substring("ROOM_HEARING_".length()), request.start().caseId(), hearingRoomId, java.sql.Timestamp.from(hearingDeadline), "target-e2e-hearing-provision-pending", WRITER, WRITER);
    execute(connection, "update fulfillment_dispute_case set case_status = 'HEARING_OPEN', current_room = 'HEARING', current_deadline_at = ?, updated_by = ? where id = ? and case_status in ('EVIDENCE_OPEN', 'EVIDENCE_SEALED', 'HEARING_OPEN')", java.sql.Timestamp.from(hearingDeadline), WRITER, request.start().caseId());
  }

  private void validateHearingAllocation(Connection connection, TerminalRequest request, String hearingRoomId,
      Instant hearingDeadline, RoomEpochAllocation allocation) throws SQLException {
    if (allocation == null || !request.start().tenantSurrogate().equals(allocation.tenantSurrogate())
        || !request.start().caseId().equals(allocation.caseId()) || !hearingRoomId.equals(allocation.roomId())
        || allocation.roomType() != RoomType.HEARING || allocation.writerMode() != WriterMode.TEMPORAL
        || allocation.roomRevision() != 0
        || allocation.processRevision() != Math.incrementExact(request.expectedProcessRevision())
        || allocation.fencingToken() != Math.incrementExact(request.start().fencingToken())
        || allocation.lifecycleStatus()
            != com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus.PREPARING
        || allocation.temporalWorkflowId() == null || allocation.temporalWorkflowId().isBlank()
        || allocation.temporalRunId() != null) {
      throw new IllegalStateException("target Hearing epoch allocation drifted");
    }
    var selection = allocation.selection();
    if (selection == null || selection.writerMode() != WriterMode.TEMPORAL
        || !TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION.equals(selection.selectionSchemaVersion())
        || !TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION.equals(selection.processContractVersion())
        || !TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE.equals(selection.caseWorkflowType())
        || !TargetTypedRoomProtocol.HEARING_WORKFLOW_TYPE.equals(selection.roomWorkflowType())
        || !TargetTypedRoomProtocol.GRAPH_KEY.equals(selection.graphKey())
        || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(selection.graphVersion())
        || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(selection.checkpointSchemaVersion())
        || !TargetTypedRoomProtocol.STREAM_PROTOCOL.equals(selection.streamProtocol())) {
      throw new IllegalStateException("target Hearing epoch selection pins drifted");
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select macro_phase, current_room, room_phase, writer_mode, process_revision, room_epoch,
               fencing_token, projected_deadline_at
          from case_process_projection where case_id = ?
        """)) {
      statement.setString(1, request.start().caseId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Hearing projection allocation drifted");
        }
        String macroPhase = row.getString(1);
        String currentRoom = row.getString(2);
        String roomPhase = row.getString(3);
        String writerMode = row.getString(4);
        long processRevision = row.getLong(5);
        long roomEpoch = row.getLong(6);
        long fencingToken = row.getLong(7);
        java.sql.Timestamp deadline = row.getTimestamp(8);
        if (row.next() || !"HEARING_OPEN".equals(macroPhase)
            || !"HEARING".equals(currentRoom) || !"PROVISIONING".equals(roomPhase)
            || !"TEMPORAL".equals(writerMode) || processRevision != allocation.processRevision()
            || roomEpoch != allocation.roomEpoch() || fencingToken != allocation.fencingToken()
            || deadline == null || !hearingDeadline.equals(deadline.toInstant())) {
          throw new IllegalStateException("target Hearing projection allocation drifted");
        }
      }
    }
    requireTerminalEvidenceEpoch(connection, request);
    requireTargetBindingPreserved(connection, request, allocation);
  }

  private static void requireTerminalEvidenceEpoch(Connection connection, TerminalRequest request)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select lifecycle_status, writer_mode, process_revision, room_revision, fencing_token
          from case_room_epoch
         where case_id = ? and room_type = 'EVIDENCE' and room_epoch = ?
        """)) {
      statement.setString(1, request.start().caseId());
      statement.setLong(2, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Evidence terminal epoch drifted");
        }
        String lifecycle = row.getString(1);
        String writerMode = row.getString(2);
        long processRevision = row.getLong(3);
        long roomRevision = row.getLong(4);
        long fencingToken = row.getLong(5);
        if (row.next() || !"TERMINAL".equals(lifecycle) || !"TEMPORAL".equals(writerMode)
            || processRevision != Math.incrementExact(request.expectedProcessRevision())
            || roomRevision != Math.incrementExact(request.expectedRoomRevision())
            || fencingToken != request.start().fencingToken()) {
          throw new IllegalStateException("target Evidence terminal epoch drifted");
        }
      }
    }
  }

  private static void requireTargetBindingPreserved(Connection connection, TerminalRequest request,
      RoomEpochAllocation allocation) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select next.activation_id, next.activation_manifest_hash, next.execution_lane,
               next.isolated_domain_db_binding_hash
          from target_e2e_room_epoch_binding source
          join target_e2e_room_epoch_binding next
            on next.activation_id = source.activation_id
           and next.activation_manifest_hash = source.activation_manifest_hash
           and next.execution_lane = source.execution_lane
           and next.isolated_domain_db_binding_hash = source.isolated_domain_db_binding_hash
         where source.case_id = ? and source.room_type = 'EVIDENCE' and source.room_epoch = ?
           and source.room_fencing_token = ? and next.epoch_id = ?
           and next.case_id = ? and next.room_type = 'HEARING' and next.room_epoch = ?
           and next.room_fencing_token = ?
        """)) {
      statement.setString(1, request.start().caseId());
      statement.setLong(2, request.start().roomEpoch());
      statement.setLong(3, request.start().fencingToken());
      statement.setString(4, allocation.epochId());
      statement.setString(5, allocation.caseId());
      statement.setLong(6, allocation.roomEpoch());
      statement.setLong(7, allocation.fencingToken());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Hearing epoch activation binding drifted");
        }
        String activationId = row.getString(1);
        String manifestHash = row.getString(2);
        String executionLane = row.getString(3);
        String databaseBindingHash = row.getString(4);
        if (row.next() || activationId == null || activationId.isBlank()
            || !"TARGET_E2E_CANDIDATE".equals(executionLane)
            || !isSha256(manifestHash) || !isSha256(databaseBindingHash)) {
          throw new IllegalStateException("target Hearing epoch activation binding drifted");
        }
      }
    }
  }

  private void appendEventAndOutbox(Connection connection, TerminalRequest request, EvidenceDossierEntity frozen,
      String hearingRoomId, Instant deadline, String receiptHash) throws SQLException {
    String key = "target-e2e-hearing-open:" + request.start().caseId() + ":" + request.start().roomEpoch();
    String eventId = "EVT_HEARING_" + receiptHash.substring(0, 32);
    String payload = ContractJson.canonicalString(mapper.valueToTree(Map.of("schema_version", "target-e2e-evidence-terminal.v1",
        "receipt_hash", receiptHash, "dossier_id", frozen.getId(), "dossier_version", frozen.getDossierVersion(),
        "hearing_room_id", hearingRoomId, "deadline_at", deadline.toString(), "provisioning", "REQUIRED")));
    execute(connection, "insert into case_timeline_event (id, case_id, dossier_id, event_type, event_time, source_refs_json, event_json, sequence_no, room_id, audience_json, event_key, created_by) values (?, ?, ?, 'HEARING_OPENED', now(), '[]'::jsonb, ?::jsonb, (select coalesce(max(sequence_no), 0) + 1 from case_timeline_event where case_id = ?), ?, '[]'::jsonb, ?, ?) on conflict (case_id, event_key) do nothing", eventId, request.start().caseId(), frozen.getId(), payload, request.start().caseId(), hearingRoomId, key, WRITER);
  }

  private void insertReceipt(Connection connection, String receiptId, String receiptHash, String requestHash,
      TerminalRequest request, EvidenceDossierEntity frozen, String hearingRoomId, Instant deadline,
      long processRevision, long roomRevision, Instant committedAt) throws SQLException {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("schema_version", "target-e2e-evidence-terminal-receipt.v1"); canonical.put("receipt_id", receiptId);
    canonical.put("receipt_hash", receiptHash); canonical.put("request_hash", requestHash); canonical.put("case_id", request.start().caseId());
    canonical.put("room_epoch", request.start().roomEpoch()); canonical.put("fencing_token", request.start().fencingToken());
    canonical.put("dossier_id", frozen.getId()); canonical.put("dossier_version", frozen.getDossierVersion());
    canonical.put("hearing_room_id", hearingRoomId); canonical.put("hearing_deadline_at", deadline.toString());
    canonical.put("process_revision", processRevision); canonical.put("room_revision", roomRevision);
    byte[] bytes = ContractJson.canonicalize(mapper.valueToTree(canonical));
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into target_e2e_evidence_terminal_receipt (
          receipt_id, receipt_hash, request_hash, tenant_surrogate, case_id, room_epoch, fencing_token,
          initiator_completion_id, respondent_completion_id, dossier_id, dossier_version, hearing_room_id,
          hearing_deadline_at, process_revision, room_revision, receipt_canonical_bytes, committed_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      int i = 1; statement.setString(i++, receiptId); statement.setString(i++, receiptHash); statement.setString(i++, requestHash);
      statement.setString(i++, request.start().tenantSurrogate()); statement.setString(i++, request.start().caseId());
      statement.setLong(i++, request.start().roomEpoch()); statement.setLong(i++, request.start().fencingToken());
      statement.setString(i++, request.initiatorCompletionId()); statement.setString(i++, request.respondentCompletionId());
      statement.setString(i++, frozen.getId()); statement.setInt(i++, frozen.getDossierVersion()); statement.setString(i++, hearingRoomId);
      statement.setTimestamp(i++, java.sql.Timestamp.from(deadline)); statement.setLong(i++, processRevision); statement.setLong(i++, roomRevision);
      statement.setBytes(i++, bytes); statement.setTimestamp(i, java.sql.Timestamp.from(committedAt)); statement.executeUpdate();
    }
  }

  private Stored readStored(Connection connection, TerminalRequest request, boolean lock) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("select receipt_id, receipt_hash, request_hash from target_e2e_evidence_terminal_receipt where case_id = ? and room_epoch = ?" + (lock ? " for update" : ""))) {
      statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        Stored stored = new Stored(row.getString(1), row.getString(2), row.getString(3));
        if (row.next() || !stored.requestHash().equals(hash(request))) throw new IllegalStateException("target Evidence terminal replay drifted");
        return stored;
      }
    }
  }

  private String hash(TerminalRequest request) { return ContractJson.sha256Hex(mapper.valueToTree(List.of(request.start(), request.expectedProcessRevision(), request.expectedRoomRevision(), request.initiatorCompletionId(), request.respondentCompletionId()))); }
  private String receiptHash(TerminalRequest request, EvidenceDossierEntity frozen, String roomId, Instant deadline, long processRevision, long roomRevision) { return ContractJson.sha256Hex(mapper.valueToTree(List.of("target-e2e-evidence-terminal-receipt.v1", hash(request), frozen.getId(), frozen.getDossierVersion(), roomId, deadline.toString(), processRevision, roomRevision))); }
  private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
  private static void requireTransaction(Connection connection) throws SQLException { if (connection.getAutoCommit()) throw new IllegalStateException("target Evidence terminal requires a transaction"); }
  private static void execute(Connection connection, String sql, Object... values) throws SQLException { try (PreparedStatement s = connection.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); s.executeUpdate(); } }
  private record Stored(String receiptId, String receiptHash, String requestHash) {}
}
