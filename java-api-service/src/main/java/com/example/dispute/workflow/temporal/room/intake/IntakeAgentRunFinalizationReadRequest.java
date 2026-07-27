package com.example.dispute.workflow.temporal.room.intake;

import java.util.Objects;

/** Exact lookup key for normal completion and cancellation reconciliation. */
public record IntakeAgentRunFinalizationReadRequest(
    String schemaVersion,
    Mode mode,
    IntakeWorkflowCommand command,
    IntakeAgentRunChildState childState) {

  public IntakeAgentRunFinalizationReadRequest {
    if (!"intake-agent-run-finalization-read-request.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-read-request.v1");
    }
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(childState, "childState must not be null");
    IntakeCommandExecutionContext execution = command.executionContext();
    if (command.commandType() != IntakeCommandType.INTAKE_MESSAGE
        || execution == null
        || !execution.isTargetAgentRun()) {
      throw new IllegalArgumentException("receipt lookup requires a target Intake message");
    }
    childState.requireMatches(command, execution.targetAgentRun());
  }

  public enum Mode {
    AFTER_CHILD_COMPLETION,
    CANCELLATION_RECONCILIATION
  }
}
