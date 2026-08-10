package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;

/** Exact, payload-free authority for one target Intake AgentRun that terminated without a commit. */
public record TargetIntakeCommandTerminalNoCommit(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    String roomWorkflowId,
    String roomWorkflowRunId,
    String roomWorkflowBuildId,
    String activationId,
    String activationManifestHash,
    String caseBuildId,
    String controlBuildId,
    String agentBuildId,
    String graphBindingHash,
    String graphCodeBuildId,
    String commandHash,
    String commandEnvelopeHash,
    String logicalInputHash,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String agentRunExecutionRequestHash,
    String commandId,
    long caseCommandSequence,
    String commandRequestHash,
    String messageId,
    String messageRef,
    String messageHash,
    long expectedProcessRevision,
    long newProcessRevision,
    long expectedRoomRevision,
    long newRoomRevision,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Long expectedLastCaseEventSequence,
    long lastCaseEventSequence,
    String logicalRunId,
    String rootAttemptId,
    String terminalAttemptId,
    long terminalAttemptNo,
    AgentRunAttemptStatus terminalAttemptStatus,
    ExecuteAgentRunResult.Outcome agentRunOutcome,
    String errorCode,
    boolean retryable,
    AgentRunRecoveryAction recoveryAction,
    long lastSequenceNo,
    boolean publicOutputEmitted,
    Instant terminalAt,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Long expectedProjectionLastCaseEventSequence,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Long newProjectionLastCaseEventSequence,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<TargetIntakeSourceEventRef> interveningCaseEvents) {

  public static final String LEGACY_SCHEMA_VERSION =
      "target-intake-command-terminal-no-commit.v1";
  public static final String SCHEMA_VERSION = "target-intake-command-terminal-no-commit.v2";
  public static final String V3_SCHEMA_VERSION = "target-intake-command-terminal-no-commit.v3";
  private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_MAPPER =
      JsonMapper.builder().findAndAddModules().build();

  static {
    CANONICAL_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public TargetIntakeCommandTerminalNoCommit {
    boolean legacy = LEGACY_SCHEMA_VERSION.equals(schemaVersion);
    boolean v3 = V3_SCHEMA_VERSION.equals(schemaVersion);
    if (!legacy && !SCHEMA_VERSION.equals(schemaVersion) && !v3) {
      throw new IllegalArgumentException(
          "schemaVersion must be target-intake-command-terminal-no-commit.v1, .v2, or .v3");
    }
    requireText(tenantSurrogate, 128, "tenantSurrogate");
    requireText(caseId, 64, "caseId");
    if (roomType != RoomType.INTAKE) {
      throw new IllegalArgumentException("terminal-no-commit authority must target Intake");
    }
    if (roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("room epoch and fencing token are invalid");
    }
    requireText(roomWorkflowId, 128, "roomWorkflowId");
    requireText(roomWorkflowRunId, 128, "roomWorkflowRunId");
    requireText(roomWorkflowBuildId, 128, "roomWorkflowBuildId");
    requireText(activationId, 128, "activationId");
    requireHash(activationManifestHash, "activationManifestHash");
    requireText(caseBuildId, 128, "caseBuildId");
    requireText(controlBuildId, 128, "controlBuildId");
    requireText(agentBuildId, 128, "agentBuildId");
    requireHash(graphBindingHash, "graphBindingHash");
    requireText(graphCodeBuildId, 128, "graphCodeBuildId");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    requireHash(logicalInputHash, "logicalInputHash");
    if (legacy) {
      if (agentRunExecutionRequestHash != null || expectedLastCaseEventSequence != null) {
        throw new IllegalArgumentException("v1 terminal authority must omit v2 fields");
      }
    } else {
      requireHash(agentRunExecutionRequestHash, "agentRunExecutionRequestHash");
      if (expectedLastCaseEventSequence == null) {
        throw new IllegalArgumentException("expectedLastCaseEventSequence is required for v2");
      }
    }
    if (!v3) {
      if (expectedProjectionLastCaseEventSequence != null
          || newProjectionLastCaseEventSequence != null
          || interveningCaseEvents != null) {
        throw new IllegalArgumentException("v1/v2 terminal authority must omit v3 fields");
      }
    } else {
      if (expectedProjectionLastCaseEventSequence == null
          || newProjectionLastCaseEventSequence == null
          || interveningCaseEvents == null) {
        throw new IllegalArgumentException("v3 terminal authority requires projection lineage");
      }
      interveningCaseEvents = List.copyOf(interveningCaseEvents);
    }
    if (!roomWorkflowBuildId.equals(controlBuildId)) {
      throw new IllegalArgumentException("room workflow build must equal control build");
    }
    requireText(commandId, 128, "commandId");
    requireHash(commandRequestHash, "commandRequestHash");
    requireText(messageId, 128, "messageId");
    requireText(messageRef, 1024, "messageRef");
    requireHash(messageHash, "messageHash");
    if (caseCommandSequence < 1
        || expectedProcessRevision < 0
        || expectedRoomRevision < 0
        || newProcessRevision != Math.incrementExact(expectedProcessRevision)
        || newRoomRevision != Math.incrementExact(expectedRoomRevision)
        || lastCaseEventSequence < 0
        || (!legacy
            && (expectedLastCaseEventSequence < 0
                || lastCaseEventSequence < expectedLastCaseEventSequence))) {
      throw new IllegalArgumentException("terminal-no-commit coordinates are invalid");
    }
    if (v3) {
      requireProjectionLineage(
          tenantSurrogate,
          caseId,
          roomType,
          roomEpoch,
          fencingToken,
          expectedProjectionLastCaseEventSequence,
          newProjectionLastCaseEventSequence,
          expectedLastCaseEventSequence,
          lastCaseEventSequence,
          interveningCaseEvents);
    }
    requireText(logicalRunId, 128, "logicalRunId");
    requireText(rootAttemptId, 128, "rootAttemptId");
    requireText(terminalAttemptId, 128, "terminalAttemptId");
    if (terminalAttemptNo < 1) {
      throw new IllegalArgumentException("terminalAttemptNo must be positive");
    }
    if (terminalAttemptStatus != AgentRunAttemptStatus.FAILED
        && terminalAttemptStatus != AgentRunAttemptStatus.ABORTED) {
      throw new IllegalArgumentException("terminal attempt must be FAILED or ABORTED");
    }
    if (agentRunOutcome != ExecuteAgentRunResult.Outcome.FAILED
        || retryable
        || recoveryAction != AgentRunRecoveryAction.FAIL_LOGICAL_RUN) {
      throw new IllegalArgumentException("AgentRun failure is not terminal without a commit");
    }
    requireText(errorCode, 64, "errorCode");
    if (lastSequenceNo < 0 || terminalAt == null) {
      throw new IllegalArgumentException("terminal result cursor is invalid");
    }
    AgentRunAttemptStatus expectedStatus =
        publicOutputEmitted ? AgentRunAttemptStatus.ABORTED : AgentRunAttemptStatus.FAILED;
    if (terminalAttemptStatus != expectedStatus) {
      throw new IllegalArgumentException("terminal attempt status conflicts with public output");
    }
  }

  /** Former constructor retained so v1/v2 source and Temporal payloads keep their exact shape. */
  public TargetIntakeCommandTerminalNoCommit(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      String roomWorkflowId,
      String roomWorkflowRunId,
      String roomWorkflowBuildId,
      String activationId,
      String activationManifestHash,
      String caseBuildId,
      String controlBuildId,
      String agentBuildId,
      String graphBindingHash,
      String graphCodeBuildId,
      String commandHash,
      String commandEnvelopeHash,
      String logicalInputHash,
      String agentRunExecutionRequestHash,
      String commandId,
      long caseCommandSequence,
      String commandRequestHash,
      String messageId,
      String messageRef,
      String messageHash,
      long expectedProcessRevision,
      long newProcessRevision,
      long expectedRoomRevision,
      long newRoomRevision,
      Long expectedLastCaseEventSequence,
      long lastCaseEventSequence,
      String logicalRunId,
      String rootAttemptId,
      String terminalAttemptId,
      long terminalAttemptNo,
      AgentRunAttemptStatus terminalAttemptStatus,
      ExecuteAgentRunResult.Outcome agentRunOutcome,
      String errorCode,
      boolean retryable,
      AgentRunRecoveryAction recoveryAction,
      long lastSequenceNo,
      boolean publicOutputEmitted,
      Instant terminalAt) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomType,
        roomEpoch,
        fencingToken,
        roomWorkflowId,
        roomWorkflowRunId,
        roomWorkflowBuildId,
        activationId,
        activationManifestHash,
        caseBuildId,
        controlBuildId,
        agentBuildId,
        graphBindingHash,
        graphCodeBuildId,
        commandHash,
        commandEnvelopeHash,
        logicalInputHash,
        agentRunExecutionRequestHash,
        commandId,
        caseCommandSequence,
        commandRequestHash,
        messageId,
        messageRef,
        messageHash,
        expectedProcessRevision,
        newProcessRevision,
        expectedRoomRevision,
        newRoomRevision,
        expectedLastCaseEventSequence,
        lastCaseEventSequence,
        logicalRunId,
        rootAttemptId,
        terminalAttemptId,
        terminalAttemptNo,
        terminalAttemptStatus,
        agentRunOutcome,
        errorCode,
        retryable,
        recoveryAction,
        lastSequenceNo,
        publicOutputEmitted,
        terminalAt,
        null,
        null,
        null);
  }

  public TargetIntakeCommandTerminalNoCommit withProjectionLineage(
      long expectedProjectionEventSequence,
      long newProjectionEventSequence,
      List<TargetIntakeSourceEventRef> interveningEvents) {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalStateException("only strict v2 authority can be resolved to v3");
    }
    return new TargetIntakeCommandTerminalNoCommit(
        V3_SCHEMA_VERSION,
        tenantSurrogate,
        caseId,
        roomType,
        roomEpoch,
        fencingToken,
        roomWorkflowId,
        roomWorkflowRunId,
        roomWorkflowBuildId,
        activationId,
        activationManifestHash,
        caseBuildId,
        controlBuildId,
        agentBuildId,
        graphBindingHash,
        graphCodeBuildId,
        commandHash,
        commandEnvelopeHash,
        logicalInputHash,
        agentRunExecutionRequestHash,
        commandId,
        caseCommandSequence,
        commandRequestHash,
        messageId,
        messageRef,
        messageHash,
        expectedProcessRevision,
        newProcessRevision,
        expectedRoomRevision,
        newRoomRevision,
        expectedLastCaseEventSequence,
        lastCaseEventSequence,
        logicalRunId,
        rootAttemptId,
        terminalAttemptId,
        terminalAttemptNo,
        terminalAttemptStatus,
        agentRunOutcome,
        errorCode,
        retryable,
        recoveryAction,
        lastSequenceNo,
        publicOutputEmitted,
        terminalAt,
        expectedProjectionEventSequence,
        newProjectionEventSequence,
        interveningEvents);
  }

  public TargetIntakeCommandTerminalNoCommit asObservedV2Authority() {
    if (SCHEMA_VERSION.equals(schemaVersion)) {
      return this;
    }
    if (!V3_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalStateException("only strict v3 authority has a v2 observed authority");
    }
    return new TargetIntakeCommandTerminalNoCommit(
        SCHEMA_VERSION,
        tenantSurrogate,
        caseId,
        roomType,
        roomEpoch,
        fencingToken,
        roomWorkflowId,
        roomWorkflowRunId,
        roomWorkflowBuildId,
        activationId,
        activationManifestHash,
        caseBuildId,
        controlBuildId,
        agentBuildId,
        graphBindingHash,
        graphCodeBuildId,
        commandHash,
        commandEnvelopeHash,
        logicalInputHash,
        agentRunExecutionRequestHash,
        commandId,
        caseCommandSequence,
        commandRequestHash,
        messageId,
        messageRef,
        messageHash,
        expectedProcessRevision,
        newProcessRevision,
        expectedRoomRevision,
        newRoomRevision,
        expectedLastCaseEventSequence,
        lastCaseEventSequence,
        logicalRunId,
        rootAttemptId,
        terminalAttemptId,
        terminalAttemptNo,
        terminalAttemptStatus,
        agentRunOutcome,
        errorCode,
        retryable,
        recoveryAction,
        lastSequenceNo,
        publicOutputEmitted,
        terminalAt);
  }

  public String receiptSha256() {
    return ContractJson.sha256Hex(CANONICAL_MAPPER.valueToTree(this));
  }

  public String receiptUri() {
    return "urn:target-intake-command-terminal-no-commit:" + receiptSha256();
  }

  private static void requireText(String value, int maximumLength, String field) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " must be bounded nonblank text");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
    }
  }

  private static void requireProjectionLineage(
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      long expectedProjectionEventSequence,
      long newProjectionEventSequence,
      long expectedObservedEventSequence,
      long newObservedEventSequence,
      List<TargetIntakeSourceEventRef> interveningEvents) {
    if (expectedProjectionEventSequence < 0
        || newProjectionEventSequence < expectedProjectionEventSequence
        || expectedProjectionEventSequence > expectedObservedEventSequence
        || expectedObservedEventSequence > newObservedEventSequence
        || newProjectionEventSequence != newObservedEventSequence) {
      throw new IllegalArgumentException("v3 projection and observed cursors are inconsistent");
    }
    long lineageLength = newProjectionEventSequence - expectedProjectionEventSequence;
    if (lineageLength > 256 || interveningEvents.size() != lineageLength) {
      throw new IllegalArgumentException("v3 intervening event lineage is not exact");
    }
    for (int index = 0; index < interveningEvents.size(); index++) {
      TargetIntakeSourceEventRef event = interveningEvents.get(index);
      long expectedSequence = expectedProjectionEventSequence + index + 1L;
      if (event == null
          || event.eventSequence() != expectedSequence
          || !tenantSurrogate.equals(event.tenantSurrogate())
          || !caseId.equals(event.caseId())
          || event.roomType() != roomType
          || event.roomEpoch() != roomEpoch
          || event.fencingToken() != fencingToken
          || !TargetIntakeSourceEventRef.isCursorOnlyEventType(event.eventType())) {
        throw new IllegalArgumentException("v3 intervening event lineage is not exact");
      }
    }
  }
}
