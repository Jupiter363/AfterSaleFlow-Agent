package com.example.dispute.workflow.temporal.room.intake;

import java.util.Objects;

/** Exact lookup key for normal completion and cancellation reconciliation. */
public record IntakeAgentRunFinalizationReadRequest(
    String schemaVersion,
    Mode mode,
    IntakeWorkflowCommand command,
    IntakeAgentRunChildState childState) {

  public static final String EXACT_SCHEMA_VERSION = "intake-agent-run-finalization-read-request.v1";
  public static final String WINNING_ATTEMPT_SCHEMA_VERSION =
      "intake-agent-run-finalization-read-request.v2";

  public IntakeAgentRunFinalizationReadRequest {
    if (!EXACT_SCHEMA_VERSION.equals(schemaVersion)
        && !WINNING_ATTEMPT_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-read-request.v1 or "
              + "intake-agent-run-finalization-read-request.v2");
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

  public static IntakeAgentRunFinalizationReadRequest exact(
      Mode mode, IntakeWorkflowCommand command, IntakeAgentRunChildState childState) {
    return new IntakeAgentRunFinalizationReadRequest(
        EXACT_SCHEMA_VERSION, mode, command, childState);
  }

  public static IntakeAgentRunFinalizationReadRequest winningAttempt(
      Mode mode, IntakeWorkflowCommand command, IntakeAgentRunChildState childState) {
    return new IntakeAgentRunFinalizationReadRequest(
        WINNING_ATTEMPT_SCHEMA_VERSION, mode, command, childState);
  }

  public boolean allowsWinningAttempt() {
    return WINNING_ATTEMPT_SCHEMA_VERSION.equals(schemaVersion);
  }

  public enum Mode {
    AFTER_CHILD_COMPLETION,
    CANCELLATION_RECONCILIATION
  }
}
