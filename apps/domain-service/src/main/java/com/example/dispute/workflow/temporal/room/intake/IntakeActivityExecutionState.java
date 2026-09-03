package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import java.util.Objects;

/** Query/carry state for one in-flight idempotent Activity stage. */
public record IntakeActivityExecutionState(
    String schemaVersion,
    String commandId,
    String rootCorrelationKey,
    IntakeActivityStage stage,
    String stageOperationKey,
    String requestHash,
    String threadId,
    String agentSessionId,
    long deadlineEpochMillis,
    RetryBudget retryBudget,
    ActivityInvocation invocation,
    GraphExecutionReceipt completedGraphExecution,
    IntakeActivityTerminalFailure terminalFailure) {

  public IntakeActivityExecutionState(
      String schemaVersion,
      String commandId,
      String rootCorrelationKey,
      IntakeActivityStage stage,
      String stageOperationKey,
      String requestHash,
      String threadId,
      String agentSessionId,
      long deadlineEpochMillis,
      RetryBudget retryBudget) {
    this(
        schemaVersion,
        commandId,
        rootCorrelationKey,
        stage,
        stageOperationKey,
        requestHash,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        invocationBudget(retryBudget),
        new ActivityInvocation(
            "intake-activity-invocation.v1",
            retryBudget.activityAttemptsRemaining() == 0
                ? ActivityInvocationMode.RECONCILE_ONLY
                : ActivityInvocationMode.FIRST_EXECUTION,
            Math.max(0, retryBudget.activityAttemptsRemaining() - 1)),
        null,
        null);
  }

  public IntakeActivityExecutionState {
    if (!"intake-activity-execution-state.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-activity-execution-state.v1");
    }
    requireIdentifier(commandId, "commandId");
    requireIdentifier(rootCorrelationKey, "rootCorrelationKey");
    Objects.requireNonNull(stage, "stage must not be null");
    IntakeOperationKeys.requireValid(stageOperationKey);
    requireHash(requestHash, "requestHash");
    requireThreadId(threadId, "threadId");
    requireIdentifier(agentSessionId, "agentSessionId");
    if (deadlineEpochMillis < 1) {
      throw new IllegalArgumentException("deadlineEpochMillis must be positive");
    }
    Objects.requireNonNull(retryBudget, "retryBudget must not be null");
    Objects.requireNonNull(invocation, "invocation must not be null");
    int expectedAttempts = invocation.permitsExecution() ? 1 : 0;
    if (retryBudget.activityAttemptsRemaining() != expectedAttempts) {
      throw new IllegalArgumentException(
          "retryBudget must match the explicit Activity invocation mode");
    }
    requireStageKey(stage, stageOperationKey);
    requireGraphReceipt(completedGraphExecution, requestHash, commandId, threadId);
    if (stage == IntakeActivityStage.TURN_FINALIZATION && completedGraphExecution == null) {
      throw new IllegalArgumentException("turn finalization requires the completed Graph receipt");
    }
    if (completedGraphExecution != null
        && (stage == IntakeActivityStage.SNAPSHOT_PUBLICATION
            || stage == IntakeActivityStage.INITIATOR_ACCEPTANCE
            || stage == IntakeActivityStage.INITIATOR_REJECTION
            || stage == IntakeActivityStage.CANCELLATION
            || stage == IntakeActivityStage.RESPONDENT_CONFIRMATION)) {
      throw new IllegalArgumentException("completed Graph receipt is invalid for this Activity stage");
    }
    if (terminalFailure != null) {
      if (terminalFailure.stage() != stage
          || !terminalFailure.operationKey().equals(stageOperationKey)) {
        throw new IllegalArgumentException(
            "terminal Activity failure must match the exact stage operation");
      }
      if (invocation.mode() != ActivityInvocationMode.RECONCILE_ONLY) {
        throw new IllegalArgumentException(
            "terminal Activity failure cannot retain execution authority");
      }
    }
  }

  public IntakeActivityExecutionState withGraphExecution(
      GraphExecutionReceipt graphExecutionReceipt) {
    return new IntakeActivityExecutionState(
        schemaVersion,
        commandId,
        rootCorrelationKey,
        stage,
        stageOperationKey,
        requestHash,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        retryBudget,
        invocation,
        Objects.requireNonNull(graphExecutionReceipt, "graphExecutionReceipt must not be null"),
        terminalFailure);
  }

  public IntakeActivityExecutionState withInvocation(ActivityInvocation nextInvocation) {
    Objects.requireNonNull(nextInvocation, "nextInvocation must not be null");
    if (terminalFailure != null && nextInvocation.permitsExecution()) {
      throw new IllegalArgumentException(
          "terminal Activity failure cannot regain execution authority");
    }
    RetryBudget nextBudget =
        new RetryBudget(
            retryBudget.schemaVersion(),
            retryBudget.providerAttemptsRemaining(),
            nextInvocation.permitsExecution() ? 1 : 0,
            retryBudget.repairsRemaining());
    return new IntakeActivityExecutionState(
        schemaVersion,
        commandId,
        rootCorrelationKey,
        stage,
        stageOperationKey,
        requestHash,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        nextBudget,
        nextInvocation,
        completedGraphExecution,
        terminalFailure);
  }

  public IntakeActivityExecutionState withTerminalFailure(String failureType) {
    ActivityInvocation terminalInvocation =
        new ActivityInvocation(
            "intake-activity-invocation.v1", ActivityInvocationMode.RECONCILE_ONLY, 0);
    RetryBudget terminalBudget =
        new RetryBudget(
            retryBudget.schemaVersion(),
            retryBudget.providerAttemptsRemaining(),
            0,
            retryBudget.repairsRemaining());
    return new IntakeActivityExecutionState(
        schemaVersion,
        commandId,
        rootCorrelationKey,
        stage,
        stageOperationKey,
        requestHash,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        terminalBudget,
        terminalInvocation,
        completedGraphExecution,
        new IntakeActivityTerminalFailure(
            "intake-activity-terminal-failure.v1", failureType, stage, stageOperationKey));
  }

  private static void requireGraphReceipt(
      GraphExecutionReceipt receipt, String requestHash, String commandId, String threadId) {
    if (receipt == null) {
      return;
    }
    if (!receipt.operation().operationKey().startsWith("intake.graph.execute:")
        || !receipt.operation().requestHash().equals(requestHash)
        || !receipt.graphExecutionRef().graphCommandId().equals(commandId)
        || !receipt.graphExecutionRef().threadId().equals(threadId)) {
      throw new IllegalArgumentException("completed Graph receipt does not match the Activity state");
    }
  }

  private static RetryBudget invocationBudget(RetryBudget source) {
    Objects.requireNonNull(source, "retryBudget must not be null");
    return new RetryBudget(
        source.schemaVersion(),
        source.providerAttemptsRemaining(),
        source.activityAttemptsRemaining() == 0 ? 0 : 1,
        source.repairsRemaining());
  }

  private static void requireStageKey(IntakeActivityStage stage, String operationKey) {
    String prefix =
        switch (stage) {
          case SNAPSHOT_PUBLICATION -> "intake.snapshot.publish:";
          case GRAPH_EXECUTION -> "intake.graph.execute:";
          case TURN_FINALIZATION -> "intake.turn.finalize:";
          case INITIATOR_ACCEPTANCE -> "intake.initiator.accept:";
          case INITIATOR_REJECTION -> "intake.initiator.reject:";
          case CANCELLATION -> "intake.cancel:";
          case RESPONDENT_CONFIRMATION -> "intake.respondent.confirm:";
        };
    if (!operationKey.startsWith(prefix)) {
      throw new IllegalArgumentException("stageOperationKey does not match the Activity stage");
    }
  }
}
