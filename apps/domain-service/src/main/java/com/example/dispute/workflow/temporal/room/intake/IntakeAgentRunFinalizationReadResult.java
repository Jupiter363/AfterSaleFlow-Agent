package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Authoritative read result; only ABSENT_TERMINAL permits cancellation to forget the command. */
public record IntakeAgentRunFinalizationReadResult(
    String schemaVersion,
    Resolution resolution,
    FinalizationLocator locator,
    TurnFinalizationReceipt receipt,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        TerminalNoCommitEvidence terminalNoCommitEvidence) {

  public static final String LEGACY_SCHEMA_VERSION =
      "intake-agent-run-finalization-read-result.v1";
  public static final String SCHEMA_VERSION = "intake-agent-run-finalization-read-result.v2";

  public IntakeAgentRunFinalizationReadResult(
      String schemaVersion,
      Resolution resolution,
      FinalizationLocator locator,
      TurnFinalizationReceipt receipt) {
    this(schemaVersion, resolution, locator, receipt, null);
  }

  public IntakeAgentRunFinalizationReadResult {
    boolean legacy = LEGACY_SCHEMA_VERSION.equals(schemaVersion);
    if (!legacy && !SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-read-result.v1 or .v2");
    }
    Objects.requireNonNull(resolution, "resolution must not be null");
    if (legacy) {
      if (resolution == Resolution.TERMINAL_NO_COMMIT || terminalNoCommitEvidence != null) {
        throw new IllegalArgumentException("v1 finalization read cannot carry terminal evidence");
      }
    } else if (resolution != Resolution.TERMINAL_NO_COMMIT
        || terminalNoCommitEvidence == null) {
      throw new IllegalArgumentException(
          "v2 finalization read requires terminal-no-commit evidence");
    }
    if (resolution == Resolution.COMMITTED) {
      Objects.requireNonNull(locator, "committed result requires a locator");
      Objects.requireNonNull(receipt, "committed result requires a receipt");
    } else if (locator != null || receipt != null) {
      throw new IllegalArgumentException("non-committed result cannot carry a receipt");
    }
  }

  public void requireMatches(IntakeAgentRunFinalizationReadRequest request) {
    requireMatches(request, false);
  }

  public void requireMatches(
      IntakeAgentRunFinalizationReadRequest request, boolean allowWinningAttempt) {
    Objects.requireNonNull(request, "request");
    if (resolution == Resolution.TERMINAL_NO_COMMIT) {
      terminalNoCommitEvidence.requireMatches(request);
      return;
    }
    if (resolution != Resolution.COMMITTED) {
      return;
    }
    IntakeWorkflowCommand command = request.command();
    IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
    IntakeAgentRunChildState child = request.childState();
    var operation = receipt.operation();
    var formal = receipt.formalReceipt();
    var event = receipt.committedEvent();
    if (!target.executionLane().equals(locator.executionLane())
        || !target.activationId().equals(locator.activationId())
        || !target.activationManifestHash().equals(locator.activationManifestHash())
        || target.roomFencingToken() != locator.roomFencingToken()
        || !child.logicalRunId().equals(locator.logicalRunId())
        || (!allowWinningAttempt && !child.attemptId().equals(locator.attemptId()))
        || !locator.operationKey().equals(operation.operationKey())
        || !locator.operationKey().equals(formal.operationKey())
        || !locator.resultHash().equals(operation.resultHash())
        || !locator.resultHash().equals(formal.resultHash())
        || !locator.proposalHash().equals(formal.proposalHash())
        || !command.requestHash().equals(operation.requestHash())
        || !command.tenantSurrogate().equals(formal.tenantSurrogate())
        || !command.caseId().equals(formal.caseId())
        || command.roomEpoch() != formal.roomEpoch()
        || command.fencingToken() != formal.fencingToken()
        || (!allowWinningAttempt && !command.commandId().equals(formal.commandId()))
        || !command.executionContext().threadId().equals(formal.threadId())
        || !command.executionContext().agentSessionId().equals(formal.agentSessionId())
        || !command.actorScopeHash().equals(formal.actorScopeHash())
        || !locator.logicalRunId().equals(formal.logicalRunId())
        || !locator.attemptId().equals(formal.attemptId())
        || !event.commandId().equals(formal.commandId())
        || !event.operationKey().equals(locator.operationKey())
        || !event.requestHash().equals(command.requestHash())
        || !event.resultHash().equals(locator.resultHash())
        || !event.agentRunRef().logicalRunId().equals(locator.logicalRunId())
        || !event.agentRunRef().attemptId().equals(locator.attemptId())
        || !event.agentRunRef().finalResultHash().equals(locator.resultHash())
        || !event.graphExecutionRef().graphCommandId().equals(formal.commandId())
        || !event.graphExecutionRef().proposalHash().equals(locator.proposalHash())
        || !event.graphExecutionRef().checkpointId().equals(locator.checkpointId())) {
      throw new IllegalArgumentException(
          "committed AgentRun finalization receipt does not match its exact lookup");
    }
    if (child.resultHash() != null && !child.resultHash().equals(locator.resultHash())) {
      throw new IllegalArgumentException("finalization result conflicts with the child result");
    }
  }

  public enum Resolution {
    COMMITTED,
    PENDING,
    ABSENT_TERMINAL,
    TERMINAL_NO_COMMIT
  }

  /** Exact durable proof that execution completed publicly but formal finalization was rejected. */
  public record TerminalNoCommitEvidence(
      String schemaVersion,
      String logicalRunId,
      String rootAttemptId,
      String terminalAttemptId,
      long terminalAttemptNo,
      AgentRunAttemptStatus terminalAttemptStatus,
      String stopReason,
      String finalizationStatus,
      String errorCode,
      long terminalSequenceNo,
      ExecuteAgentRunResult completedAudit) {

    public static final String SCHEMA_VERSION =
        "intake-agent-run-terminal-no-commit-evidence.v1";

    public TerminalNoCommitEvidence {
      if (!SCHEMA_VERSION.equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "schemaVersion must be intake-agent-run-terminal-no-commit-evidence.v1");
      }
      requireIdentifier(logicalRunId, "logicalRunId");
      requireIdentifier(rootAttemptId, "rootAttemptId");
      requireIdentifier(terminalAttemptId, "terminalAttemptId");
      if (terminalAttemptNo < 1 || terminalSequenceNo < 1) {
        throw new IllegalArgumentException("terminal attempt and sequence must be positive");
      }
      Objects.requireNonNull(terminalAttemptStatus, "terminalAttemptStatus must not be null");
      if (!"FINALIZATION_REJECTED".equals(stopReason)
          || !"UNCOMMITTED".equals(finalizationStatus)) {
        throw new IllegalArgumentException(
            "terminal evidence must be an uncommitted finalization rejection");
      }
      requireIdentifier(errorCode, "errorCode");
      Objects.requireNonNull(completedAudit, "completedAudit must not be null");
      if (completedAudit.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
          || !logicalRunId.equals(completedAudit.agentRunId())
          || !logicalRunId.equals(completedAudit.logicalRunId())
          || !terminalAttemptId.equals(completedAudit.attemptId())
          || terminalAttemptNo != completedAudit.attemptNo()
          || completedAudit.graphResult() == null
          || completedAudit.resultHash() == null
          || terminalSequenceNo != Math.incrementExact(completedAudit.lastSequenceNo())) {
        throw new IllegalArgumentException(
            "terminal evidence conflicts with the completed AgentRun audit");
      }
      AgentRunAttemptStatus expectedStatus =
          completedAudit.publicOutputEmitted()
              ? AgentRunAttemptStatus.ABORTED
              : AgentRunAttemptStatus.FAILED;
      if (terminalAttemptStatus != expectedStatus) {
        throw new IllegalArgumentException(
            "terminal attempt status conflicts with completed public output");
      }
    }

    public ExecuteAgentRunResult terminalResult() {
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          logicalRunId,
          logicalRunId,
          terminalAttemptId,
          terminalAttemptNo,
          ExecuteAgentRunResult.Outcome.FAILED,
          null,
          null,
          terminalSequenceNo,
          completedAudit.publicOutputEmitted(),
          errorCode,
          false,
          AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
          completedAudit.completedAt());
    }

    private void requireMatches(IntakeAgentRunFinalizationReadRequest request) {
      IntakeWorkflowCommand command = request.command();
      IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
      IntakeAgentRunChildState child = request.childState();
      if (!logicalRunId.equals(child.logicalRunId())
          || !logicalRunId.equals(target.request().logicalRunId())
          || !rootAttemptId.equals(child.attemptId())
          || !rootAttemptId.equals(target.request().attemptId())
          || terminalAttemptNo < target.request().attemptNo()
          || terminalAttemptNo > target.request().attemptLimit()
          || (terminalAttemptNo == target.request().attemptNo()
              && !terminalAttemptId.equals(rootAttemptId))) {
        throw new IllegalArgumentException(
            "terminal evidence does not match the exact Intake child lookup");
      }
    }
  }

  public record FinalizationLocator(
      String schemaVersion,
      String executionLane,
      String activationId,
      String activationManifestHash,
      long roomFencingToken,
      String logicalRunId,
      String attemptId,
      String resultHash,
      String proposalHash,
      String checkpointId,
      String operationKey,
      String agentRunManifestId,
      String agentRunManifestHash,
      String isolatedDomainDbBindingHash,
      String receiptHash) {

    public FinalizationLocator {
      if (!"intake-agent-run-finalization-locator.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "schemaVersion must be intake-agent-run-finalization-locator.v1");
      }
      if (!IntakeTargetAgentRunContext.TARGET_LANE.equals(executionLane)) {
        throw new IllegalArgumentException("executionLane must be PRODUCTION");
      }
      if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
        throw new IllegalArgumentException("activationId is invalid");
      }
      requireHash(activationManifestHash, "activationManifestHash");
      if (roomFencingToken < 1) {
        throw new IllegalArgumentException("roomFencingToken must be positive");
      }
      requireIdentifier(logicalRunId, "logicalRunId");
      requireIdentifier(attemptId, "attemptId");
      requireHash(resultHash, "resultHash");
      requireHash(proposalHash, "proposalHash");
      requireIdentifier(checkpointId, "checkpointId");
      IntakeOperationKeys.requireValid(operationKey);
      requireIdentifier(agentRunManifestId, "agentRunManifestId");
      requireHash(agentRunManifestHash, "agentRunManifestHash");
      requireHash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
      requireHash(receiptHash, "receiptHash");
    }
  }
}
