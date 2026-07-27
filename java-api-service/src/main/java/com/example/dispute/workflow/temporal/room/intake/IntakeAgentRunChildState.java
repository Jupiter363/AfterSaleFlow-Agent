package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import java.util.Objects;

/** Bounded durable identity for one target AgentRun child and its committed receipt. */
public record IntakeAgentRunChildState(
    String schemaVersion,
    String childWorkflowId,
    String commandId,
    String childRequestHash,
    String logicalRunId,
    String attemptId,
    Status status,
    String resultHash,
    String finalizationOperationKey,
    String agentRunManifestId,
    String agentRunManifestHash,
    String finalizationReceiptHash) {

  public IntakeAgentRunChildState {
    if (!"intake-agent-run-child-state.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-child-state.v1");
    }
    requireIdentifier(childWorkflowId, "childWorkflowId");
    requireIdentifier(commandId, "commandId");
    requireHash(childRequestHash, "childRequestHash");
    requireIdentifier(logicalRunId, "logicalRunId");
    requireIdentifier(attemptId, "attemptId");
    Objects.requireNonNull(status, "status must not be null");
    switch (status) {
      case PENDING -> requireEmptyResult();
      case RESULT_READY -> {
        requireHash(resultHash, "resultHash");
        if (finalizationOperationKey != null
            || agentRunManifestId != null
            || agentRunManifestHash != null
            || finalizationReceiptHash != null) {
          throw new IllegalArgumentException(
              "result-ready child cannot carry committed receipt identity");
        }
      }
      case RECEIPT_COMMITTED -> {
        requireHash(resultHash, "resultHash");
        IntakeOperationKeys.requireValid(finalizationOperationKey);
        requireIdentifier(agentRunManifestId, "agentRunManifestId");
        requireHash(agentRunManifestHash, "agentRunManifestHash");
        requireHash(finalizationReceiptHash, "finalizationReceiptHash");
      }
      case TERMINAL_NO_COMMIT -> {
        if (finalizationOperationKey != null
            || agentRunManifestId != null
            || agentRunManifestHash != null
            || finalizationReceiptHash != null) {
          throw new IllegalArgumentException(
              "terminal child without a commit cannot carry receipt identity");
        }
      }
    }
  }

  public static IntakeAgentRunChildState pending(
      String workflowId, IntakeTargetAgentRunContext target) {
    return new IntakeAgentRunChildState(
        "intake-agent-run-child-state.v1",
        workflowId,
        target.request().command().commandId(),
        target.commandEnvelopeHash(),
        target.request().logicalRunId(),
        target.request().attemptId(),
        Status.PENDING,
        null,
        null,
        null,
        null,
        null);
  }

  public IntakeAgentRunChildState resultReady(String completedResultHash) {
    if (status != Status.PENDING && status != Status.RESULT_READY) {
      throw new IllegalStateException("child is not awaiting a result");
    }
    requireHash(completedResultHash, "completedResultHash");
    if (status == Status.RESULT_READY && !completedResultHash.equals(resultHash)) {
      throw new IllegalArgumentException("completed child result hash changed");
    }
    return new IntakeAgentRunChildState(
        schemaVersion,
        childWorkflowId,
        commandId,
        childRequestHash,
        logicalRunId,
        attemptId,
        Status.RESULT_READY,
        completedResultHash,
        null,
        null,
        null,
        null);
  }

  public IntakeAgentRunChildState committed(IntakeAgentRunFinalizationReadResult result) {
    Objects.requireNonNull(result, "result");
    if (result.resolution() != IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED) {
      throw new IllegalArgumentException("finalization result is not committed");
    }
    var locator = result.locator();
    if (!logicalRunId.equals(locator.logicalRunId())
        || !attemptId.equals(locator.attemptId())
        || !Objects.equals(resultHash, locator.resultHash())) {
      throw new IllegalArgumentException("committed locator does not match the child result");
    }
    return new IntakeAgentRunChildState(
        schemaVersion,
        childWorkflowId,
        commandId,
        childRequestHash,
        logicalRunId,
        attemptId,
        Status.RECEIPT_COMMITTED,
        resultHash,
        locator.operationKey(),
        locator.agentRunManifestId(),
        locator.agentRunManifestHash(),
        locator.receiptHash());
  }

  public IntakeAgentRunChildState terminalNoCommit() {
    return new IntakeAgentRunChildState(
        schemaVersion,
        childWorkflowId,
        commandId,
        childRequestHash,
        logicalRunId,
        attemptId,
        Status.TERMINAL_NO_COMMIT,
        resultHash,
        null,
        null,
        null,
        null);
  }

  public boolean unresolved() {
    return status == Status.PENDING || status == Status.RESULT_READY;
  }

  public void requireMatches(IntakeWorkflowCommand command, IntakeTargetAgentRunContext target) {
    if (!commandId.equals(command.commandId())
        || !commandId.equals(target.request().command().commandId())
        || !childRequestHash.equals(target.commandEnvelopeHash())
        || !logicalRunId.equals(target.request().logicalRunId())
        || !attemptId.equals(target.request().attemptId())
        || !childWorkflowId.equals(IntakeAgentRunChildIds.forCommand(command))) {
      throw new IllegalArgumentException("AgentRun child identity conflicts with the command");
    }
  }

  private void requireEmptyResult() {
    if (resultHash != null
        || finalizationOperationKey != null
        || agentRunManifestId != null
        || agentRunManifestHash != null
        || finalizationReceiptHash != null) {
      throw new IllegalArgumentException("pending child cannot carry result or receipt identity");
    }
  }

  public enum Status {
    PENDING,
    RESULT_READY,
    RECEIPT_COMMITTED,
    TERMINAL_NO_COMMIT
  }
}
