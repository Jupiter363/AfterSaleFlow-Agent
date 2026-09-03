package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;

/** Explicit graph stage used only by an explicitly assembled Intake Activity adapter. */
@FunctionalInterface
public interface IntakeGraphExecutionPort {

    GraphExecutionReceipt execute(GraphExecutionRequest request);
}
