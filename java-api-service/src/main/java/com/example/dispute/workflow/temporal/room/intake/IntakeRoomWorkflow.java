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

  @SignalMethod(name = IntakeWorkflowProtocol.DOMAIN_EVENT_SIGNAL)
  void domainEventCommitted(IntakeDomainEventRef event);

  @SignalMethod(name = IntakeWorkflowProtocol.REQUEST_CONTINUE_AS_NEW_SIGNAL)
  void requestContinueAsNew();

  @QueryMethod(name = IntakeWorkflowProtocol.STATE_QUERY)
  IntakeRoomSnapshot state();

  @QueryMethod(name = IntakeWorkflowProtocol.LAST_DECISION_QUERY)
  IntakeCommandDecision lastCommandDecision();
}
