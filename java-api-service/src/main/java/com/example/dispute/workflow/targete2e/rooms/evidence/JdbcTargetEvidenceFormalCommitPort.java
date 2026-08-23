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
      String formalCommitHash = formalHash(request, existing, command);

      if ("APPLIED".equals(command.status())) {
        persistOrVerifyFrameAuthority(transaction, request, false);
        persistOrVerifyTurnProjection(transaction, request, formalMessage, false);
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
      requireInitialCoordinates(request, command, epoch, projection);

      persistOrVerifyFrameAuthority(transaction, request, true);
      persistOrVerifyTurnProjection(transaction, request, formalMessage, true);

      advanceEpoch(transaction, epoch.id(), request);
      advanceProjection(transaction, graph.caseId(), request, command);
      applyCommand(transaction, command, request, graph, resultUri, formalCommitHash);
      return new CommitResult(formalObjectId, formalCommitHash);
    } catch (SQLException failure) {
      if (TargetEvidenceFinalizationPersistenceException.isRetryableTransactionConflict(failure)) {
        throw new TargetEvidenceFinalizationPersistenceException(
            "target Evidence formal commit was interrupted by a transient transaction conflict",
            failure);
      }
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
         where run.id = ? and run.protocol = 'agent-stream.v3'
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
    String expectedCommandType = expectedCommandType(request);
    require(expectedCommandType.equals(command.commandType()), "case command type drifted");
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
               process_revision, room_epoch, fencing_token, last_command_sequence
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
                row.getLong(8),
                row.getLong(9));
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
      TargetEvidenceFinalizationRequest request,
      Command command,
      Epoch epoch,
      Projection projection) {
    require(
        epoch.processRevision() == request.expectedProcessRevision()
            && epoch.roomRevision() == request.expectedRoomRevision(),
        "Evidence epoch revisions drifted before formalization");
    require(
        projection.processRevision() == request.expectedProcessRevision(),
        "Evidence projection revision drifted before formalization");
    require(
        projection.lastCommandSequence() == Math.decrementExact(command.sequence()),
        "Evidence projection command cursor drifted before formalization");
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
    require(
        projection.lastCommandSequence() == command.sequence(),
        "Evidence replay projection command cursor drifted");
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
    if ("ROOM_OPENING".equals(event.eventType())) {
      require(event.messageType().name().equals("AGENT_MESSAGE"),
          "Evidence opening message type drifted");
      require(event.attachmentRefs().isEmpty(),
          "Evidence opening unexpectedly carries attachments");
      require(event.eventId() != null && !event.eventId().isBlank(),
          "Evidence opening idempotency authority is absent");
      return event.eventId();
    }
    require("PARTY_MESSAGE".equals(event.eventType()), "Evidence formal event type drifted");
    require(event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE"),
        "Evidence submission message type drifted");
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
      Connection transaction,
      String caseId,
      TargetEvidenceFinalizationRequest request,
      Command command)
      throws SQLException {
    try (PreparedStatement statement = transaction.prepareStatement("""
        update case_process_projection
           set process_revision = ?, last_command_sequence = ?,
               updated_at = now(), version = version + 1
         where case_id = ? and current_room = 'EVIDENCE' and room_phase = 'OPEN'
           and writer_mode = 'TEMPORAL' and process_revision = ? and fencing_token = ?
           and last_command_sequence = ?
        """)) {
      statement.setLong(1, Math.incrementExact(request.expectedProcessRevision()));
      statement.setLong(2, command.sequence());
      statement.setString(3, caseId);
      statement.setLong(4, request.expectedProcessRevision());
      statement.setLong(5, request.roomFencingToken());
      statement.setLong(6, Math.decrementExact(command.sequence()));
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
           and case_command_sequence = ? and command_type = ?
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
      statement.setString(8, expectedCommandType(request));
      statement.setLong(9, graph.roomEpoch());
      statement.setString(10, request.actorId());
      statement.setString(11, request.actorRole().name());
      statement.setString(12, command.actorScopesJson());
      statement.setString(13, command.payloadSchemaVersion());
      statement.setString(14, command.payloadUri());
      statement.setString(15, command.payloadHash());
      statement.setLong(16, command.payloadSize());
      statement.setLong(17, request.expectedProcessRevision());
      statement.setString(18, request.caseCommandRequestHash());
      statement.setTimestamp(19, java.sql.Timestamp.from(command.deadline()));
      require(statement.executeUpdate() == 1, "target Evidence command APPLIED CAS failed");
    }
  }

  private void persistOrVerifyFrameAuthority(
      Connection transaction,
      TargetEvidenceFinalizationRequest request,
      boolean allowInsert) throws SQLException {
    var graph = request.command().request().command();
    for (TargetEvidenceTurnResultV2.Frame frame :
        request.proposal().evidenceTurnResult().frames()) {
      String privateHeader = ContractJson.canonicalString(frame.header());
      boolean publicFrame = frame.publicText() != null;
      String publicHeader = publicFrame ? privateHeader : "{}";
      if (allowInsert) {
        try (PreparedStatement statement = transaction.prepareStatement("""
            insert into agent_run_frame_authority (
                id, agent_run_id, agent_run_attempt_id, command_id, frame_id,
                frame_sequence, frame_type, private_header, public_header, public_text,
                header_sha256, public_text_sha256, frame_sha256
            ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            on conflict (agent_run_id, agent_run_attempt_id, frame_id) do nothing
            """)) {
          statement.setString(1, "ARFA_" + frame.frameId());
          statement.setString(2, graph.logicalRunId());
          statement.setString(3, graph.attemptId());
          statement.setString(4, graph.commandId());
          statement.setString(5, frame.frameId());
          statement.setInt(6, frame.frameSequence());
          statement.setString(7, frame.frameType());
          statement.setString(8, privateHeader);
          statement.setString(9, publicHeader);
          statement.setString(10, frame.publicText());
          statement.setString(11, frame.headerSha256());
          statement.setString(12, frame.publicTextSha256());
          statement.setString(13, frame.frameSha256());
          statement.executeUpdate();
        }
      }

      List<FrameAuthorityRow> rows;
      try (PreparedStatement statement = transaction.prepareStatement("""
          select command_id, frame_sequence, frame_type,
                 private_header::text, public_header::text, public_text,
                 header_sha256, public_text_sha256, frame_sha256
            from agent_run_frame_authority
           where agent_run_id = ? and agent_run_attempt_id = ? and frame_id = ?
           for key share
          """)) {
        statement.setString(1, graph.logicalRunId());
        statement.setString(2, graph.attemptId());
        statement.setString(3, frame.frameId());
        try (ResultSet result = statement.executeQuery()) {
          var collected = new java.util.ArrayList<FrameAuthorityRow>();
          while (result.next()) {
            collected.add(new FrameAuthorityRow(
                result.getString(1), result.getInt(2), result.getString(3),
                result.getString(4), result.getString(5), result.getString(6),
                result.getString(7), result.getString(8), result.getString(9)));
          }
          rows = List.copyOf(collected);
        }
      }
      require(rows.size() == 1, "Evidence frame authority is absent or ambiguous");
      FrameAuthorityRow stored = rows.getFirst();
      try {
        require(stored.commandId().equals(graph.commandId())
                && stored.frameSequence() == frame.frameSequence()
                && stored.frameType().equals(frame.frameType())
                && objectMapper.readTree(stored.privateHeader()).equals(frame.header())
                && objectMapper.readTree(stored.publicHeader()).equals(
                    publicFrame ? frame.header() : objectMapper.createObjectNode())
                && Objects.equals(stored.publicText(), frame.publicText())
                && stored.headerSha256().equals(frame.headerSha256())
                && stored.publicTextSha256().equals(frame.publicTextSha256())
                && stored.frameSha256().equals(frame.frameSha256()),
            "Evidence frame authority replay differs");
      } catch (JsonProcessingException failure) {
        throw new IllegalStateException("Evidence frame authority JSON is invalid", failure);
      }

      int publicRows;
      try (PreparedStatement statement = transaction.prepareStatement("""
          select count(*)
            from agent_run_public_frame
           where agent_run_id = ? and agent_run_attempt_id = ? and frame_id = ?
             and frame_sequence = ? and frame_type = ?
             and header_sha256 = ? and public_text_sha256 = ? and frame_sha256 = ?
          """)) {
        statement.setString(1, graph.logicalRunId());
        statement.setString(2, graph.attemptId());
        statement.setString(3, frame.frameId());
        statement.setInt(4, frame.frameSequence());
        statement.setString(5, frame.frameType());
        statement.setString(6, frame.headerSha256());
        statement.setString(7, frame.publicTextSha256());
        statement.setString(8, frame.frameSha256());
        try (ResultSet result = statement.executeQuery()) {
          require(result.next(), "Evidence public frame count is absent");
          publicRows = result.getInt(1);
        }
      }
      require(publicRows == (publicFrame ? 1 : 0),
          "Evidence public frame projection differs from private authority");
    }
  }

  private void persistOrVerifyTurnProjection(
      Connection transaction,
      TargetEvidenceFinalizationRequest request,
      RoomMessageView formalMessage,
      boolean allowInsert) throws SQLException {
    var graph = request.command().request().command();
    TargetEvidenceTurnResultV2 result = request.proposal().evidenceTurnResult();
    String resultHash = ContractJson.sha256Hex(result.document());
    String projectionId = "ETPV2_" + resultHash.substring(0, 32).toUpperCase();
    String observations = ContractJson.canonicalString(
        objectMapper.valueToTree(result.observationGraph()));
    String assessments = ContractJson.canonicalString(
        objectMapper.valueToTree(result.evidenceAssessments()));
    String requests = ContractJson.canonicalString(
        objectMapper.valueToTree(result.evidenceRequests()));
    // The v3 protocol has no model-authored review task.  The retained column is
    // written as an empty transport projection until the schema migration drops it.
    String reviews = ContractJson.canonicalString(objectMapper.createArrayNode());
    String readiness = ContractJson.canonicalString(result.roomReadiness());
    if (allowInsert) {
      try (PreparedStatement statement = transaction.prepareStatement("""
          insert into evidence_turn_projection_v2 (
              id, case_id, room_epoch, command_id, agent_run_id,
              agent_run_attempt_id, actor_id, actor_role, room_message_id,
              frame_manifest_sha256, result_sha256, observation_graph,
              evidence_assessments, evidence_requests, human_review_tasks,
              room_readiness
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?::jsonb)
          on conflict (case_id, command_id) do nothing
          """)) {
        statement.setString(1, projectionId);
        statement.setString(2, graph.caseId());
        statement.setLong(3, graph.roomEpoch());
        statement.setString(4, graph.commandId());
        statement.setString(5, graph.logicalRunId());
        statement.setString(6, graph.attemptId());
        statement.setString(7, request.actorId());
        statement.setString(8, request.actorRole().name());
        statement.setString(9, formalMessage.id());
        statement.setString(10, result.frameManifestSha256());
        statement.setString(11, resultHash);
        statement.setString(12, observations);
        statement.setString(13, assessments);
        statement.setString(14, requests);
        statement.setString(15, reviews);
        statement.setString(16, readiness);
        statement.executeUpdate();
      }
    }
    int exactProjection;
    try (PreparedStatement statement = transaction.prepareStatement("""
        select count(*)
          from evidence_turn_projection_v2
         where id = ? and case_id = ? and room_epoch = ? and command_id = ?
           and agent_run_id = ? and agent_run_attempt_id = ?
           and actor_id = ? and actor_role = ? and room_message_id = ?
           and frame_manifest_sha256 = ? and result_sha256 = ?
           and observation_graph = ?::jsonb and evidence_assessments = ?::jsonb
           and evidence_requests = ?::jsonb and human_review_tasks = ?::jsonb
           and room_readiness = ?::jsonb
        """)) {
      statement.setString(1, projectionId);
      statement.setString(2, graph.caseId());
      statement.setLong(3, graph.roomEpoch());
      statement.setString(4, graph.commandId());
      statement.setString(5, graph.logicalRunId());
      statement.setString(6, graph.attemptId());
      statement.setString(7, request.actorId());
      statement.setString(8, request.actorRole().name());
      statement.setString(9, formalMessage.id());
      statement.setString(10, result.frameManifestSha256());
      statement.setString(11, resultHash);
      statement.setString(12, observations);
      statement.setString(13, assessments);
      statement.setString(14, requests);
      statement.setString(15, reviews);
      statement.setString(16, readiness);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next(), "Evidence v2 projection verification returned no row");
        exactProjection = row.getInt(1);
      }
    }
    require(exactProjection == 1, "Evidence v2 projection replay differs");

    java.util.Map<String, String> evidenceByObservation = new java.util.LinkedHashMap<>();
    for (var assessment : result.evidenceAssessments()) {
      String evidenceId = assessment.path("evidence_id").asText("");
      for (var slot : assessment.path("observation_slots")) {
        String previous = evidenceByObservation.putIfAbsent(slot.asText(), evidenceId);
        require(previous == null || previous.equals(evidenceId),
            "Evidence observation is attributed to conflicting attachments");
      }
    }
    java.util.List<FactEdge> edges = new java.util.ArrayList<>();
    for (var observation : result.observationGraph()) {
      if (!"BOUND".equals(observation.path("binding_status").asText())) {
        continue;
      }
      String slot = observation.path("observation_slot").asText("");
      String evidenceId = evidenceByObservation.get(slot);
      if (slot.isBlank() || evidenceId == null || evidenceId.isBlank()) {
        // A model may omit an optional binding.  Persist the public frame but
        // do not synthesize an unauthorised formal fact edge from missing data.
        continue;
      }
      String sourceUnitId = observation.path("source_unit_id").asText("");
      for (var binding : observation.path("fact_bindings")) {
        String factId = binding.path("fact_id").asText("");
        if (sourceUnitId.isBlank() || factId.isBlank()) {
          continue;
        }
        edges.add(new FactEdge(
            evidenceId,
            sourceUnitId,
            slot,
            factId,
            binding.path("relation").asText(""),
            binding.path("reason").asText("")));
      }
    }
    for (FactEdge edge : edges) {
      String edgeHash = ContractJson.sha256Hex(objectMapper.valueToTree(List.of(
          projectionId, edge.evidenceId(), edge.sourceUnitId(), edge.observationSlot(),
          edge.factId(), edge.relation(), edge.reason())));
      if (allowInsert) {
        try (PreparedStatement statement = transaction.prepareStatement("""
            insert into evidence_fact_edge_v2 (
                id, projection_id, case_id, evidence_id, source_unit_id,
                observation_slot, fact_id, relation, reason
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (projection_id, observation_slot, fact_id) do nothing
            """)) {
          statement.setString(1, "EFEDGE_" + edgeHash.substring(0, 32).toUpperCase());
          statement.setString(2, projectionId);
          statement.setString(3, graph.caseId());
          statement.setString(4, edge.evidenceId());
          statement.setString(5, edge.sourceUnitId());
          statement.setString(6, edge.observationSlot());
          statement.setString(7, edge.factId());
          statement.setString(8, edge.relation());
          statement.setString(9, edge.reason());
          statement.executeUpdate();
        }
      }
      int exactEdge;
      try (PreparedStatement statement = transaction.prepareStatement("""
          select count(*) from evidence_fact_edge_v2
           where projection_id = ? and case_id = ? and evidence_id = ?
             and source_unit_id = ? and observation_slot = ? and fact_id = ?
             and relation = ? and reason = ?
          """)) {
        statement.setString(1, projectionId);
        statement.setString(2, graph.caseId());
        statement.setString(3, edge.evidenceId());
        statement.setString(4, edge.sourceUnitId());
        statement.setString(5, edge.observationSlot());
        statement.setString(6, edge.factId());
        statement.setString(7, edge.relation());
        statement.setString(8, edge.reason());
        try (ResultSet row = statement.executeQuery()) {
          require(row.next(), "Evidence fact edge verification returned no row");
          exactEdge = row.getInt(1);
        }
      }
      require(exactEdge == 1, "Evidence fact edge replay differs");
    }
    try (PreparedStatement statement = transaction.prepareStatement(
        "select count(*) from evidence_fact_edge_v2 where projection_id = ?")) {
      statement.setString(1, projectionId);
      try (ResultSet row = statement.executeQuery()) {
        require(row.next() && row.getInt(1) == edges.size(),
            "Evidence fact edge cardinality differs");
      }
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
      TargetEvidenceFinalizationRequest request, Existing message, Command command) {
    var proposal = request.proposal();
    String hashSchema = "EVIDENCE_OPENING".equals(command.commandType())
        ? "target-e2e-evidence-opening-formal-commit.v1"
        : "target-e2e-evidence-formal-commit.v3";
    return ContractJson.sha256Hex(
        objectMapper.valueToTree(
            List.of(
                hashSchema,
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
                command.sequence(),
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

  private static String expectedCommandType(TargetEvidenceFinalizationRequest request) {
    var event = request.material().material().evidenceAgentTurnCommand()
        .contextEnvelope().currentEvent();
    if ("ROOM_OPENING".equals(event.eventType())) {
      require(event.messageType().name().equals("AGENT_MESSAGE"),
          "Evidence opening command event drifted");
      require(event.attachmentRefs().isEmpty(),
          "Evidence opening command attachments drifted");
      return "EVIDENCE_OPENING";
    }
    require("PARTY_MESSAGE".equals(event.eventType()),
        "Evidence submission command event drifted");
    require(event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE"),
        "Evidence submission command message drifted");
    require(!event.attachmentRefs().isEmpty(),
        "Evidence submission command attachments drifted");
    return "EVIDENCE_SUBMIT";
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
      long fencingToken,
      long lastCommandSequence) {}

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

  private record FrameAuthorityRow(
      String commandId,
      int frameSequence,
      String frameType,
      String privateHeader,
      String publicHeader,
      String publicText,
      String headerSha256,
      String publicTextSha256,
      String frameSha256) {}

  private record FactEdge(
      String evidenceId,
      String sourceUnitId,
      String observationSlot,
      String factId,
      String relation,
      String reason) {}
}
