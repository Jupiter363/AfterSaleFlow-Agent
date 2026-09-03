package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Read-only recovery boundary for a formal receipt already committed by AgentRun V2. */
@ActivityInterface
public interface IntakeAgentRunFinalizationReadActivities {

  @ActivityMethod(name = "ReadCommittedIntakeAgentRunFinalization")
  IntakeAgentRunFinalizationReadResult readFinalization(
      IntakeAgentRunFinalizationReadRequest request);
}
