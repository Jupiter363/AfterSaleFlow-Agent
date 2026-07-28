package com.example.dispute.workflow.targete2e.ingress.materialization;

import com.example.dispute.workflow.targete2e.ingress.TargetIntakeMessageRequest;

/** Creates the durable target-lane material that a case command is allowed to reference. */
public interface TargetIntakeMaterializer {

    MaterializedIntake materialize(TargetIntakeMessageRequest request);

    record MaterializedIntake(
            String commandId,
            com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef eventPayload,
            java.time.Instant admittedAt) {}
}
