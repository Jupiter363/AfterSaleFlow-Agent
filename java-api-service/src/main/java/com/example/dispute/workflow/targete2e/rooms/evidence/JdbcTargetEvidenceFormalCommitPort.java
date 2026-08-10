package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Caller-transaction formal Evidence writer for the target candidate lane.
 *
 * <p>The admitted command, fenced epoch, projection, visible Evidence fact, and command terminal
 * state advance in the outer target finalizer transaction. A replay observes the exact post-commit
 * state and never advances revisions a second time.
 */
public final class JdbcTargetEvidenceFormalCommitPort implements TargetEvidenceFormalCommitPort {
  private static final String SENDER_ROLE = "EVIDENCE_CLERK";
  private static final String SENDER_ID = "evidence-clerk";
  private static final String SENDER_TYPE = "AGENT";
  private static final String MESSAGE_SOURCE = "AGENT_LLM";
  private static final String MESSAGE_TYPE = "AGENT_MESSAGE";
  private final ObjectMapper objectMapper;

  public JdbcTargetEvidenceFormalCommitPort(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  @Override
  public CommitResult commit(
      Connection transaction,
      TargetEvidenceFinalizationRequest request,
      RoomMessageView formalMessage) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(formalMessage, "formalMessage");
    try {
      requireCallerTransaction(transaction);
      var graph = request.command().request().command();
      require(graph.roomType() == RoomType.EVIDENCE, "graph room type is not Evidence");

      Admission admission = lockAdmission(transaction, request.admissionId());
      requireAdmission(request, graph, admission);
      String rootCommandId = lockRootCommandId(transaction, graph.logicalRunId());
      requireRootCommandLineage(request.command().request(), rootCommandId);
      Command command = lockCommand(transaction, graph.tenantSurrogate(), rootCommandId);
      requireCommand(request, graph, command);
      Epoch epoch = lockEpoch(transaction, graph.tenantSurrogate(), graph.caseId(), graph.roomEpoch());
      requireEpochBinding(request, graph, epoch);
      Projection projection = lockProjection(transaction, graph.caseId());
      requireProjectionBinding(request, graph, projection);
      String roomId = lockEvidenceRoom(transaction, epoch.roomId(), graph.caseId());
      Existing existing = lockFormalMessage(transaction, formalMessage.id());
      requireFormalMessage(request, formalMessage, existing, roomId);
      String formalObjectId = existing.id();
      String resultUri = "urn:target-e2e:evidence-formal-message:" + formalObjectId;
      String formalCommitHash = formalHash(request, existing, command.sequence());

      if ("APPLIED".equals(command.status())) {
        requireReplayState(
            request,
            command,
            epoch,
            projection,
            resultUri,
            formalCommitHash);
        return new CommitResult(formalObjectId, formalCommitHash);
      }
      require("ORCHESTRATION_ACCEPTED".equals(command.status()),
          "case command is not ready for Evidence formalization");
      requireInitialCoordinates(request, epoch, projection);

      advanceEpoch(transaction, epoch.id(), request);
      advanceProjection(transaction, graph.caseId(), request);
      applyCommand(transaction, command, request, graph, resultUri, formalCommitHash);
      return new CommitResult(formalObjectId, formalCommitHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence formal commit failed", failure);
    }
  }

  private static Admission lockAdmission(Connection transaction, String admissionId)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select activation_id, activation_manifest_hash, execution_lane,
               isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
               command_hash, command_envelope_hash, room_epoch, room_fencing_token
          from target_e2e_command_admission
         where admission_id = ?
         for key share
        """)) {
      statement.setString(1, admissionId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence admission is absent");
        Admission value =
            new Admission(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getLong(10),
                row.getLong(11));
        require(!row.next(), "target Evidence admission is ambiguous");
        return value;
      }
    }
  }

  private static void requireAdmission(
      TargetEvidenceFinalizationRequest request,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      Admission admission) {
    require(request.activationId().equals(admission.activationId()), "activation id drifted");
    require(
        request.activationManifestHash().equals(admission.activationManifestHash()),
        "activation manifest drifted");
    require(request.executionLane().equals(admission.executionLane()), "execution lane drifted");
    require(
        request.isolatedDomainDbBindingHash().equals(admission.databaseBindingHash()),
        "isolated database binding drifted");
    require(graph.tenantSurrogate().equals(admission.tenant()), "admission tenant drifted");
    require(graph.caseId().equals(admission.caseId()), "admission case drifted");
    require(graph.commandId().equals(admission.commandId()), "admission command drifted");
    require(request.commandHash().equals(admission.commandHash()), "admission command hash drifted");
    require(
        request.commandEnvelopeHash().equals(admission.commandEnvelopeHash()),
        "admission command envelope hash drifted");
    require(graph.roomEpoch() == admission.roomEpoch(), "admission room epoch drifted");
    require(
        request.roomFencingToken() == admission.roomFencingToken(),
        "admission room fence drifted");
  }

  private static Command lockCommand(Connection transaction, String tenant, String commandId)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select id, tenant_surrogate, case_id, command_id, case_command_sequence,
               command_type, room_type, room_epoch, actor_id, actor_role,
               actor_scopes_json::text, payload_schema_version, payload_uri, payload_sha256,
               payload_size_bytes, expected_process_revision, request_hash, deadline_at,
               command_status, result_uri, result_sha256
          from case_command
         where tenant_surrogate = ? and command_id = ?
         for update
        """)) {
      statement.setString(1, tenant);
      statement.setString(2, commandId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence case command is absent");
        Command value =
            new Command(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getLong(5),
                row.getString(6),
                row.getString(7),
                row.getLong(8),
                row.getString(9),
                row.getString(10),
                row.getString(11),
                row.getString(12),
                row.getString(13),
                row.getString(14),
                row.getLong(15),
                row.getLong(16),
                row.getString(17),
                row.getTimestamp(18).toInstant(),
                row.getString(19),
                row.getString(20),
                row.getString(21));
        require(!row.next(), "target Evidence case command is ambiguous");
        return value;
      }
    }
  }

  private static String lockRootCommandId(Connection transaction, String logicalRunId)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select attempt.command_id
          from agent_run run
          join agent_run_attempt attempt
            on attempt.agent_run_id = run.id and attempt.attempt_no = 1
         where run.id = ? and run.protocol = 'agent-stream.v2'
           and run.executor_kind = 'TEMPORAL_ACTIVITY'
         for key share of run, attempt
        """)) {
      statement.setString(1, logicalRunId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence root AgentRun command is absent");
        String commandId = row.getString(1);
        require(!row.next(), "target Evidence root AgentRun command is ambiguous");
        return commandId;
      }
    }
  }

  private static void requireRootCommandLineage(
      com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest request,
      String rootCommandId) {
    boolean initial = request.attemptNo() == 1;
    require(
        initial
            ? rootCommandId.equals(request.command().commandId())
            : !rootCommandId.equals(request.command().commandId()),
        "target Evidence root and winning command lineage drifted");
  }

  private void requireCommand(
      TargetEvidenceFinalizationRequest request,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      Command command) {
    var event = graph.eventRef();
    require(event != null, "Evidence graph event reference is absent");
    require(graph.tenantSurrogate().equals(command.tenant()), "case command tenant drifted");
    require(graph.caseId().equals(command.caseId()), "case command case drifted");
    require(command.commandId() != null && !command.commandId().isBlank(), "case command id drifted");
    require(command.sequence() > 0, "case command sequence is invalid");
    require("EVIDENCE_SUBMIT".equals(command.commandType()), "case command type drifted");
    require("EVIDENCE".equals(command.roomType()), "case command room type drifted");
    require(graph.roomEpoch() == command.roomEpoch(), "case command room epoch drifted");
    require(request.actorId().equals(command.actorId()), "case command actor id drifted");
    require(request.actorRole().name().equals(command.actorRole()), "case command actor role drifted");
    requireActorScopes(command.actorScopesJson(), graph.actorScope().capabilities());
    require(
        request.expectedProcessRevision() == command.expectedProcessRevision(),
        "case command process revision drifted");
    require(event.schemaVersion().equals(command.payloadSchemaVersion()), "case command payload schema drifted");
    require(event.uri().equals(command.payloadUri()), "case command payload URI drifted");
    require(event.sha256().equals(command.payloadHash()), "case command payload hash drifted");
    require(event.sizeBytes() == command.payloadSize(), "case command payload size drifted");
    require(
        request.caseCommandRequestHash().equals(command.requestHash()),
        "case command request hash drifted");
    require(graph.deadlineAt().equals(command.deadline()), "case command deadline drifted");
  }

  private void requireActorScopes(String storedJson, List<String> frozenCapabilities) {
    try {
      var stored = objectMapper.readTree(storedJson);
      require(
          stored != null
              && stored.isArray()
              && stored.equals(objectMapper.valueToTree(frozenCapabilities)),
          "case command actor scopes drifted");
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("case command actor scopes are invalid", failure);
    }
  }

  private static Epoch lockEpoch(Connection transaction, String tenant, String caseId, long roomEpoch)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select epoch.id, epoch.room_id, epoch.tenant_surrogate, epoch.case_id,
               epoch.lifecycle_status, epoch.writer_mode, epoch.process_revision,
               epoch.room_revision, epoch.fencing_token, binding.activation_id,
               binding.activation_manifest_hash, binding.execution_lane,
               binding.isolated_domain_db_binding_hash
          from case_room_epoch epoch
          join target_e2e_room_epoch_binding binding on binding.epoch_id = epoch.id
         where epoch.tenant_surrogate = ? and epoch.case_id = ?
           and epoch.room_type = 'EVIDENCE' and epoch.room_epoch = ?
         for update of epoch
        """)) {
      statement.setString(1, tenant);
      statement.setString(2, caseId);
      statement.setLong(3, roomEpoch);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence epoch is absent");
        Epoch value =
            new Epoch(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6),
                row.getLong(7),
                row.getLong(8),
                row.getLong(9),
                row.getString(10),
                row.getString(11),
                row.getString(12),
                row.getString(13));
        require(!row.next(), "target Evidence epoch binding is ambiguous");
        return value;
      }
    }
  }

  private static void requireEpochBinding(
      TargetEvidenceFinalizationRequest request,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      Epoch epoch) {
    require(graph.tenantSurrogate().equals(epoch.tenant()), "Evidence epoch tenant drifted");
    require(graph.caseId().equals(epoch.caseId()), "Evidence epoch case drifted");
    require("ACTIVE".equals(epoch.lifecycle()), "Evidence epoch is not active");
    require("TEMPORAL".equals(epoch.writerMode()), "Evidence epoch is not Temporal-owned");
    require(request.roomFencingToken() == epoch.fencingToken(), "Evidence epoch fence drifted");
    require(request.activationId().equals(epoch.activationId()), "Evidence epoch activation drifted");
    require(
        request.activationManifestHash().equals(epoch.activationManifestHash()),
        "Evidence epoch activation manifest drifted");
    require(request.executionLane().equals(epoch.executionLane()), "Evidence epoch lane drifted");
    require(
        request.isolatedDomainDbBindingHash().equals(epoch.databaseBindingHash()),
        "Evidence epoch database binding drifted");
  }

  private static Projection lockProjection(Connection transaction, String caseId)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select tenant_surrogate, macro_phase, current_room, room_phase, writer_mode,
               process_revision, room_epoch, fencing_token
          from case_process_projection
         where case_id = ?
         for update
        """)) {
      statement.setString(1, caseId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence process projection is absent");
        Projection value =
            new Projection(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getLong(6),
                row.getLong(7),
                row.getLong(8));
        require(!row.next(), "target Evidence process projection is ambiguous");
        return value;
      }
    }
  }

  private static void requireProjectionBinding(
      TargetEvidenceFinalizationRequest request,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      Projection projection) {
    require(graph.tenantSurrogate().equals(projection.tenant()), "Evidence projection tenant drifted");
    require("EVIDENCE_OPEN".equals(projection.macroPhase()), "Evidence projection macro phase drifted");
    require("EVIDENCE".equals(projection.currentRoom()), "Evidence projection current room drifted");
    require("OPEN".equals(projection.roomPhase()), "Evidence projection room phase drifted");
    require("TEMPORAL".equals(projection.writerMode()), "Evidence projection is not Temporal-owned");
    require(graph.roomEpoch() == projection.roomEpoch(), "Evidence projection room epoch drifted");
    require(request.roomFencingToken() == projection.fencingToken(), "Evidence projection fence drifted");
  }

  private static void requireInitialCoordinates(
      TargetEvidenceFinalizationRequest request, Epoch epoch, Projection projection) {
    require(
        epoch.processRevision() == request.expectedProcessRevision()
            && epoch.roomRevision() == request.expectedRoomRevision(),
        "Evidence epoch revisions drifted before formalization");
    require(
        projection.processRevision() == request.expectedProcessRevision(),
        "Evidence projection revision drifted before formalization");
  }

  private static void requireReplayState(
      TargetEvidenceFinalizationRequest request,
      Command command,
      Epoch epoch,
      Projection projection,
      String resultUri,
      String formalCommitHash) {
    require(
        resultUri.equals(command.resultUri()) && formalCommitHash.equals(command.resultHash()),
        "Evidence applied command result drifted");
    require(
        epoch.processRevision() == Math.incrementExact(request.expectedProcessRevision())
            && epoch.roomRevision() == Math.incrementExact(request.expectedRoomRevision()),
        "Evidence replay epoch revisions drifted");
    require(
        projection.processRevision() == Math.incrementExact(request.expectedProcessRevision()),
        "Evidence replay projection revision drifted");
  }

  private static String lockEvidenceRoom(
      Connection transaction, String roomId, String caseId) throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select id, room_status from case_room
         where id = ? and case_id = ? and room_type = 'EVIDENCE'
         for update
        """)) {
      statement.setString(1, roomId);
      statement.setString(2, caseId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence room is absent");
        String value = row.getString(1);
        require("OPEN".equals(row.getString(2)), "target Evidence room is not open");
        require(!row.next(), "target Evidence room is ambiguous");
        return value;
      }
    }
  }

  private static Existing lockFormalMessage(Connection transaction, String messageId)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        select id, case_id, room_id, sequence_no, sender_type, sender_role, sender_id,
               message_source, message_type, message_text, agent_run_id, idempotency_key, created_by
          from room_message
         where id = ?
         for update
        """)) {
      statement.setString(1, messageId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "target Evidence formal message is absent");
        Existing existing =
            new Existing(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getLong(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getString(10),
                row.getString(11),
                row.getString(12),
                row.getString(13));
        require(!row.next(), "target Evidence formal message is ambiguous");
        return existing;
      }
    }
  }

  private static void requireFormalMessage(
      TargetEvidenceFinalizationRequest request,
      RoomMessageView formalMessage,
      Existing existing,
      String roomId) {
    var graph = request.command().request().command();
    require(formalMessage.id().equals(existing.id()), "Evidence formal object id drifted");
    require(graph.caseId().equals(formalMessage.caseId())
            && graph.caseId().equals(existing.caseId()),
        "Evidence formal case drifted");
    require(roomId.equals(formalMessage.roomId()) && roomId.equals(existing.roomId()),
        "Evidence formal room drifted");
    require(formalMessage.sequenceNo() > 0
            && formalMessage.sequenceNo() == existing.sequence(),
        "Evidence formal sequence drifted");
    require(SENDER_TYPE.equals(existing.senderType()), "Evidence formal sender type drifted");
    require(SENDER_ROLE.equals(formalMessage.senderRole())
            && SENDER_ROLE.equals(existing.senderRole()),
        "Evidence formal sender role drifted");
    require(SENDER_ID.equals(formalMessage.senderId()) && SENDER_ID.equals(existing.senderId()),
        "Evidence formal sender drifted");
    require(formalMessage.messageSource() == MessageSource.AGENT_LLM
            && MESSAGE_SOURCE.equals(existing.messageSource()),
        "Evidence formal source drifted");
    require(formalMessage.messageType() == MessageType.AGENT_MESSAGE
            && MESSAGE_TYPE.equals(existing.messageType()),
        "Evidence formal message type drifted");
    require(request.proposal().roomUtterance().equals(formalMessage.messageText())
            && formalMessage.messageText().equals(existing.messageText()),
        "Evidence formal message drifted");
    require(request.command().request().agentRunId().equals(formalMessage.agentRunId())
            && formalMessage.agentRunId().equals(existing.agentRunId()),
        "Evidence formal AgentRun drifted");
    require(expectedMessageIdempotencyKey(request).equals(existing.idempotencyKey()),
        "Evidence formal idempotency authority drifted");
    require(SENDER_ID.equals(existing.createdBy()), "Evidence formal creator drifted");
  }

  private static String expectedMessageIdempotencyKey(
      TargetEvidenceFinalizationRequest request) {
    var turn = request.material().material().evidenceAgentTurnCommand();
    var context = turn.agentContext();
    var event = turn.contextEnvelope().currentEvent();
    require("PARTY_MESSAGE".equals(event.eventType()),
        "Evidence formal event type drifted");
    require(context.agentSessionId() != null && !context.agentSessionId().isBlank(),
        "Evidence formal agent session is invalid");
    require(event.turnNo() > 0, "Evidence formal turn number is invalid");
    return "agent-evidence-turn:"
        + request.command().request().command().caseId()
        + ":"
        + context.agentSessionId()
        + ":"
        + request.actorRole().name()
        + ":"
        + event.turnNo();
  }

  private static void advanceEpoch(
      Connection transaction, String epochId, TargetEvidenceFinalizationRequest request)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        update case_room_epoch
           set process_revision = ?, room_revision = ?, updated_at = now(), version = version + 1
         where id = ? and lifecycle_status = 'ACTIVE' and writer_mode = 'TEMPORAL'
           and process_revision = ? and room_revision = ? and fencing_token = ?
        """)) {
      statement.setLong(1, Math.incrementExact(request.expectedProcessRevision()));
      statement.setLong(2, Math.incrementExact(request.expectedRoomRevision()));
      statement.setString(3, epochId);
      statement.setLong(4, request.expectedProcessRevision());
      statement.setLong(5, request.expectedRoomRevision());
      statement.setLong(6, request.roomFencingToken());
      require(statement.executeUpdate() == 1, "target Evidence epoch revision CAS failed");
    }
  }

  private static void advanceProjection(
      Connection transaction, String caseId, TargetEvidenceFinalizationRequest request)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        update case_process_projection
           set process_revision = ?, updated_at = now(), version = version + 1
         where case_id = ? and current_room = 'EVIDENCE' and room_phase = 'OPEN'
           and writer_mode = 'TEMPORAL' and process_revision = ? and fencing_token = ?
        """)) {
      statement.setLong(1, Math.incrementExact(request.expectedProcessRevision()));
      statement.setString(2, caseId);
      statement.setLong(3, request.expectedProcessRevision());
      statement.setLong(4, request.roomFencingToken());
      require(statement.executeUpdate() == 1, "target Evidence projection revision CAS failed");
    }
  }

  private static void applyCommand(
      Connection transaction,
      Command command,
      TargetEvidenceFinalizationRequest request,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      String resultUri,
      String resultHash)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        update case_command
           set command_status = 'APPLIED', status_reason_code = null,
               result_uri = ?, result_sha256 = ?, applied_at = now(),
               updated_at = now(), version = version + 1
         where id = ? and tenant_surrogate = ? and case_id = ? and command_id = ?
           and case_command_sequence = ? and command_type = 'EVIDENCE_SUBMIT'
           and room_type = 'EVIDENCE' and room_epoch = ?
           and actor_id = ? and actor_role = ? and actor_scopes_json = ?::jsonb
           and payload_schema_version = ? and payload_uri = ? and payload_sha256 = ?
           and payload_size_bytes = ? and expected_process_revision = ?
           and request_hash = ? and deadline_at = ?
           and command_status = 'ORCHESTRATION_ACCEPTED'
        """)) {
      statement.setString(1, resultUri);
      statement.setString(2, resultHash);
      statement.setString(3, command.id());
      statement.setString(4, graph.tenantSurrogate());
      statement.setString(5, graph.caseId());
      statement.setString(6, command.commandId());
      statement.setLong(7, command.sequence());
      statement.setLong(8, graph.roomEpoch());
      statement.setString(9, request.actorId());
      statement.setString(10, request.actorRole().name());
      statement.setString(11, command.actorScopesJson());
      statement.setString(12, command.payloadSchemaVersion());
      statement.setString(13, command.payloadUri());
      statement.setString(14, command.payloadHash());
      statement.setLong(15, command.payloadSize());
      statement.setLong(16, request.expectedProcessRevision());
      statement.setString(17, request.caseCommandRequestHash());
      statement.setTimestamp(18, java.sql.Timestamp.from(command.deadline()));
      require(statement.executeUpdate() == 1, "target Evidence command APPLIED CAS failed");
    }
  }

  private static void requireCallerTransaction(Connection transaction) throws SQLException {
    if (transaction.getAutoCommit()
        || transaction.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
      throw new IllegalStateException(
          "target Evidence formal commit requires caller repeatable-read transaction");
    }
  }

  private String formalHash(
      TargetEvidenceFinalizationRequest request, Existing message, long commandSequence) {
    var proposal = request.proposal();
    return ContractJson.sha256Hex(
        objectMapper.valueToTree(
            List.of(
                "target-e2e-evidence-formal-commit.v3",
                message.id(),
                message.caseId(),
                message.roomId(),
                message.sequence(),
                message.senderType(),
                message.senderRole(),
                message.senderId(),
                message.messageSource(),
                message.messageType(),
                message.messageText(),
                message.agentRunId(),
                message.idempotencyKey(),
                request.activationId(),
                request.activationManifestHash(),
                request.admissionId(),
                request.isolatedDomainDbBindingHash(),
                request.commandHash(),
                request.commandEnvelopeHash(),
                request.caseCommandRequestHash(),
                request.formalOperationId(),
                commandSequence,
                request.roomFencingToken(),
                request.expectedProcessRevision(),
                request.expectedRoomRevision(),
                request.actorId(),
                request.actorRole().name(),
                proposal.payloadRef(),
                proposal.payloadHash(),
                proposal.proposalHash(),
                proposal.roomUtteranceSha256(),
                ContractJson.sha256Hex(proposal.evidenceTurnResultJson()),
                proposal.usage().inputTokens(),
                proposal.usage().outputTokens(),
                proposal.usage().totalTokens(),
                request.command().result().resultHash())));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private record Admission(
      String activationId,
      String activationManifestHash,
      String executionLane,
      String databaseBindingHash,
      String tenant,
      String caseId,
      String commandId,
      String commandHash,
      String commandEnvelopeHash,
      long roomEpoch,
      long roomFencingToken) {}

  private record Command(
      String id,
      String tenant,
      String caseId,
      String commandId,
      long sequence,
      String commandType,
      String roomType,
      long roomEpoch,
      String actorId,
      String actorRole,
      String actorScopesJson,
      String payloadSchemaVersion,
      String payloadUri,
      String payloadHash,
      long payloadSize,
      long expectedProcessRevision,
      String requestHash,
      Instant deadline,
      String status,
      String resultUri,
      String resultHash) {}

  private record Epoch(
      String id,
      String roomId,
      String tenant,
      String caseId,
      String lifecycle,
      String writerMode,
      long processRevision,
      long roomRevision,
      long fencingToken,
      String activationId,
      String activationManifestHash,
      String executionLane,
      String databaseBindingHash) {}

  private record Projection(
      String tenant,
      String macroPhase,
      String currentRoom,
      String roomPhase,
      String writerMode,
      long processRevision,
      long roomEpoch,
      long fencingToken) {}

  private record Existing(
      String id,
      String caseId,
      String roomId,
      long sequence,
      String senderType,
      String senderRole,
      String senderId,
      String messageSource,
      String messageType,
      String messageText,
      String agentRunId,
      String idempotencyKey,
      String createdBy) {}
}
