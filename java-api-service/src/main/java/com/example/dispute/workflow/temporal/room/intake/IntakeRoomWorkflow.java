package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface IntakeRoomWorkflow {

  @WorkflowMethod(name = IntakeWorkflowProtocol.WORKFLOW_TYPE)
  IntakeRoomSnapshot run(IntakeRoomStart start);

  @SignalMethod(name = IntakeWorkflowProtocol.COMMAND_SIGNAL)
  void commandAccepted(IntakeWorkflowCommand command);

  @SignalMethod(name = IntakeWorkflowProtocol.DOMAIN_RECEIPT_SIGNAL)
  void domainReceiptCommitted(IntakeDomainReceipt receipt);

  @QueryMethod(name = IntakeWorkflowProtocol.STATE_QUERY)
  IntakeRoomSnapshot state();

  @QueryMethod(name = IntakeWorkflowProtocol.LAST_DECISION_QUERY)
  IntakeCommandDecision lastCommandDecision();
}
