package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import java.util.Objects;

/** Authoritative read result; only ABSENT_TERMINAL permits cancellation to forget the command. */
public record IntakeAgentRunFinalizationReadResult(
    String schemaVersion,
    Resolution resolution,
    FinalizationLocator locator,
    TurnFinalizationReceipt receipt) {

  public IntakeAgentRunFinalizationReadResult {
    if (!"intake-agent-run-finalization-read-result.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-read-result.v1");
    }
    Objects.requireNonNull(resolution, "resolution must not be null");
    if (resolution == Resolution.COMMITTED) {
      Objects.requireNonNull(locator, "committed result requires a locator");
      Objects.requireNonNull(receipt, "committed result requires a receipt");
    } else if (locator != null || receipt != null) {
      throw new IllegalArgumentException("non-committed result cannot carry a receipt");
    }
  }

  public void requireMatches(IntakeAgentRunFinalizationReadRequest request) {
    Objects.requireNonNull(request, "request");
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
        || !child.attemptId().equals(locator.attemptId())
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
        || !command.commandId().equals(formal.commandId())
        || !command.executionContext().threadId().equals(formal.threadId())
        || !command.executionContext().agentSessionId().equals(formal.agentSessionId())
        || !command.actorScopeHash().equals(formal.actorScopeHash())
        || !locator.logicalRunId().equals(formal.logicalRunId())
        || !locator.attemptId().equals(formal.attemptId())
        || !event.commandId().equals(command.commandId())
        || !event.operationKey().equals(locator.operationKey())
        || !event.requestHash().equals(command.requestHash())
        || !event.resultHash().equals(locator.resultHash())
        || !event.agentRunRef().logicalRunId().equals(locator.logicalRunId())
        || !event.agentRunRef().attemptId().equals(locator.attemptId())
        || !event.agentRunRef().finalResultHash().equals(locator.resultHash())
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
    ABSENT_TERMINAL
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
        throw new IllegalArgumentException("executionLane must be TARGET_E2E_CANDIDATE");
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
