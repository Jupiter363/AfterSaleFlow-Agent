package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

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
    RetryBudget retryBudget) {

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
    requireStageKey(stage, stageOperationKey);
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
