package com.example.dispute.workflow.runtime.temporal.room;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact immutable authority for a target Evidence AgentRun that terminated without a commit. */
public record TargetRoomAgentRunTerminalNoCommit(
    String schemaVersion,
    CaseCommandRef command,
    long roomFencingToken,
    long expectedRoomRevision,
    long expectedLastCaseEventSequence,
    String roomWorkflowId,
    String roomWorkflowRunId,
    String roomWorkflowBuildId,
    String commandHash,
    String commandEnvelopeHash,
    ExecuteAgentRunRequest rootRequest,
    ExecuteAgentRunResult terminalResult,
    AgentRunAttemptStatus terminalAttemptStatus,
    String terminalErrorCode,
    boolean terminalRetryable,
    AgentRunRecoveryAction terminalRecoveryAction,
    long terminalLastSequenceNo,
    Instant terminalAt,
    boolean finalFrameObserved) {

  public static final String SCHEMA_VERSION = "target-room-agent-run-terminal-no-commit.v1";
  private static final Pattern SAMPLED_TRACEPARENT =
      Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
  private static final String ZERO_TRACE_ID = "0".repeat(32);
  private static final String ZERO_SPAN_ID = "0".repeat(16);
  private static final String GRAPH_ROOT_SPAN_ID = "0000000000000001";
  private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_MAPPER =
      JsonMapper.builder().findAndAddModules().build();

  static {
    CANONICAL_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public TargetRoomAgentRunTerminalNoCommit {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be target-room-agent-run-terminal-no-commit.v1");
    }
    command = Objects.requireNonNull(command, "command");
    rootRequest = Objects.requireNonNull(rootRequest, "rootRequest");
    terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
    terminalAttemptStatus = Objects.requireNonNull(terminalAttemptStatus, "terminalAttemptStatus");
    terminalRecoveryAction =
        Objects.requireNonNull(terminalRecoveryAction, "terminalRecoveryAction");
    terminalAt = Objects.requireNonNull(terminalAt, "terminalAt");
    requireText(roomWorkflowId, 128, "roomWorkflowId");
    requireText(roomWorkflowRunId, 128, "roomWorkflowRunId");
    requireText(roomWorkflowBuildId, 128, "roomWorkflowBuildId");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    requireText(terminalErrorCode, 64, "terminalErrorCode");

    if (command.roomType() != RoomType.EVIDENCE
        || (command.commandType() != CommandType.EVIDENCE_OPENING
            && command.commandType() != CommandType.EVIDENCE_SUBMIT)
        || command.caseCommandSequence() < 1
        || command.roomEpoch() < 0
        || command.expectedProcessRevision() < 0
        || roomFencingToken < 1
        || expectedRoomRevision < 0
        || expectedLastCaseEventSequence < 0) {
      throw new IllegalArgumentException("terminal-no-commit command authority is invalid");
    }

    RoomGraphCommand graph = rootRequest.command();
    if (rootRequest.attemptNo() != 1
        || !command.commandId().equals(graph.commandId())
        || !command.tenantSurrogate().equals(graph.tenantSurrogate())
        || !command.caseId().equals(graph.caseId())
        || graph.roomType() != RoomType.EVIDENCE
        || command.roomEpoch() != graph.roomEpoch()
        || command.expectedProcessRevision() != graph.processRevision()
        || !command.actorRef().actorId().equals(graph.actorScope().actorId())
        || command.actorRef().actorRole() != graph.actorScope().actorRole()
        || !command.actorRef().actorScopes().equals(graph.actorScope().capabilities())
        || !command.deadlineAt().equals(graph.deadlineAt())
        || !matchesGraphTraceparent(command.traceparent(), graph.traceparent())
        || !matchesPayload(command, graph.eventRef())) {
      throw new IllegalArgumentException(
          "terminal-no-commit root request does not bind the case command");
    }

    if (!rootRequest.logicalRunId().equals(terminalResult.logicalRunId())
        || !rootRequest.agentRunId().equals(terminalResult.agentRunId())
        || terminalResult.attemptNo() < rootRequest.attemptNo()
        || terminalResult.attemptNo() > rootRequest.attemptLimit()
        || terminalRetryable
        || terminalRecoveryAction != AgentRunRecoveryAction.FAIL_LOGICAL_RUN
        || terminalLastSequenceNo < 0) {
      throw new IllegalArgumentException("AgentRun result is not terminal without a commit");
    }
    boolean rootAttemptTerminal = terminalResult.attemptNo() == rootRequest.attemptNo();
    if ((rootAttemptTerminal && !rootRequest.attemptId().equals(terminalResult.attemptId()))
        || (!rootAttemptTerminal
            && rootRequest.attemptId().equals(terminalResult.attemptId()))) {
      throw new IllegalArgumentException(
          "terminal attempt identity conflicts with the root attempt lineage");
    }
    boolean failedResult = terminalResult.outcome() == ExecuteAgentRunResult.Outcome.FAILED;
    boolean completedAudit = terminalResult.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED;
    if ((!failedResult && !completedAudit)
        || finalFrameObserved != completedAudit) {
      throw new IllegalArgumentException(
          "terminal result shape conflicts with final-frame authority");
    }
    if (failedResult
        && (!terminalErrorCode.equals(terminalResult.errorCode())
            || terminalRetryable != terminalResult.retryable()
            || terminalRecoveryAction != terminalResult.recoveryAction()
            || terminalLastSequenceNo != terminalResult.lastSequenceNo()
            || !terminalAt.equals(terminalResult.completedAt()))) {
      throw new IllegalArgumentException(
          "failed terminal result conflicts with its terminal authority");
    }
    if (completedAudit
        && (terminalLastSequenceNo != Math.incrementExact(terminalResult.lastSequenceNo())
            || !terminalAt.equals(terminalResult.completedAt()))) {
      throw new IllegalArgumentException(
          "completed audit conflicts with finalization-rejected terminal authority");
    }
    AgentRunAttemptStatus expectedStatus =
        terminalResult.publicOutputEmitted()
            ? AgentRunAttemptStatus.ABORTED
            : AgentRunAttemptStatus.FAILED;
    if (terminalAttemptStatus != expectedStatus) {
      throw new IllegalArgumentException(
          "terminal attempt status conflicts with public output authority");
    }
  }

  public String receiptSha256() {
    return ContractJson.sha256Hex(CANONICAL_MAPPER.valueToTree(this));
  }

  public String receiptUri() {
    return "urn:target-room-agent-run-terminal-no-commit:" + receiptSha256();
  }

  private static boolean matchesGraphTraceparent(String source, String graph) {
    if (source == null || !SAMPLED_TRACEPARENT.matcher(source).matches()) {
      return false;
    }
    String traceId = source.substring(3, 35);
    String sourceSpanId = source.substring(36, 52);
    return !ZERO_TRACE_ID.equals(traceId)
        && !ZERO_SPAN_ID.equals(sourceSpanId)
        && ("00-" + traceId + "-" + GRAPH_ROOT_SPAN_ID + "-01").equals(graph);
  }

  private static boolean matchesPayload(CaseCommandRef command, RoomGraphCommand.SnapshotRef event) {
    return event != null
        && command.payloadRef().schemaVersion().equals(event.schemaVersion())
        && command.payloadRef().uri().equals(event.uri())
        && command.payloadRef().sha256().equals(event.sha256())
        && command.payloadRef().sizeBytes() == event.sizeBytes();
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
