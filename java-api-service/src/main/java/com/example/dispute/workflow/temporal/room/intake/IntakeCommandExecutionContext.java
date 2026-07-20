package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import java.util.Objects;

/** Reference-only metadata that opts an admitted command into the Temporal Activity path. */
public record IntakeCommandExecutionContext(
    String schemaVersion,
    String threadId,
    String agentSessionId,
    long deadlineEpochMillis,
    RetryBudget retryBudget,
    BranchOperation branchOperation) {

  public IntakeCommandExecutionContext {
    if (!"intake-command-execution-context.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-command-execution-context.v1");
    }
    requireThreadId(threadId, "threadId");
    requireIdentifier(agentSessionId, "agentSessionId");
    if (deadlineEpochMillis < 1) {
      throw new IllegalArgumentException("deadlineEpochMillis must be positive");
    }
    Objects.requireNonNull(retryBudget, "retryBudget must not be null");
  }

  void requireCompatible(IntakeCommandType commandType, IntakeParty party) {
    BranchOperation expected =
        switch (commandType) {
          case INTAKE_MESSAGE -> null;
          case INTAKE_CANCEL -> BranchOperation.CANCEL;
          case INTAKE_CONFIRM ->
              party == IntakeParty.RESPONDENT ? BranchOperation.RESPONDENT_CONFIRM : null;
        };
    if (commandType == IntakeCommandType.INTAKE_CONFIRM && party == IntakeParty.INITIATOR) {
      if (branchOperation != BranchOperation.INITIATOR_ACCEPT
          && branchOperation != BranchOperation.INITIATOR_REJECT) {
        throw new IllegalArgumentException(
            "initiator confirmation requires an accept or reject branch operation");
      }
      return;
    }
    if (branchOperation != expected) {
      throw new IllegalArgumentException(
          commandType + " from " + party + " has an incompatible branch operation");
    }
  }
}
