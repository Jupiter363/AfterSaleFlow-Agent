package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CaseProcessWorkflow {

  @WorkflowMethod(name = CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE)
  void run(CaseProcessCarryState carryState);

  @UpdateMethod(name = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
  void acceptCommand(CaseCommandRef command);

  @UpdateValidatorMethod(updateName = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
  void validateAcceptCommand(CaseCommandRef command);

  @UpdateMethod(name = CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
  ProvisionRoomEpochReceipt provisionRoomEpoch(ProvisionRoomEpoch request);

  @UpdateValidatorMethod(updateName = CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
  void validateProvisionRoomEpoch(ProvisionRoomEpoch request);

  @UpdateMethod(
      name = CaseProcessWorkflowProtocol.RECOVER_INTAKE_PROJECTION_COMPLETION_UPDATE)
  default CaseProcessIntakeProjectionRecoveryResult recoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Intake projection completion recovery is not supported by this CaseProcess workflow");
  }

  @UpdateValidatorMethod(
      updateName = CaseProcessWorkflowProtocol.RECOVER_INTAKE_PROJECTION_COMPLETION_UPDATE)
  default void validateRecoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Intake projection completion recovery is not supported by this CaseProcess workflow");
  }

  @SignalMethod(name = CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL)
  void domainEventCommitted(CaseDomainEventRef event);

  @SignalMethod(name = CaseProcessWorkflowProtocol.TARGET_ROOM_PROGRESS_SIGNAL)
  void targetRoomProgressed(TargetRoomProgressReceipt receipt);

  @SignalMethod(name = CaseProcessWorkflowProtocol.TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL)
  default void targetIntakeCommandTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit authority) {
    throw new UnsupportedOperationException(
        "target Intake terminal-no-commit convergence is not supported");
  }

  @SignalMethod(name = CaseProcessWorkflowProtocol.RETRY_SEQUENCE_GAP_SIGNAL)
  void retrySequenceGap();

  @SignalMethod(name = CaseProcessWorkflowProtocol.REQUEST_CONTINUE_AS_NEW_SIGNAL)
  void requestContinueAsNew();

  @QueryMethod(name = CaseProcessWorkflowProtocol.PROCESS_STATE_QUERY)
  CaseProcessSnapshot state();

  @QueryMethod(name = CaseProcessWorkflowProtocol.ROOM_PROVISIONING_RECEIPT_QUERY)
  ProvisionRoomEpochReceipt provisioningReceipt();

  @QueryMethod(name = CaseProcessWorkflowProtocol.ROOM_PROVISIONING_COMMITMENT_QUERY)
  ProvisioningCommitment provisioningCommitment();
}
