package com.example.dispute.workflow.temporal.room.intake;

import java.util.Objects;

/** Durable non-retryable failure bound to one exact Activity stage operation. */
public record IntakeActivityTerminalFailure(
    String schemaVersion,
    String failureType,
    IntakeActivityStage stage,
    String operationKey) {

  public IntakeActivityTerminalFailure {
    if (!"intake-activity-terminal-failure.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-activity-terminal-failure.v1");
    }
    if (failureType == null
        || failureType.isBlank()
        || failureType.length() > 256
        || failureType.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("failureType must be bounded non-control text");
    }
    Objects.requireNonNull(stage, "stage must not be null");
    IntakeOperationKeys.requireValid(operationKey);
    if (IntakeActivityFailureTypes.isRetryable(failureType)) {
      throw new IllegalArgumentException("terminal Activity failure must not be retryable");
    }
  }
}
