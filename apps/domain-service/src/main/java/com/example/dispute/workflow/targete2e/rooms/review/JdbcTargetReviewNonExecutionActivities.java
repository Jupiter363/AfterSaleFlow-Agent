package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.config.DisputeProperties;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.ReviewDecision;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.CompletionRequest;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.CompletionResult;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.DispositionReceipt;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.EvidenceTransition;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.LoadRequest;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic Review disposition writer for the three decisions which authorize no execution. */
public final class JdbcTargetReviewNonExecutionActivities
    implements TargetReviewNonExecutionActivities {
  public static final String DISPOSITION_INVALID = "TARGET_REVIEW_NON_EXECUTION_INVALID";
  static final String RESULT_URI_PREFIX = "urn:target-e2e:review-non-execution:";
  private static final String WRITER = "target-e2e-review-non-execution";

  static final String AUTHORITY_SQL = """
      select command.id, command.command_status, command.result_uri, command.result_sha256,
             admission.admission_id, admission.activation_id, admission.command_hash,
             admission.command_envelope_hash, material.material_canonical_json,
             material.material_sha256, decision_event.event_json::text,
             approval.decision_type, activation.lifecycle_status as activation_lifecycle,
             (activation.lifecycle_status = 'DRAIN_ONLY'
               or (activation.lifecycle_status = 'ACTIVE'
                   and activation.expires_at > clock_timestamp())) as accepts_new_write,
             epoch.lifecycle_status as epoch_lifecycle, epoch.process_revision,
             epoch.room_revision, epoch.room_id
        from case_command command
        join target_e2e_command_admission admission
          on admission.tenant_surrogate = command.tenant_surrogate
         and admission.case_id = command.case_id
         and admission.command_id = command.command_id
         and admission.room_epoch = command.room_epoch
        join target_e2e_review_command_material material
          on material.admission_id = admission.admission_id
         and material.activation_id = admission.activation_id
         and material.activation_manifest_hash = admission.activation_manifest_hash
         and material.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
         and material.tenant_surrogate = admission.tenant_surrogate
         and material.case_id = admission.case_id
         and material.command_id = admission.command_id
         and material.command_hash = admission.command_hash
         and material.command_envelope_hash = admission.command_envelope_hash
         and material.room_epoch = admission.room_epoch
         and material.room_fencing_token = admission.room_fencing_token
        join case_timeline_event decision_event
          on decision_event.case_id = command.case_id
         and decision_event.event_json ->> 'command_id' = command.command_id
         and decision_event.event_key =
             ('target-review-decision:' || (decision_event.event_json ->> 'approval_record_id'))
        join human_review_record approval
          on approval.id = decision_event.event_json ->> 'approval_record_id'
         and approval.case_id = command.case_id
         and approval.review_task_id = decision_event.event_json ->> 'review_task_id'
         and approval.review_packet_id = decision_event.event_json ->> 'packet_id'
         and approval.action_snapshot_hash =
             decision_event.event_json ->> 'approved_action_snapshot_hash'
         and approval.decision_type::text = decision_event.event_json ->> 'decision'
         and approval.ai_decision_action =
             decision_event.event_json ->> 'ai_decision_action'
         and approval.reviewer_decision_action =
             decision_event.event_json ->> 'reviewer_decision_action'
        join target_e2e_activation activation
          on activation.activation_id = admission.activation_id
         and activation.manifest_hash = admission.activation_manifest_hash
         and activation.execution_lane = admission.execution_lane
         and activation.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
         and activation.tenant_surrogate = admission.tenant_surrogate
        join target_e2e_room_epoch_binding binding
          on binding.activation_id = admission.activation_id
         and binding.activation_manifest_hash = admission.activation_manifest_hash
         and binding.execution_lane = admission.execution_lane
         and binding.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
         and binding.tenant_surrogate = admission.tenant_surrogate
         and binding.case_id = admission.case_id
         and binding.room_type = 'REVIEW'
         and binding.room_epoch = admission.room_epoch
         and binding.room_fencing_token = admission.room_fencing_token
        join case_room_epoch epoch
          on epoch.id = binding.epoch_id
         and epoch.tenant_surrogate = binding.tenant_surrogate
         and epoch.case_id = binding.case_id
         and epoch.room_type = binding.room_type
         and epoch.room_epoch = binding.room_epoch
         and epoch.fencing_token = binding.room_fencing_token
       where command.tenant_surrogate = ? and command.case_id = ? and command.command_id = ?
         and command.case_command_sequence = ? and command.command_type = 'REVIEW_DECISION'
         and command.room_type = 'REVIEW' and command.room_epoch = ?
         and command.expected_process_revision = ? and command.request_hash = ?
         and command.payload_schema_version = ? and command.payload_uri = ?
         and command.payload_sha256 = ? and command.payload_size_bytes = ?
         and command.actor_id = ? and command.actor_role = ?
         and command.actor_scopes_json = cast(? as jsonb)
         and command.occurred_at = ? and command.deadline_at = ? and command.traceparent = ?
         and admission.room_fencing_token = ?
         and admission.execution_lane = 'TARGET_E2E_CANDIDATE'
         and approval.id = ?
         and approval.decision_type = ?
         and approval.decision_type = 'ESCALATE_MANUAL'
         and approval.reviewer_decision_action = 'ESCALATE_MANUAL'
         and activation.execution_lane = 'TARGET_E2E_CANDIDATE'
         and activation.lifecycle_status in (
             'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL')
         and epoch.writer_mode = 'TEMPORAL'
         and epoch.lifecycle_status in ('ACTIVE', 'TERMINAL')
         and command.payload_schema_version = 'target-e2e-review-human-decision-event.v1'
         and decision_event.event_json ->> 'schema_version' = command.payload_schema_version
         and decision_event.event_json ->> 'case_id' = command.case_id
         and decision_event.event_json ->> 'room_epoch' = command.room_epoch::text
         and decision_event.event_json ->> 'fencing_token' = admission.room_fencing_token::text
         and decision_event.event_json ->> 'case_process_revision' =
             command.expected_process_revision::text
         and material.material_schema_version = 'target-e2e-review-command-material.v1'
         and material.material_canonical_json::jsonb #>> '{expected_process_revision}' =
             command.expected_process_revision::text
         and material.material_canonical_json::jsonb #>> '{room_fencing_token}' =
             admission.room_fencing_token::text
         and material.material_canonical_json::jsonb #>> '{request,command,tenant_surrogate}' =
             command.tenant_surrogate
         and material.material_canonical_json::jsonb #>> '{request,command,case_id}' = command.case_id
         and material.material_canonical_json::jsonb #>> '{request,command,command_id}' =
             command.command_id
         and material.material_canonical_json::jsonb #>> '{request,command,room_type}' =
             command.room_type
         and material.material_canonical_json::jsonb #>> '{request,command,room_epoch}' =
             command.room_epoch::text
         and material.material_canonical_json::jsonb #>> '{request,command,event_ref,uri}' =
             command.payload_uri
          and material.material_canonical_json::jsonb #>> '{request,command,event_ref,sha256}' =
              command.payload_sha256
       for update of command, activation, epoch
      """;

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final RoomEpochAllocator roomEpochAllocator;
  private final TargetE2EActivationLedger activationLedger;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final java.time.Duration evidenceWindow;

  public JdbcTargetReviewNonExecutionActivities(
      DataSource dataSource,
      TransactionTemplate transaction,
      RoomEpochAllocator roomEpochAllocator,
      TargetE2EActivationLedger activationLedger,
      ObjectMapper mapper,
      Clock clock,
      DisputeProperties disputeProperties) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.roomEpochAllocator = Objects.requireNonNull(roomEpochAllocator, "roomEpochAllocator");
    this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.evidenceWindow = Objects.requireNonNull(disputeProperties, "disputeProperties").evidenceWindow();
  }

  @Override
  public CompletionResult complete(CompletionRequest request) {
    try {
      var info = Activity.getExecutionContext().getInfo();
      return complete(
          Objects.requireNonNull(request, "request"), info.getWorkflowId(), info.getRunId());
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), DISPOSITION_INVALID);
    }
  }

  @Override
  public CompletionResult loadApplied(LoadRequest request) {
    try {
      var info = Activity.getExecutionContext().getInfo();
      return loadApplied(
          Objects.requireNonNull(request, "request"), info.getWorkflowId(), info.getRunId());
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), DISPOSITION_INVALID);
    }
  }

  CompletionResult complete(
      CompletionRequest request, String actualWorkflowId, String actualWorkflowRunId) {
    requireRequest(request.start(), request.decision(), request.command());
    requireCaseWorkflow(request.command(), actualWorkflowId, actualWorkflowRunId);
    return Objects.requireNonNull(
        transaction.execute(
            ignored ->
                completeInTransaction(request, actualWorkflowId, actualWorkflowRunId)),
        "target Review non-execution transaction returned null");
  }

  CompletionResult loadApplied(
      LoadRequest request, String actualWorkflowId, String actualWorkflowRunId) {
    requireRequest(request.start(), request.decision(), request.command());
    requireCaseWorkflow(request.command(), actualWorkflowId, actualWorkflowRunId);
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> loadInTransaction(request, actualWorkflowId)),
        "target Review non-execution lookup returned null");
  }

  private CompletionResult completeInTransaction(
      CompletionRequest request, String workflowId, String workflowRunId) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      lockBusinessCase(connection, request.command().caseId());
      Authority authority = lockAuthority(connection, request.command(), request.decision(), request.start());
      Stored stored = readStored(connection, authority, true);
      boolean replay = "APPLIED".equals(authority.commandStatus());
      if (replay) {
        CompletionResult result =
            requireStoredReplay(
                connection,
                request.start(),
                request.decision(),
                request.command(),
                authority,
                stored);
        requireReplayCoordinates(request, authority, result);
        return result;
      }
      if (stored != null
          || !"ORCHESTRATION_ACCEPTED".equals(authority.commandStatus())
          || !"ACTIVE".equals(authority.epochLifecycle())
          || !authority.acceptsNewWrite()
          || authority.processRevision() != request.expectedProcessRevision()
          || authority.roomRevision() != request.expectedRoomRevision()) {
        throw new IllegalStateException("target Review non-execution authority drifted");
      }

      Instant committedAt = clock.instant();
      closeReviewRoom(connection, request.command().caseId(), committedAt);
      TransitionState transition = transition(connection, request, authority, committedAt);
      String receiptId = receiptId(request.command(), request.decision());
      DispositionReceipt receipt =
          new DispositionReceipt(
              DispositionReceipt.SCHEMA_VERSION,
              receiptId,
              request.command().tenantSurrogate(),
              request.command().caseId(),
              workflowId,
              workflowRunId,
              request.command().commandId(),
              request.decision().decisionRecordRef(),
              request.decision().decisionRecordHash(),
              request.decision().decision(),
              request.start().epoch(),
              request.start().fence(),
              transition.terminalProcessRevision(),
              transition.terminalRoomRevision(),
              transition.evidenceTransition(),
              committedAt);
      String canonical = canonical(receipt);
      String receiptHash = canonicalHash(canonical);
      insertReceipt(connection, authority, receipt, canonical, receiptHash, request);
      markApplied(connection, authority, receiptHash, committedAt);
      activationLedger.completeCommand(
          connection,
          new TargetE2EActivationLedger.CommandCompletion(
              authority.admissionId(),
              authority.activationId(),
              request.command().commandId(),
              authority.commandHash(),
              authority.commandEnvelopeHash(),
              receiptHash));
      return new CompletionResult(receipt, receiptHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Review non-execution persistence failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private CompletionResult loadInTransaction(LoadRequest request, String workflowId) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      lockBusinessCase(connection, request.command().caseId());
      Authority authority = lockAuthority(connection, request.command(), request.decision(), request.start());
      Stored stored = readStored(connection, authority, false);
      CompletionResult result =
          requireStoredReplay(
              connection,
              request.start(),
              request.decision(),
              request.command(),
              authority,
              stored);
      if (!workflowId.equals(result.receipt().caseWorkflowId())) {
        throw new IllegalStateException("target Review disposition workflow identity conflicts");
      }
      requirePersistedBranchState(connection, request, authority, result);
      return result;
    } catch (SQLException failure) {
      throw new IllegalStateException("target Review non-execution lookup failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  static void requireRequest(
      OutcomeWorkflowStart start,
      OutcomeReviewDecisionReceipt decision,
      CaseCommandRef command) {
    if (command.commandType() != CommandType.REVIEW_DECISION
        || command.roomType() != RoomType.REVIEW
        || command.roomEpoch() != start.epoch()
        || command.expectedProcessRevision() < 0
        || start.runtimeMode() != RuntimeMode.TEMPORAL
        || start.syntheticOnly()
        || decision.syntheticOnly()
        || decision.executionAuthorized()
        || !isNonExecutable(decision.decision())
        || !command.caseId().equals(start.caseId())
        || !start.workflowId().equals(decision.workflowId())
        || !start.caseId().equals(decision.caseId())
        || start.epoch() != decision.epoch()
        || start.fence() != decision.fence()
        || start.revision() != decision.sourceRevision()
        || decision.revision() != start.revision() + 1
        || !decision.receiptId().equals(decision.decisionRecordRef())
        || !decision.receiptHash().equals(decision.decisionRecordHash())
        || !start.reviewTaskId().equals(decision.reviewTaskId())
        || !start.frozenReviewPacketRef().equals(decision.frozenReviewPacketRef())
        || !start.frozenReviewPacketHash().equals(decision.frozenReviewPacketHash())
        || !start.requiredOperationSetHash().equals(decision.requiredOperationSetHash())
        || start.requiredOperationCount() != decision.requiredOperationCount()
        || !start.policyVersion().equals(decision.policyVersion())
        || !command.payloadRef().sha256().equals(decision.decisionRecordHash())) {
      throw new IllegalArgumentException("target Review non-execution request is inconsistent");
    }
  }

  private static boolean isNonExecutable(ReviewDecision decision) {
    return decision == ReviewDecision.ESCALATE_MANUAL;
  }

  private static void requireCaseWorkflow(
      CaseCommandRef command, String actualWorkflowId, String actualWorkflowRunId) {
    String expected =
        CaseProcessWorkflowProtocol.caseWorkflowId(
            command.tenantSurrogate(), command.caseId());
    if (!expected.equals(actualWorkflowId)
        || actualWorkflowRunId == null
        || actualWorkflowRunId.isBlank()) {
      throw new IllegalStateException(
          "target Review non-execution caller is not the canonical CaseProcess workflow");
    }
  }

  private Authority lockAuthority(
      Connection connection,
      CaseCommandRef command,
      OutcomeReviewDecisionReceipt decision,
      OutcomeWorkflowStart start)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(AUTHORITY_SQL)) {
      int index = 1;
      statement.setString(index++, command.tenantSurrogate());
      statement.setString(index++, command.caseId());
      statement.setString(index++, command.commandId());
      statement.setLong(index++, command.caseCommandSequence());
      statement.setLong(index++, command.roomEpoch());
      statement.setLong(index++, command.expectedProcessRevision());
      statement.setString(index++, command.requestHash());
      statement.setString(index++, command.payloadRef().schemaVersion());
      statement.setString(index++, command.payloadRef().uri());
      statement.setString(index++, command.payloadRef().sha256());
      statement.setLong(index++, command.payloadRef().sizeBytes());
      statement.setString(index++, command.actorRef().actorId());
      statement.setString(index++, command.actorRef().actorRole().name());
      statement.setString(
          index++, ContractJson.canonicalString(mapper.valueToTree(command.actorRef().actorScopes())));
      statement.setObject(index++, command.occurredAt());
      statement.setObject(index++, command.deadlineAt());
      statement.setString(index++, command.traceparent());
      statement.setLong(index++, start.fence());
      statement.setString(index++, decision.decisionRecordRef());
      statement.setString(index, decision.decision().name());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException("target Review non-execution authority is absent");
        }
        Authority authority =
            new Authority(
                rows.getString("id"),
                rows.getString("command_status"),
                rows.getString("result_uri"),
                rows.getString("result_sha256"),
                rows.getString("admission_id"),
                rows.getString("activation_id"),
                rows.getString("command_hash"),
                rows.getString("command_envelope_hash"),
                rows.getString("activation_lifecycle"),
                rows.getBoolean("accepts_new_write"),
                rows.getString("epoch_lifecycle"),
                rows.getLong("process_revision"),
                rows.getLong("room_revision"),
                rows.getString("room_id"));
        String material = rows.getString("material_canonical_json");
        String materialHash = rows.getString("material_sha256");
        String event = rows.getString("event_json");
        if (rows.next()
            || !material.equals(canonical(material))
            || !decision.decisionRecordHash().equals(canonicalHash(event))
            || !canonicalHash(material).equals(materialHash)) {
          throw new IllegalStateException("target Review non-execution durable authority conflicts");
        }
        return authority;
      }
    }
  }

  private TransitionState transition(
      Connection connection,
      CompletionRequest request,
      Authority authority,
      Instant committedAt)
      throws SQLException {
    OffsetDateTime occurredAt = OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC);
    if (request.decision().decision() != ReviewDecision.REQUEST_MORE_EVIDENCE) {
      requireManualHandoff(connection, request.command().caseId());
      String roomPhase =
          request.decision().decision() == ReviewDecision.REJECT
              ? "REJECTED"
              : "MANUAL_ESCALATED";
      RoomEpochAllocation terminal =
          roomEpochAllocator.terminate(
              new TerminateRoomEpoch(
                  request.command().caseId(),
                  RoomType.REVIEW,
                  "MANUAL_HANDOFF",
                  roomPhase,
                  occurredAt));
      if (terminal.roomType() != RoomType.REVIEW
          || terminal.roomEpoch() != request.start().epoch()
          || terminal.fencingToken() != request.start().fence()
          || terminal.processRevision() != request.expectedProcessRevision() + 1
          || terminal.roomRevision() != request.expectedRoomRevision() + 1) {
        throw new IllegalStateException("target Review terminal allocation conflicts");
      }
      return new TransitionState(
          terminal.processRevision(), terminal.roomRevision(), null);
    }

    BusinessEvidenceRoom room = lockEvidenceRoom(connection, request.command().caseId());
    Instant deadline = committedAt.plus(evidenceWindow);
    RoomEpochAllocation evidence =
        roomEpochAllocator.transition(
            new TransitionRoomEpoch(
                request.command().caseId(),
                RoomType.REVIEW,
                room.id(),
                RoomType.EVIDENCE,
                "EVIDENCE_OPEN",
                "PROVISIONING",
                OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC),
                occurredAt));
    String expectedCaseWorkflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId(
            request.command().tenantSurrogate(), request.command().caseId());
    String evidenceWorkflowId =
        CaseProcessWorkflowProtocol.roomWorkflowId(
            request.command().caseId(), RoomType.EVIDENCE, evidence.roomEpoch());
    var selection = evidence.selection();
    if (!request.command().tenantSurrogate().equals(evidence.tenantSurrogate())
        || !request.command().caseId().equals(evidence.caseId())
        || evidence.roomType() != RoomType.EVIDENCE
        || !room.id().equals(evidence.roomId())
        || evidence.processRevision() != request.expectedProcessRevision() + 1
        || evidence.roomRevision() != 0
        || evidence.fencingToken() != Math.incrementExact(request.start().fence())
        || evidence.writerMode() != WriterMode.TEMPORAL
        || evidence.lifecycleStatus() != EpochLifecycleStatus.PREPARING
        || !expectedCaseWorkflowId.equals(evidence.temporalWorkflowId())
        || evidence.temporalRunId() != null
        || selection == null
        || selection.writerMode() != WriterMode.TEMPORAL
        || !TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION.equals(
            selection.selectionSchemaVersion())
        || !TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION.equals(
            selection.processContractVersion())
        || !TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE.equals(selection.caseWorkflowType())
        || !TargetTypedRoomProtocol.EVIDENCE_WORKFLOW_TYPE.equals(selection.roomWorkflowType())
        || !TargetTypedRoomProtocol.GRAPH_KEY.equals(selection.graphKey())
        || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(selection.graphVersion())
        || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
            selection.checkpointSchemaVersion())
        || !TargetTypedRoomProtocol.STREAM_PROTOCOL.equals(selection.streamProtocol())) {
      throw new IllegalStateException("target Review Evidence allocation conflicts");
    }
    long sourceTerminalRoomRevision = sourceTerminalRoomRevision(connection, request);
    reopenEvidence(
        connection,
        request.command().caseId(),
        room.id(),
        evidenceWorkflowId,
        committedAt,
        deadline);
    return new TransitionState(
        evidence.processRevision(),
        sourceTerminalRoomRevision,
        new EvidenceTransition(
            evidence.epochId(),
            evidence.roomId(),
            evidence.roomEpoch(),
            evidence.fencingToken(),
            evidence.processRevision(),
            evidence.roomRevision(),
            evidenceWorkflowId,
            deadline));
  }

  private long sourceTerminalRoomRevision(Connection connection, CompletionRequest request)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select process_revision, room_revision from case_room_epoch
         where case_id = ? and room_type = 'REVIEW' and room_epoch = ? and fencing_token = ?
           and lifecycle_status = 'TERMINAL' and writer_activation_status = 'TERMINAL'
         for key share
        """)) {
      statement.setString(1, request.command().caseId());
      statement.setLong(2, request.start().epoch());
      statement.setLong(3, request.start().fence());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || rows.getLong(1) != request.expectedProcessRevision() + 1
            || rows.getLong(2) != request.expectedRoomRevision() + 1
            || rows.next()) {
          throw new IllegalStateException("target Review source terminal coordinates conflict");
        }
        return request.expectedRoomRevision() + 1;
      }
    }
  }

  private void reopenEvidence(
      Connection connection,
      String caseId,
      String roomId,
      String evidenceWorkflowId,
      Instant committedAt,
      Instant deadline)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement("""
        update case_room
           set room_status = 'OPEN', opened_at = ?, sealed_at = null, closed_at = null,
               updated_at = ?, updated_by = ?, version = version + 1
         where id = ? and case_id = ? and room_type = 'EVIDENCE'
        """)) {
      update.setObject(1, committedAt);
      update.setObject(2, committedAt);
      update.setString(3, WRITER);
      update.setString(4, roomId);
      update.setString(5, caseId);
      requireOne(update.executeUpdate(), "target Review Evidence room reopen was rejected");
    }
    try (PreparedStatement update = connection.prepareStatement("""
        update case_phase_clock
           set room_id = ?, clock_status = 'RUNNING', started_at = ?, deadline_at = ?,
               completed_at = null, temporal_workflow_id = ?, temporal_run_id = null,
               completion_reason = null, updated_at = ?, updated_by = ?, version = version + 1
         where case_id = ? and clock_type = 'EVIDENCE_SUBMISSION'
        """)) {
      update.setString(1, roomId);
      update.setObject(2, committedAt);
      update.setObject(3, deadline);
      update.setString(4, evidenceWorkflowId);
      update.setObject(5, committedAt);
      update.setString(6, WRITER);
      update.setString(7, caseId);
      requireOne(update.executeUpdate(), "target Review Evidence clock reset was rejected");
    }
    try (PreparedStatement update = connection.prepareStatement("""
        update fulfillment_dispute_case
           set case_status = 'EVIDENCE_OPEN', current_room = 'EVIDENCE',
               current_deadline_at = ?, updated_at = ?, updated_by = ?, version = version + 1
         where id = ? and case_status = 'WAITING_EVIDENCE' and current_room = 'EVIDENCE'
           and current_deadline_at is null
        """)) {
      update.setObject(1, deadline);
      update.setObject(2, committedAt);
      update.setString(3, WRITER);
      update.setString(4, caseId);
      requireOne(update.executeUpdate(), "target Review Evidence case reopen was rejected");
    }
  }

  private void insertReceipt(
      Connection connection,
      Authority authority,
      DispositionReceipt receipt,
      String canonical,
      String receiptHash,
      CompletionRequest request)
      throws SQLException {
    EvidenceTransition evidence = receipt.evidenceTransition();
    try (PreparedStatement insert = connection.prepareStatement("""
        insert into target_e2e_review_non_execution_completion (
          receipt_id, schema_version, tenant_surrogate, case_id,
          case_workflow_id, case_workflow_run_id, decision_type,
          decision_record_id, decision_record_hash, command_id, admission_id, activation_id,
          command_hash, command_envelope_hash, source_room_epoch, source_fencing_token,
          source_process_revision, source_room_revision, terminal_process_revision,
          terminal_room_revision, next_evidence_epoch_id, next_evidence_room_id,
          next_evidence_room_epoch, next_evidence_fencing_token, next_evidence_process_revision,
          next_evidence_room_revision, next_evidence_workflow_id, next_evidence_deadline_at,
          receipt_canonical_json, receipt_sha256, committed_at, committed_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?)
        """)) {
      int index = 1;
      insert.setString(index++, receipt.receiptId());
      insert.setString(index++, receipt.schemaVersion());
      insert.setString(index++, receipt.tenantSurrogate());
      insert.setString(index++, receipt.caseId());
      insert.setString(index++, receipt.caseWorkflowId());
      insert.setString(index++, receipt.caseWorkflowRunId());
      insert.setString(index++, receipt.decision().name());
      insert.setString(index++, receipt.decisionRecordId());
      insert.setString(index++, receipt.decisionRecordHash());
      insert.setString(index++, receipt.commandId());
      insert.setString(index++, authority.admissionId());
      insert.setString(index++, authority.activationId());
      insert.setString(index++, authority.commandHash());
      insert.setString(index++, authority.commandEnvelopeHash());
      insert.setLong(index++, receipt.sourceRoomEpoch());
      insert.setLong(index++, receipt.sourceFencingToken());
      insert.setLong(index++, request.expectedProcessRevision());
      insert.setLong(index++, request.expectedRoomRevision());
      insert.setLong(index++, receipt.terminalProcessRevision());
      insert.setLong(index++, receipt.terminalRoomRevision());
      nullableEvidence(insert, index, evidence);
      index += 8;
      insert.setString(index++, canonical);
      insert.setString(index++, receiptHash);
      insert.setObject(index++, receipt.committedAt());
      insert.setString(index, WRITER);
      requireOne(insert.executeUpdate(), "target Review disposition receipt insert was rejected");
    }
  }

  private static void nullableEvidence(
      PreparedStatement statement, int index, EvidenceTransition evidence) throws SQLException {
    if (evidence == null) {
      for (int offset = 0; offset < 8; offset++) {
        statement.setObject(index + offset, null);
      }
      return;
    }
    statement.setString(index++, evidence.epochId());
    statement.setString(index++, evidence.roomId());
    statement.setLong(index++, evidence.roomEpoch());
    statement.setLong(index++, evidence.fencingToken());
    statement.setLong(index++, evidence.processRevision());
    statement.setLong(index++, evidence.roomRevision());
    statement.setString(index++, evidence.workflowId());
    statement.setObject(index, evidence.deadlineAt());
  }

  private Stored readStored(Connection connection, Authority authority, boolean updateLock)
      throws SQLException {
    String lock = updateLock ? " for update" : " for key share";
    try (PreparedStatement statement = connection.prepareStatement("""
        select receipt_id, schema_version, tenant_surrogate, case_id,
               case_workflow_id, case_workflow_run_id, decision_type,
               decision_record_id, decision_record_hash, command_id,
               admission_id, activation_id, command_hash, command_envelope_hash,
               source_room_epoch, source_fencing_token, source_process_revision,
               source_room_revision, terminal_process_revision, terminal_room_revision,
               next_evidence_epoch_id, next_evidence_room_id, next_evidence_room_epoch,
               next_evidence_fencing_token, next_evidence_process_revision,
               next_evidence_room_revision, next_evidence_workflow_id,
               next_evidence_deadline_at, receipt_canonical_json, receipt_sha256, committed_at
          from target_e2e_review_non_execution_completion
         where admission_id = ? and activation_id = ?
        """ + lock)) {
      statement.setString(1, authority.admissionId());
      statement.setString(2, authority.activationId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        String canonical = rows.getString("receipt_canonical_json");
        DispositionReceipt receipt = readReceipt(canonical);
        requireStoredColumns(rows, receipt);
        Stored stored =
            new Stored(
                canonical,
                rows.getString("receipt_sha256"),
                rows.getString("admission_id"),
                rows.getString("activation_id"),
                rows.getString("command_hash"),
                rows.getString("command_envelope_hash"),
                receipt);
        if (rows.next()) {
          throw new IllegalStateException("target Review disposition receipt is ambiguous");
        }
        return stored;
      }
    }
  }

  private DispositionReceipt readReceipt(String canonical) {
    try {
      return mapper.readValue(canonical, DispositionReceipt.class);
    } catch (Exception failure) {
      throw new IllegalStateException("target Review disposition receipt is malformed", failure);
    }
  }

  private static void requireStoredColumns(ResultSet rows, DispositionReceipt receipt)
      throws SQLException {
    boolean commonMatches =
        receipt.receiptId().equals(rows.getString("receipt_id"))
            && receipt.schemaVersion().equals(rows.getString("schema_version"))
            && receipt.tenantSurrogate().equals(rows.getString("tenant_surrogate"))
            && receipt.caseId().equals(rows.getString("case_id"))
            && receipt.caseWorkflowId().equals(rows.getString("case_workflow_id"))
            && receipt.caseWorkflowRunId().equals(rows.getString("case_workflow_run_id"))
            && receipt.decision().name().equals(rows.getString("decision_type"))
            && receipt.decisionRecordId().equals(rows.getString("decision_record_id"))
            && receipt.decisionRecordHash().equals(rows.getString("decision_record_hash"))
            && receipt.commandId().equals(rows.getString("command_id"))
            && receipt.sourceRoomEpoch() == rows.getLong("source_room_epoch")
            && receipt.sourceFencingToken() == rows.getLong("source_fencing_token")
            && receipt.terminalProcessRevision() - 1
                == rows.getLong("source_process_revision")
            && receipt.terminalRoomRevision() - 1 == rows.getLong("source_room_revision")
            && receipt.terminalProcessRevision()
                == rows.getLong("terminal_process_revision")
            && receipt.terminalRoomRevision() == rows.getLong("terminal_room_revision")
            && sameDatabaseInstant(receipt.committedAt(), rows, "committed_at");
    if (!commonMatches || !storedEvidenceMatches(rows, receipt.evidenceTransition())) {
      throw new IllegalStateException("target Review disposition stored columns conflict");
    }
  }

  private static boolean storedEvidenceMatches(ResultSet rows, EvidenceTransition evidence)
      throws SQLException {
    List<String> columns =
        List.of(
            "next_evidence_epoch_id",
            "next_evidence_room_id",
            "next_evidence_room_epoch",
            "next_evidence_fencing_token",
            "next_evidence_process_revision",
            "next_evidence_room_revision",
            "next_evidence_workflow_id",
            "next_evidence_deadline_at");
    if (evidence == null) {
      for (String column : columns) {
        if (rows.getObject(column) != null) return false;
      }
      return true;
    }
    return evidence.epochId().equals(rows.getString("next_evidence_epoch_id"))
        && evidence.roomId().equals(rows.getString("next_evidence_room_id"))
        && evidence.roomEpoch() == rows.getLong("next_evidence_room_epoch")
        && evidence.fencingToken() == rows.getLong("next_evidence_fencing_token")
        && evidence.processRevision() == rows.getLong("next_evidence_process_revision")
        && evidence.roomRevision() == rows.getLong("next_evidence_room_revision")
        && evidence.workflowId().equals(rows.getString("next_evidence_workflow_id"))
        && sameDatabaseInstant(evidence.deadlineAt(), rows, "next_evidence_deadline_at");
  }

  private static boolean sameDatabaseInstant(Instant expected, ResultSet rows, String column)
      throws SQLException {
    Object value = rows.getObject(column);
    Instant actual;
    if (value instanceof OffsetDateTime offsetDateTime) {
      actual = offsetDateTime.toInstant();
    } else if (value instanceof java.sql.Timestamp timestamp) {
      actual = timestamp.toInstant();
    } else if (value instanceof Instant instant) {
      actual = instant;
    } else {
      return false;
    }
    return expected.truncatedTo(ChronoUnit.MICROS).equals(actual.truncatedTo(ChronoUnit.MICROS));
  }

  static boolean validEvidenceTransition(DispositionReceipt receipt) {
    EvidenceTransition evidence = receipt.evidenceTransition();
    if (receipt.decision() != ReviewDecision.REQUEST_MORE_EVIDENCE) {
      return evidence == null;
    }
    return evidence != null
        && evidence.processRevision() == receipt.terminalProcessRevision()
        && evidence.roomRevision() == 0
        && evidence.fencingToken() == Math.incrementExact(receipt.sourceFencingToken())
        && evidence.workflowId()
            .equals(
                CaseProcessWorkflowProtocol.roomWorkflowId(
                    receipt.caseId(), RoomType.EVIDENCE, evidence.roomEpoch()));
  }

  private CompletionResult requireStoredReplay(
      Connection connection,
      OutcomeWorkflowStart start,
      OutcomeReviewDecisionReceipt decision,
      CaseCommandRef command,
      Authority authority,
      Stored stored)
      throws SQLException {
    if (stored == null
        || !"APPLIED".equals(authority.commandStatus())
        || !stored.admissionId().equals(authority.admissionId())
        || !stored.activationId().equals(authority.activationId())
        || !stored.commandHash().equals(authority.commandHash())
        || !stored.commandEnvelopeHash().equals(authority.commandEnvelopeHash())
        || !stored.canonical().equals(canonical(stored.canonical()))
        || !stored.canonical().equals(canonical(stored.receipt()))
        || !stored.hash().equals(canonicalHash(stored.canonical()))
        || !resultUri(stored.hash()).equals(authority.resultUri())
        || !stored.hash().equals(authority.resultHash())) {
      throw new IllegalStateException("target Review disposition replay conflicts");
    }
    DispositionReceipt receipt = stored.receipt();
    String expectedCaseWorkflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId(command.tenantSurrogate(), command.caseId());
    if (!command.tenantSurrogate().equals(receipt.tenantSurrogate())
        || !command.caseId().equals(receipt.caseId())
        || !command.commandId().equals(receipt.commandId())
        || !receiptId(command, decision).equals(receipt.receiptId())
        || !expectedCaseWorkflowId.equals(receipt.caseWorkflowId())
        || !decision.decisionRecordRef().equals(receipt.decisionRecordId())
        || !decision.decisionRecordHash().equals(receipt.decisionRecordHash())
        || decision.decision() != receipt.decision()
        || start.epoch() != receipt.sourceRoomEpoch()
        || start.fence() != receipt.sourceFencingToken()
        || receipt.terminalProcessRevision()
            != Math.incrementExact(command.expectedProcessRevision())
        || receipt.terminalRoomRevision() != Math.incrementExact(start.revision())
        || !validEvidenceTransition(receipt)) {
      throw new IllegalStateException("target Review disposition receipt scope conflicts");
    }
    requireActivationCompletion(connection, authority, stored.hash());
    return new CompletionResult(receipt, stored.hash());
  }

  private void requireReplayCoordinates(
      CompletionRequest request, Authority authority, CompletionResult result) {
    if (!"TERMINAL".equals(authority.epochLifecycle())
        || authority.processRevision() != result.receipt().terminalProcessRevision()
        || authority.roomRevision() != result.receipt().terminalRoomRevision()
        || request.expectedProcessRevision() + 1 != authority.processRevision()
        || request.expectedRoomRevision() + 1 != authority.roomRevision()) {
      throw new IllegalStateException("target Review disposition replay coordinates conflict");
    }
  }

  private void requirePersistedBranchState(
      Connection connection,
      LoadRequest request,
      Authority authority,
      CompletionResult result)
      throws SQLException {
    if (!request.decision().decisionRecordRef().equals(result.receipt().decisionRecordId())
        || !request.decision().decisionRecordHash().equals(result.receipt().decisionRecordHash())
        || request.decision().decision() != result.receipt().decision()
        || !"TERMINAL".equals(authority.epochLifecycle())
        || authority.processRevision() != result.receipt().terminalProcessRevision()
        || authority.roomRevision() != result.receipt().terminalRoomRevision()) {
      throw new IllegalStateException("target Review disposition durable branch conflicts");
    }
    String expectedStatus =
        result.receipt().decision() == ReviewDecision.REQUEST_MORE_EVIDENCE
            ? "EVIDENCE_OPEN"
            : "MANUAL_HANDOFF";
    String expectedRoom =
        result.receipt().decision() == ReviewDecision.REQUEST_MORE_EVIDENCE
            ? "EVIDENCE"
            : "OUTCOME";
    try (PreparedStatement statement = connection.prepareStatement("""
        select case_status, current_room, current_deadline_at
          from fulfillment_dispute_case where id = ? for key share
        """)) {
      statement.setString(1, request.command().caseId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || !expectedStatus.equals(rows.getString("case_status"))
            || !expectedRoom.equals(rows.getString("current_room"))
            || !caseDeadlineMatches(rows, result.receipt())
            || rows.next()) {
          throw new IllegalStateException("target Review disposition business state conflicts");
        }
      }
    }
    requireReviewRoomClosed(connection, request.command().caseId(), authority.reviewRoomId());
    if (result.receipt().decision() == ReviewDecision.REQUEST_MORE_EVIDENCE) {
      requireEvidenceBranchState(connection, result.receipt());
    }
  }

  private static boolean caseDeadlineMatches(ResultSet rows, DispositionReceipt receipt)
      throws SQLException {
    if (receipt.evidenceTransition() == null) {
      return rows.getObject("current_deadline_at") == null;
    }
    return sameDatabaseInstant(
        receipt.evidenceTransition().deadlineAt(), rows, "current_deadline_at");
  }

  private static void requireReviewRoomClosed(
      Connection connection, String caseId, String reviewRoomId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select room_status from case_room
         where id = ? and case_id = ? and room_type = 'REVIEW' for key share
        """)) {
      statement.setString(1, reviewRoomId);
      statement.setString(2, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || !"CLOSED".equals(rows.getString(1)) || rows.next()) {
          throw new IllegalStateException("target Review disposition room state conflicts");
        }
      }
    }
  }

  private static void requireEvidenceBranchState(
      Connection connection, DispositionReceipt receipt) throws SQLException {
    EvidenceTransition evidence =
        Objects.requireNonNull(
            receipt.evidenceTransition(), "target Review Evidence transition receipt");
    try (PreparedStatement statement = connection.prepareStatement("""
        select tenant_surrogate, case_id, room_id, room_type, room_epoch,
               process_revision, room_revision, fencing_token, writer_mode,
               lifecycle_status, temporal_workflow_id, temporal_run_id,
               room_temporal_workflow_id, room_temporal_run_id
          from case_room_epoch where id = ? for key share
        """)) {
      statement.setString(1, evidence.epochId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || !receipt.tenantSurrogate().equals(rows.getString("tenant_surrogate"))
            || !receipt.caseId().equals(rows.getString("case_id"))
            || !evidence.roomId().equals(rows.getString("room_id"))
            || !RoomType.EVIDENCE.name().equals(rows.getString("room_type"))
            || evidence.roomEpoch() != rows.getLong("room_epoch")
            || evidence.processRevision() != rows.getLong("process_revision")
            || evidence.roomRevision() != rows.getLong("room_revision")
            || evidence.fencingToken() != rows.getLong("fencing_token")
            || !WriterMode.TEMPORAL.name().equals(rows.getString("writer_mode"))
            || !receipt.caseWorkflowId().equals(rows.getString("temporal_workflow_id"))
            || !validProvisioningIdentity(rows, evidence)
            || rows.next()) {
          throw new IllegalStateException("target Review Evidence durable epoch conflicts");
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select room_id, clock_status, deadline_at, temporal_workflow_id
          from case_phase_clock
         where case_id = ? and clock_type = 'EVIDENCE_SUBMISSION' for key share
        """)) {
      statement.setString(1, receipt.caseId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || !evidence.roomId().equals(rows.getString("room_id"))
            || !"RUNNING".equals(rows.getString("clock_status"))
            || !evidence.workflowId().equals(rows.getString("temporal_workflow_id"))
            || !sameDatabaseInstant(evidence.deadlineAt(), rows, "deadline_at")
            || rows.next()) {
          throw new IllegalStateException("target Review Evidence durable clock conflicts");
        }
      }
    }
  }

  private static boolean validProvisioningIdentity(ResultSet rows, EvidenceTransition evidence)
      throws SQLException {
    String lifecycle = rows.getString("lifecycle_status");
    String caseRunId = rows.getString("temporal_run_id");
    String roomWorkflowId = rows.getString("room_temporal_workflow_id");
    String roomRunId = rows.getString("room_temporal_run_id");
    return validProvisioningIdentity(
        lifecycle, caseRunId, roomWorkflowId, roomRunId, evidence.workflowId());
  }

  static boolean validProvisioningIdentity(
      String lifecycle,
      String caseRunId,
      String roomWorkflowId,
      String roomRunId,
      String expectedRoomWorkflowId) {
    if (EpochLifecycleStatus.PREPARING.name().equals(lifecycle)
        || EpochLifecycleStatus.PROVISIONING.name().equals(lifecycle)) {
      return caseRunId == null
          && expectedRoomWorkflowId.equals(roomWorkflowId)
          && roomRunId == null;
    }
    return EpochLifecycleStatus.ACTIVE.name().equals(lifecycle)
        && caseRunId != null
        && !caseRunId.isBlank()
        && expectedRoomWorkflowId.equals(roomWorkflowId)
        && roomRunId != null
        && !roomRunId.isBlank();
  }

  private void markApplied(
      Connection connection, Authority authority, String receiptHash, Instant committedAt)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement("""
        update case_command
           set command_status = 'APPLIED', result_uri = ?, result_sha256 = ?, applied_at = ?,
               updated_at = ?, version = version + 1
         where id = ? and command_status = 'ORCHESTRATION_ACCEPTED'
           and result_uri is null and result_sha256 is null
        """)) {
      update.setString(1, resultUri(receiptHash));
      update.setString(2, receiptHash);
      update.setObject(3, committedAt);
      update.setObject(4, committedAt);
      update.setString(5, authority.commandRowId());
      requireOne(update.executeUpdate(), "target Review disposition command CAS was rejected");
    }
  }

  private void requireActivationCompletion(
      Connection connection, Authority authority, String receiptHash) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select 1 from target_e2e_command_completion
         where admission_id = ? and activation_id = ? and command_id = ?
           and command_hash = ? and command_envelope_hash = ? and completion_hash = ?
         for key share
        """)) {
      statement.setString(1, authority.admissionId());
      statement.setString(2, authority.activationId());
      statement.setString(3, receiptCommandId(connection, authority));
      statement.setString(4, authority.commandHash());
      statement.setString(5, authority.commandEnvelopeHash());
      statement.setString(6, receiptHash);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.next()) {
          throw new IllegalStateException("target Review activation completion conflicts");
        }
      }
    }
  }

  private String receiptCommandId(Connection connection, Authority authority) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select command_id from target_e2e_review_non_execution_completion
         where admission_id = ? and activation_id = ? for key share
        """)) {
      statement.setString(1, authority.admissionId());
      statement.setString(2, authority.activationId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Review disposition receipt is absent");
        String commandId = rows.getString(1);
        if (rows.next()) throw new IllegalStateException("target Review disposition receipt is ambiguous");
        return commandId;
      }
    }
  }

  private static void lockBusinessCase(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select id from fulfillment_dispute_case where id = ? for update
        """)) {
      statement.setString(1, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.next()) {
          throw new IllegalStateException("target Review business case is absent");
        }
      }
    }
  }

  private static void requireManualHandoff(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select case_status, current_deadline_at from fulfillment_dispute_case
         where id = ? for key share
        """)) {
      statement.setString(1, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || !"MANUAL_HANDOFF".equals(rows.getString(1))
            || rows.getObject(2) != null
            || rows.next()) {
          throw new IllegalStateException("target Review manual handoff state conflicts");
        }
      }
    }
  }

  private static BusinessEvidenceRoom lockEvidenceRoom(Connection connection, String caseId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select id, room_status from case_room
         where case_id = ? and room_type = 'EVIDENCE' for update
        """)) {
      statement.setString(1, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Review Evidence room is absent");
        BusinessEvidenceRoom room = new BusinessEvidenceRoom(rows.getString(1), rows.getString(2));
        if (rows.next() || (!"SEALED".equals(room.status()) && !"CLOSED".equals(room.status()))) {
          throw new IllegalStateException("target Review Evidence room cannot be reopened");
        }
        return room;
      }
    }
  }

  private static void closeReviewRoom(Connection connection, String caseId, Instant committedAt)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement("""
        update case_room
           set room_status = 'CLOSED', closed_at = ?, updated_at = ?, updated_by = ?,
               version = version + 1
         where case_id = ? and room_type = 'REVIEW' and room_status <> 'CLOSED'
        """)) {
      update.setObject(1, committedAt);
      update.setObject(2, committedAt);
      update.setString(3, WRITER);
      update.setString(4, caseId);
      requireOne(update.executeUpdate(), "target Review room close was rejected");
    }
  }

  private String receiptId(CaseCommandRef command, OutcomeReviewDecisionReceipt decision) {
    String seed =
        ContractJson.sha256Hex(
            mapper.valueToTree(
                List.of(
                    DispositionReceipt.SCHEMA_VERSION,
                    command.tenantSurrogate(),
                    command.caseId(),
                    command.commandId(),
                    decision.decisionRecordHash(),
                    decision.decision().name())));
    return "RVNEX_" + seed.substring(0, 32);
  }

  static String resultUri(String receiptHash) {
    if (receiptHash == null || !receiptHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target Review disposition hash is invalid");
    }
    return RESULT_URI_PREFIX + receiptHash;
  }

  private String canonical(Object value) {
    if (value instanceof String json) {
      try {
        return ContractJson.canonicalString(mapper.readTree(json));
      } catch (Exception failure) {
        throw new IllegalStateException("target Review canonical JSON is malformed", failure);
      }
    }
    return ContractJson.canonicalString(mapper.valueToTree(value));
  }

  private String canonicalHash(String json) {
    try {
      return ContractJson.sha256Hex(mapper.readTree(json));
    } catch (Exception failure) {
      throw new IllegalStateException("target Review canonical JSON is malformed", failure);
    }
  }

  private static void requireTransaction(Connection connection) throws SQLException {
    if (connection.isClosed() || connection.getAutoCommit()) {
      throw new IllegalStateException("target Review non-execution requires a transaction");
    }
  }

  private static void requireOne(int count, String message) {
    if (count != 1) throw new IllegalStateException(message);
  }

  private record Authority(
      String commandRowId,
      String commandStatus,
      String resultUri,
      String resultHash,
      String admissionId,
      String activationId,
      String commandHash,
      String commandEnvelopeHash,
      String activationLifecycle,
      boolean acceptsNewWrite,
      String epochLifecycle,
      long processRevision,
      long roomRevision,
      String reviewRoomId) {}

  private record Stored(
      String canonical,
      String hash,
      String admissionId,
      String activationId,
      String commandHash,
      String commandEnvelopeHash,
      DispositionReceipt receipt) {}

  private record BusinessEvidenceRoom(String id, String status) {}

  private record TransitionState(
      long terminalProcessRevision,
      long terminalRoomRevision,
      EvidenceTransition evidenceTransition) {}
}
