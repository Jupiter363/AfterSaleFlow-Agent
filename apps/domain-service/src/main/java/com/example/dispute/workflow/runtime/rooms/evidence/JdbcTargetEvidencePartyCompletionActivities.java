package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.activity.Activity;
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

/** Atomically binds a business-owned Evidence completion to fenced command terminal state. */
public final class JdbcTargetEvidencePartyCompletionActivities
    implements TargetEvidencePartyCompletionActivities {
  public static final String COMPLETION_INVALID = "TARGET_EVIDENCE_PARTY_COMPLETION_INVALID";
  static final String RESULT_URI_PREFIX = "urn:target-evidence-completion:";
  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

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
      var activityInfo = Activity.getExecutionContext().getInfo();
      WorkflowIdentity workflowIdentity =
          requireCaseWorkflowIdentity(
              request, activityInfo.getWorkflowId(), activityInfo.getRunId());
      Command command = lockCommand(connection, request);
      CompletionAuthority completionAuthority = lockCompletionMaterial(connection, request);
      String role = request.command().actorRef().actorRole().name();
      CompletionIntent completion = lockCompletionIntent(connection, request, role);
      lockParticipant(connection, request.command().caseId(), request.command().actorRef().actorId(), role);
      int targetDossierVersion = dossierFreezer.targetVersion(request.start().caseId());
      if (completion.dossierVersion() != targetDossierVersion) {
        throw new IllegalStateException("target Evidence completion dossier version drifted");
      }
      String completionId = completion.completionId();
      String receiptHash = receiptHash(request, completionId);
      boolean storedReplay = "APPLIED".equals(command.status());
      if (storedReplay) {
        requireAppliedResult(command, completionId, receiptHash);
      }
      Epoch epoch = lockEpoch(connection, request, workflowIdentity);
      if (!completionAuthority.material().activationId().equals(epoch.activationId())) {
        throw new IllegalStateException("target Evidence completion material activation drifted");
      }
      requireCoordinates(request, epoch, storedReplay);
      lockProjection(connection, request, storedReplay);
      if ("APPLIED".equals(command.status())) {
        completeActivationCommand(connection, completionAuthority, receiptHash, true);
        return result(request, completionId, receiptHash);
      }
      if (!"ORCHESTRATION_ACCEPTED".equals(command.status())) {
        throw new IllegalStateException("target Evidence party completion authority drifted");
      }
      long processRevision = Math.incrementExact(request.expectedProcessRevision());
      long roomRevision = Math.incrementExact(request.expectedRoomRevision());
      updateEpoch(connection, epoch.id(), processRevision, roomRevision);
      updateProjection(connection, request, processRevision);
      markApplied(connection, request, command.id(), completionId, receiptHash);
      completeActivationCommand(connection, completionAuthority, receiptHash, false);
      return result(request, completionId, receiptHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence party completion failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  static void requireRequest(Request request) {
    if (request.command().roomType() != RoomType.EVIDENCE
        || request.command().commandType() != CommandType.PARTY_EVIDENCE_COMPLETE
        || request.command().roomEpoch() != request.start().roomEpoch()
        || request.command().expectedProcessRevision() != request.expectedProcessRevision()
        || !request.start().caseId().equals(request.command().caseId())
        || !request.start().tenantSurrogate().equals(request.command().tenantSurrogate())
        || !request.start().tenantSurrogate().equals(request.participants().tenantSurrogate())
        || !request.start().caseId().equals(request.participants().caseId())
        || request.start().roomEpoch() != request.participants().roomEpoch()
        || request.start().fencingToken() != request.participants().fencingToken()
        || !request.start().initiatorParticipantId().equals(request.participants().initiatorParticipantId())
        || !request.start().respondentParticipantId().equals(request.participants().respondentParticipantId())
        || (request.command().actorRef().actorRole() != ActorRole.USER
            && request.command().actorRef().actorRole() != ActorRole.MERCHANT)
        || (!request.command().actorRef().actorId().equals(request.participants().initiatorParticipantId())
            && !request.command().actorRef().actorId().equals(request.participants().respondentParticipantId()))) {
      throw new IllegalArgumentException("target Evidence party completion request is inconsistent");
    }
  }

  static WorkflowIdentity requireCaseWorkflowIdentity(
      Request request, String actualWorkflowId, String actualWorkflowRunId) {
    String canonicalWorkflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId(
            request.start().tenantSurrogate(), request.start().caseId());
    if (!canonicalWorkflowId.equals(actualWorkflowId)
        || actualWorkflowRunId == null
        || actualWorkflowRunId.isBlank()) {
      throw new IllegalStateException(
          "target Evidence party completion caller is not the canonical case workflow");
    }
    return new WorkflowIdentity(actualWorkflowId, actualWorkflowRunId);
  }

  private static Command lockCommand(Connection c, Request r) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select id, command_status, result_uri, result_sha256 from case_command
         where tenant_surrogate = ? and case_id = ? and command_id = ?
           and case_command_sequence = ? and command_type = 'PARTY_EVIDENCE_COMPLETE'
           and room_type = 'EVIDENCE' and room_epoch = ? and actor_id = ? and actor_role = ?
           and actor_scopes_json = ?::jsonb
           and payload_schema_version = ? and payload_uri = ? and payload_sha256 = ?
           and payload_size_bytes = ? and expected_process_revision = ?
           and occurred_at = ? and deadline_at = ? and traceparent = ? and request_hash = ?
         for update
        """)) {
      s.setString(1, r.command().tenantSurrogate()); s.setString(2, r.command().caseId());
      s.setString(3, r.command().commandId()); s.setLong(4, r.command().caseCommandSequence());
      s.setLong(5, r.command().roomEpoch()); s.setString(6, r.command().actorRef().actorId());
      s.setString(7, r.command().actorRef().actorRole().name());
      s.setString(
          8,
          ContractJson.canonicalString(
              MAPPER.valueToTree(r.command().actorRef().actorScopes())));
      s.setString(9, r.command().payloadRef().schemaVersion());
      s.setString(10, r.command().payloadRef().uri());
      s.setString(11, r.command().payloadRef().sha256());
      s.setLong(12, r.command().payloadRef().sizeBytes());
      s.setLong(13, r.command().expectedProcessRevision());
      s.setTimestamp(14, java.sql.Timestamp.from(r.command().occurredAt()));
      s.setTimestamp(15, java.sql.Timestamp.from(r.command().deadlineAt()));
      s.setString(16, r.command().traceparent());
      s.setString(17, r.command().requestHash());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence case command is absent");
        Command value = new Command(row.getString(1), row.getString(2), row.getString(3), row.getString(4));
        if (row.next()) throw new IllegalStateException("target Evidence case command is ambiguous");
        return value;
      }
    }
  }

  static CompletionAuthority lockCompletionMaterial(Connection c, Request r)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select material.admission_id, material.material_canonical_json,
               material.material_sha256
          from production_runtime_evidence_completion_command_material material
          join production_runtime_command_admission admission
            on admission.admission_id = material.admission_id
           and admission.activation_id = material.activation_id
           and admission.activation_manifest_hash = material.activation_manifest_hash
           and admission.execution_lane = material.execution_lane
           and admission.isolated_domain_db_binding_hash = material.isolated_domain_db_binding_hash
           and admission.tenant_surrogate = material.tenant_surrogate
           and admission.case_id = material.case_id
           and admission.command_id = material.command_id
           and admission.command_hash = material.command_hash
           and admission.command_envelope_hash = material.command_envelope_hash
           and admission.room_epoch = material.room_epoch
           and admission.room_fencing_token = material.room_fencing_token
          join production_runtime_room_epoch_binding binding
            on binding.activation_id = material.activation_id
           and binding.activation_manifest_hash = material.activation_manifest_hash
           and binding.execution_lane = material.execution_lane
           and binding.isolated_domain_db_binding_hash = material.isolated_domain_db_binding_hash
           and binding.tenant_surrogate = material.tenant_surrogate
           and binding.case_id = material.case_id
           and binding.room_type = material.room_type
           and binding.room_epoch = material.room_epoch
           and binding.room_fencing_token = material.room_fencing_token
          join production_runtime_activation activation
            on activation.activation_id = material.activation_id
           and activation.manifest_hash = material.activation_manifest_hash
           and activation.execution_lane = material.execution_lane
           and activation.isolated_domain_db_binding_hash = material.isolated_domain_db_binding_hash
           and activation.tenant_surrogate = material.tenant_surrogate
         where material.execution_lane = 'PRODUCTION'
           and material.tenant_surrogate = ? and material.case_id = ?
           and material.command_id = ? and material.room_type = 'EVIDENCE'
           and material.room_epoch = ? and material.room_fencing_token = ?
           and material.case_command_request_hash = ?
           and material.expected_process_revision = ? and material.expected_room_revision = ?
           and material.actor_id = ? and material.actor_role = ?
           and material.actor_scopes_json = ?::jsonb
           and material.payload_schema_version = ? and material.payload_uri = ?
           and material.payload_sha256 = ? and material.payload_size_bytes = ?
           and material.deadline_at = ? and material.stored_at <= ?
           and activation.lifecycle_status in (
               'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL')
         for update of material, admission
        """)) {
      int i = 1;
      s.setString(i++, r.command().tenantSurrogate());
      s.setString(i++, r.command().caseId());
      s.setString(i++, r.command().commandId());
      s.setLong(i++, r.command().roomEpoch());
      s.setLong(i++, r.start().fencingToken());
      s.setString(i++, r.command().requestHash());
      s.setLong(i++, r.expectedProcessRevision());
      s.setLong(i++, r.expectedRoomRevision());
      s.setString(i++, r.command().actorRef().actorId());
      s.setString(i++, r.command().actorRef().actorRole().name());
      s.setString(i++, ContractJson.canonicalString(
          MAPPER.valueToTree(r.command().actorRef().actorScopes())));
      s.setString(i++, r.command().payloadRef().schemaVersion());
      s.setString(i++, r.command().payloadRef().uri());
      s.setString(i++, r.command().payloadRef().sha256());
      s.setLong(i++, r.command().payloadRef().sizeBytes());
      s.setTimestamp(i++, java.sql.Timestamp.from(r.command().deadlineAt()));
      s.setTimestamp(i, java.sql.Timestamp.from(r.command().occurredAt()));
      try (ResultSet rows = s.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException(
              "target Evidence completion admission material is absent");
        }
        String admissionId = rows.getString(1);
        String canonical = rows.getString(2);
        String materialHash = rows.getString(3);
        if (rows.next()) {
          throw new IllegalStateException(
              "target Evidence completion admission material is ambiguous");
        }
        TargetEvidenceCompletionCommandMaterial material;
        try {
          var value = MAPPER.readTree(canonical);
          if (!canonical.equals(ContractJson.canonicalString(value))
              || !materialHash.equals(ContractJson.sha256Hex(value))) {
            throw new IllegalStateException(
                "target Evidence completion material self-hash drifted");
          }
          material = MAPPER.treeToValue(value, TargetEvidenceCompletionCommandMaterial.class);
        } catch (IllegalStateException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new IllegalStateException(
              "target Evidence completion material is malformed", failure);
        }
        requireCompletionMaterial(r, material);
        return new CompletionAuthority(admissionId, materialHash, material);
      }
    }
  }

  static void requireCompletionMaterial(
      Request request, TargetEvidenceCompletionCommandMaterial material) {
    String traceparent = request.command().traceparent();
    if (!TargetEvidenceCompletionCommandMaterial.TARGET_LANE.equals(material.executionLane())
        || !request.start().tenantSurrogate().equals(material.tenantSurrogate())
        || !request.start().caseId().equals(material.caseId())
        || !request.command().commandId().equals(material.commandId())
        || material.commandType() != CommandType.PARTY_EVIDENCE_COMPLETE
        || material.roomType() != RoomType.EVIDENCE
        || material.roomEpoch() != request.start().roomEpoch()
        || material.roomFencingToken() != request.start().fencingToken()
        || material.expectedProcessRevision() != request.expectedProcessRevision()
        || material.expectedRoomRevision() != request.expectedRoomRevision()
        || !request.command().actorRef().equals(material.actorRef())
        || !request.command().payloadRef().equals(material.payloadRef())
        || !request.command().deadlineAt().equals(material.deadlineAt())
        || !request.command().requestHash().equals(material.caseCommandRequestHash())
        || traceparent == null
        || !traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
        || !traceparent.substring(3, 35).equals(material.traceId())) {
      throw new IllegalStateException(
          "target Evidence completion material differs from the case command");
    }
  }

  private static CompletionIntent lockCompletionIntent(Connection c, Request r, String role)
      throws SQLException {
    if (!"production-runtime-evidence-completion.v1".equals(r.command().payloadRef().schemaVersion())
        || !r.command().payloadRef().uri().startsWith("urn:production-runtime:timeline-event:")) {
      throw new IllegalStateException(
          "target Evidence party completion requires a canonical intent payload");
    }
    String eventId =
        r.command()
            .payloadRef()
            .uri()
            .substring("urn:production-runtime:timeline-event:".length());
    if (eventId.isBlank()) {
      throw new IllegalStateException(
          "target Evidence party completion intent event is absent");
    }
    try (PreparedStatement s = c.prepareStatement("""
        select completion.id, completion.dossier_version, completion.participant_role,
               completion.participant_id, completion.completion_status,
               completion.idempotency_key, completion.created_by,
               event.event_json::text, event.event_key, event.created_by
          from case_timeline_event event
          join evidence_party_completion completion
            on completion.case_id = event.case_id
           and completion.id = event.event_json ->> 'completion_id'
         where event.id = ? and event.case_id = ? and event.room_id = ?
           and event.event_type = 'EVIDENCE_PARTY_COMPLETION_INTENT'
           and event.event_key = 'target-evidence-completion:' || completion.id
         for update of completion, event
        """)) {
      s.setString(1, eventId);
      s.setString(2, r.start().caseId());
      s.setString(3, r.start().roomId());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException(
              "target Evidence party completion intent is absent");
        }
        CompletionIntent intent =
            new CompletionIntent(
                row.getString(1),
                row.getInt(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getString(10));
        if (row.next()) {
          throw new IllegalStateException(
              "target Evidence party completion intent is ambiguous");
        }
        requireCompletionIntent(r, role, eventId, intent);
        return intent;
      }
    }
  }

  static void requireCompletionIntent(
      Request request, String role, String eventId, CompletionIntent intent) {
    var canonicalIntent =
        MAPPER.valueToTree(
            new java.util.TreeMap<>(
                java.util.Map.of(
                    "completion_id", intent.completionId(),
                    "case_id", request.start().caseId(),
                    "dossier_version", intent.dossierVersion(),
                    "participant_id", request.command().actorRef().actorId(),
                    "participant_role", role,
                    "room_epoch", request.start().roomEpoch())));
    String canonicalJson = ContractJson.canonicalString(canonicalIntent);
    byte[] canonicalBytes = ContractJson.canonicalize(canonicalIntent);
    if (!request.command().commandId().equals("evidence-complete:" + intent.completionId())
        || intent.dossierVersion() < 1
        || !role.equals(intent.participantRole())
        || !request.command().actorRef().actorId().equals(intent.participantId())
        || !"COMPLETED".equals(intent.completionStatus())
        || intent.idempotencyKey() == null
        || intent.idempotencyKey().isBlank()
        || !intent.participantId().equals(intent.completionCreatedBy())
        || !canonicalJson.equals(ContractJson.canonicalString(parseJson(intent.eventJson())))
        || !("target-evidence-completion:" + intent.completionId()).equals(intent.eventKey())
        || !intent.participantId().equals(intent.eventCreatedBy())
        || !request.command().payloadRef().uri().equals("urn:production-runtime:timeline-event:" + eventId)
        || !request.command().payloadRef().sha256().equals(ContractJson.sha256Hex(canonicalIntent))
        || request.command().payloadRef().sizeBytes() != canonicalBytes.length) {
      throw new IllegalStateException(
          "target Evidence party completion intent or durable completion drifted");
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode parseJson(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "target Evidence party completion intent is not canonical JSON", failure);
    }
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

  static Epoch lockEpoch(Connection c, Request r, WorkflowIdentity workflowIdentity)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select epoch.id, epoch.lifecycle_status, epoch.process_revision, epoch.room_revision,
               binding.activation_id, activation.lifecycle_status,
               (activation.lifecycle_status = 'DRAIN_ONLY'
                 or (activation.lifecycle_status = 'ACTIVE'
                     and activation.expires_at > clock_timestamp())) as accepts_new_write
          from case_room_epoch epoch
          join production_runtime_room_epoch_binding binding
            on binding.epoch_id = epoch.id
           and binding.tenant_surrogate = epoch.tenant_surrogate
           and binding.case_id = epoch.case_id
           and binding.room_type = epoch.room_type
           and binding.room_epoch = epoch.room_epoch
           and binding.room_fencing_token = epoch.fencing_token
          join production_runtime_activation activation
            on activation.activation_id = binding.activation_id
           and activation.manifest_hash = binding.activation_manifest_hash
           and activation.execution_lane = binding.execution_lane
           and activation.isolated_domain_db_binding_hash = binding.isolated_domain_db_binding_hash
           and activation.tenant_surrogate = binding.tenant_surrogate
         where epoch.tenant_surrogate = ? and epoch.case_id = ? and epoch.room_id = ?
           and epoch.room_type = 'EVIDENCE' and epoch.room_epoch = ? and epoch.fencing_token = ?
           and epoch.lifecycle_status = 'ACTIVE' and epoch.provisioning_status = 'READY'
           and epoch.writer_mode = 'TEMPORAL'
           and epoch.temporal_workflow_id = ?
           and coalesce(btrim(epoch.temporal_run_id), '') <> ''
           and epoch.room_temporal_workflow_id = ?
           and coalesce(btrim(epoch.room_temporal_run_id), '') <> ''
           and epoch.room_workflow_build_id = ?
           and binding.execution_lane = 'PRODUCTION'
           and activation.execution_lane = 'PRODUCTION'
           and activation.lifecycle_status in (
               'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL')
         for update of epoch
        """)) {
      s.setString(1, r.start().tenantSurrogate()); s.setString(2, r.start().caseId());
      s.setString(3, r.start().roomId()); s.setLong(4, r.start().roomEpoch());
      s.setLong(5, r.start().fencingToken()); s.setString(6, workflowIdentity.workflowId());
      s.setString(
          7,
          CaseProcessWorkflowProtocol.roomWorkflowId(
              r.start().caseId(), RoomType.EVIDENCE, r.start().roomEpoch()));
      s.setString(8, r.start().workflowBuildId());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("target Evidence epoch is absent");
        Epoch value =
            new Epoch(
                row.getString(1),
                row.getString(2),
                row.getLong(3),
                row.getLong(4),
                row.getString(5),
                row.getString(6),
                row.getBoolean(7));
        if (row.next()) throw new IllegalStateException("target Evidence epoch is ambiguous");
        return value;
      }
    }
  }

  static void requireCoordinates(Request request, Epoch epoch, boolean storedReplay) {
    long expectedProcess =
        storedReplay
            ? Math.incrementExact(request.expectedProcessRevision())
            : request.expectedProcessRevision();
    long expectedRoom =
        storedReplay
            ? Math.incrementExact(request.expectedRoomRevision())
            : request.expectedRoomRevision();
    if (!"ACTIVE".equals(epoch.lifecycleStatus())
        || epoch.activationId() == null
        || epoch.activationId().isBlank()
        || !activationLifecycleAllows(storedReplay, epoch.activationLifecycleStatus())
        || (!storedReplay && !epoch.activationAcceptsNewWrite())
        || epoch.processRevision() != expectedProcess
        || epoch.roomRevision() != expectedRoom) {
      throw new IllegalStateException("target Evidence party completion authority drifted");
    }
  }

  static boolean activationLifecycleAllows(boolean storedReplay, String lifecycle) {
    if ("ACTIVE".equals(lifecycle) || "DRAIN_ONLY".equals(lifecycle)) return true;
    return storedReplay
        && ("DRAINED".equals(lifecycle) || "REVOKED_TERMINAL".equals(lifecycle));
  }

  static void lockProjection(Connection c, Request request, boolean storedReplay)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        select process_revision, last_command_sequence from case_process_projection
         where tenant_surrogate = ? and case_id = ? and current_room = 'EVIDENCE'
           and room_phase = 'OPEN' and writer_mode = 'TEMPORAL'
           and writer_activation_status = 'READY' and room_epoch = ? and fencing_token = ?
         for update
        """)) {
      s.setString(1, request.start().tenantSurrogate()); s.setString(2, request.start().caseId());
      s.setLong(3, request.start().roomEpoch()); s.setLong(4, request.start().fencingToken());
      long expectedRevision =
          storedReplay
              ? Math.incrementExact(request.expectedProcessRevision())
              : request.expectedProcessRevision();
      long expectedCommandSequence =
          storedReplay
              ? request.command().caseCommandSequence()
              : Math.decrementExact(request.command().caseCommandSequence());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()
            || row.getLong(1) != expectedRevision
            || row.getLong(2) != expectedCommandSequence
            || row.next()) {
          throw new IllegalStateException("target Evidence process projection drifted");
        }
      }
    }
  }

  private static void updateEpoch(Connection c, String id, long process, long room) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_room_epoch set process_revision = ?, room_revision = ?, updated_at = now(), version = version + 1
         where id = ? and lifecycle_status = 'ACTIVE' and process_revision = ? and room_revision = ?
        """)) { s.setLong(1, process); s.setLong(2, room); s.setString(3, id);
      s.setLong(4, process - 1); s.setLong(5, room - 1);
      if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence epoch update failed"); }
  }

  static void updateProjection(Connection c, Request request, long process) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_process_projection
           set process_revision = ?, last_command_sequence = ?,
               updated_at = now(), version = version + 1
         where tenant_surrogate = ? and case_id = ? and current_room = 'EVIDENCE'
           and room_phase = 'OPEN' and writer_mode = 'TEMPORAL'
           and writer_activation_status = 'READY' and room_epoch = ? and fencing_token = ?
           and process_revision = ? and last_command_sequence = ?
        """)) { s.setLong(1, process);
      s.setLong(2, request.command().caseCommandSequence());
      s.setString(3, request.start().tenantSurrogate());
      s.setString(4, request.start().caseId()); s.setLong(5, request.start().roomEpoch());
      s.setLong(6, request.start().fencingToken()); s.setLong(7, request.expectedProcessRevision());
      s.setLong(8, Math.decrementExact(request.command().caseCommandSequence()));
      if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence projection update failed"); }
  }

  private static void markApplied(
      Connection c, Request request, String id, String receiptId, String receiptHash) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("""
        update case_command set command_status = 'APPLIED', status_reason_code = null, result_uri = ?, result_sha256 = ?,
          applied_at = now(), updated_at = now(), version = version + 1
         where id = ? and tenant_surrogate = ? and case_id = ? and command_id = ?
           and case_command_sequence = ? and command_type = 'PARTY_EVIDENCE_COMPLETE'
           and room_type = 'EVIDENCE' and room_epoch = ? and actor_id = ? and actor_role = ?
           and request_hash = ? and expected_process_revision = ?
           and command_status = 'ORCHESTRATION_ACCEPTED'
        """)) { s.setString(1, RESULT_URI_PREFIX + receiptId); s.setString(2, receiptHash); s.setString(3, id);
      s.setString(4, request.command().tenantSurrogate()); s.setString(5, request.command().caseId());
      s.setString(6, request.command().commandId()); s.setLong(7, request.command().caseCommandSequence());
      s.setLong(8, request.command().roomEpoch()); s.setString(9, request.command().actorRef().actorId());
      s.setString(10, request.command().actorRef().actorRole().name());
      s.setString(11, request.command().requestHash()); s.setLong(12, request.expectedProcessRevision());
      if (s.executeUpdate() != 1) throw new IllegalStateException("target Evidence command completion failed"); }
  }

  private static void completeActivationCommand(
      Connection connection,
      CompletionAuthority authority,
      String completionHash,
      boolean storedReplay)
      throws SQLException {
    var material = authority.material();
    try (PreparedStatement select = connection.prepareStatement("""
        select activation_id, command_id, command_hash, command_envelope_hash,
               completion_hash
          from production_runtime_command_completion where admission_id = ? for key share
        """)) {
      select.setString(1, authority.admissionId());
      try (ResultSet rows = select.executeQuery()) {
        if (rows.next()) {
          if (!material.activationId().equals(rows.getString(1))
              || !material.commandId().equals(rows.getString(2))
              || !material.commandHash().equals(rows.getString(3))
              || !material.commandEnvelopeHash().equals(rows.getString(4))
              || !completionHash.equals(rows.getString(5))
              || rows.next()) {
            throw new IllegalStateException(
                "target Evidence completion activation receipt drifted");
          }
          return;
        }
      }
    }
    if (storedReplay) {
      throw new IllegalStateException(
          "target Evidence completion activation receipt is absent");
    }
    try (PreparedStatement insert = connection.prepareStatement("""
        insert into production_runtime_command_completion (
          admission_id, activation_id, command_id, command_hash,
          command_envelope_hash, completion_hash)
        values (?, ?, ?, ?, ?, ?)
        """)) {
      insert.setString(1, authority.admissionId());
      insert.setString(2, material.activationId());
      insert.setString(3, material.commandId());
      insert.setString(4, material.commandHash());
      insert.setString(5, material.commandEnvelopeHash());
      insert.setString(6, completionHash);
      if (insert.executeUpdate() != 1) {
        throw new IllegalStateException(
            "target Evidence completion activation receipt insert failed");
      }
    }
  }

  private static void requireAppliedResult(
      Command command, String completionId, String receiptHash) {
    if (!(RESULT_URI_PREFIX + completionId).equals(command.resultUri())
        || !receiptHash.equals(command.resultHash())) {
      throw new IllegalStateException("target Evidence command replay conflicts with its completion");
    }
  }

  private static String receiptHash(Request r, String id) { return ContractJson.sha256Hex(MAPPER.valueToTree(List.of("production-runtime-evidence-party-receipt.v1", id, r.command().requestHash(), r.expectedProcessRevision(), r.expectedRoomRevision()))); }
  private static Result result(Request r, String id, String hash) { return new Result(id, new TargetRoomProgressReceipt(RoomType.EVIDENCE, r.start().roomEpoch(), r.start().fencingToken(), Math.incrementExact(r.expectedProcessRevision()), Math.incrementExact(r.expectedRoomRevision()), id, hash)); }
  record WorkflowIdentity(String workflowId, String workflowRunId) {}
  record CompletionIntent(
      String completionId,
      int dossierVersion,
      String participantRole,
      String participantId,
      String completionStatus,
      String idempotencyKey,
      String completionCreatedBy,
      String eventJson,
      String eventKey,
      String eventCreatedBy) {}
  private record Command(String id, String status, String resultUri, String resultHash) {}
  record CompletionAuthority(
      String admissionId,
      String materialHash,
      TargetEvidenceCompletionCommandMaterial material) {}
  record Epoch(
      String id,
      String lifecycleStatus,
      long processRevision,
      long roomRevision,
      String activationId,
      String activationLifecycleStatus,
      boolean activationAcceptsNewWrite) {}
}
