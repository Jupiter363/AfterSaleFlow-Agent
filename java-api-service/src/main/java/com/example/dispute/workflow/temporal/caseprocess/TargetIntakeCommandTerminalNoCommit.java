package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;

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
    Instant terminalAt) {

  public static final String LEGACY_SCHEMA_VERSION =
      "target-intake-command-terminal-no-commit.v1";
  public static final String SCHEMA_VERSION = "target-intake-command-terminal-no-commit.v2";
  private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_MAPPER =
      JsonMapper.builder().findAndAddModules().build();

  static {
    CANONICAL_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public TargetIntakeCommandTerminalNoCommit {
    boolean legacy = LEGACY_SCHEMA_VERSION.equals(schemaVersion);
    if (!legacy && !SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be target-intake-command-terminal-no-commit.v1 or .v2");
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
}
