package com.example.dispute.workflow.temporal.room.intake;

public final class IntakeWorkflowProtocol {

  public static final String WORKFLOW_TYPE = "IntakeRoomWorkflow";
  public static final String COMMAND_SIGNAL = "intakeCommandAccepted";
  public static final String DOMAIN_EVENT_SIGNAL = "intakeDomainEventCommitted";
  public static final String TARGET_SOURCE_EVENT_SIGNAL = "intakeTargetSourceEventObserved";
  public static final String REQUEST_CONTINUE_AS_NEW_SIGNAL = "intakeRequestContinueAsNew";
  public static final String STATE_QUERY = "intakeState";
  public static final String LAST_DECISION_QUERY = "intakeLastCommandDecision";

  private IntakeWorkflowProtocol() {}
}
