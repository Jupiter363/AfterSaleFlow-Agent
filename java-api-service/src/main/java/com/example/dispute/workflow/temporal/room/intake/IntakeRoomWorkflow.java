package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface IntakeRoomWorkflow {

  @WorkflowMethod(name = IntakeWorkflowProtocol.WORKFLOW_TYPE)
  IntakeRoomSnapshot run(IntakeRoomStart start);

  @SignalMethod(name = IntakeWorkflowProtocol.COMMAND_SIGNAL)
  void commandAccepted(IntakeWorkflowCommand command);

  @SignalMethod(name = IntakeWorkflowProtocol.DOMAIN_EVENT_SIGNAL)
  void domainEventCommitted(IntakeDomainEventRef event);

  @SignalMethod(name = IntakeWorkflowProtocol.TARGET_SOURCE_EVENT_SIGNAL)
  void targetSourceEventObserved(TargetIntakeSourceEventRef event);

  @SignalMethod(name = IntakeWorkflowProtocol.REQUEST_CONTINUE_AS_NEW_SIGNAL)
  void requestContinueAsNew();

  @UpdateMethod(name = IntakeWorkflowProtocol.RECOVER_TARGET_FINALIZATION_UPDATE)
  default IntakeAgentRunFinalizationRecoveryResult recoverTargetFinalization(
      IntakeAgentRunFinalizationRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Target finalization recovery is not supported by this Intake Room workflow");
  }

  @UpdateValidatorMethod(updateName = IntakeWorkflowProtocol.RECOVER_TARGET_FINALIZATION_UPDATE)
  default void validateRecoverTargetFinalization(IntakeAgentRunFinalizationRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Target finalization recovery is not supported by this Intake Room workflow");
  }

  @UpdateMethod(name = IntakeWorkflowProtocol.RECOVER_TERMINAL_NO_COMMIT_UPDATE)
  default IntakeTerminalNoCommitRecoveryResult recoverTerminalNoCommit(
      IntakeTerminalNoCommitRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Terminal-no-commit recovery is not supported by this Intake Room workflow");
  }

  @UpdateValidatorMethod(updateName = IntakeWorkflowProtocol.RECOVER_TERMINAL_NO_COMMIT_UPDATE)
  default void validateRecoverTerminalNoCommit(IntakeTerminalNoCommitRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Terminal-no-commit recovery is not supported by this Intake Room workflow");
  }

  @QueryMethod(name = IntakeWorkflowProtocol.STATE_QUERY)
  IntakeRoomSnapshot state();

  @QueryMethod(name = IntakeWorkflowProtocol.LAST_DECISION_QUERY)
  IntakeCommandDecision lastCommandDecision();
}
