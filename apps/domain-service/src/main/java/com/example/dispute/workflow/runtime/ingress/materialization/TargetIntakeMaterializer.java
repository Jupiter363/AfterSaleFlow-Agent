package com.example.dispute.workflow.runtime.ingress.materialization;

import com.example.dispute.workflow.runtime.ingress.TargetIntakeIngressReceipt;
import com.example.dispute.workflow.runtime.ingress.TargetIntakeMessageRequest;

/** Creates the durable target-lane material that a case command is allowed to reference. */
public interface TargetIntakeMaterializer {

    MaterializedIntake materialize(TargetIntakeMessageRequest request);

    record MaterializedIntake(
            String commandId,
            String runId,
            com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef eventPayload,
            java.time.Instant admittedAt,
            java.time.Instant deadlineAt) {

        public MaterializedIntake {
            if (!TargetIntakeIngressReceipt.runIdForCommand(commandId).equals(runId)) {
                throw new IllegalArgumentException("runId does not match commandId");
            }
            if (eventPayload == null || admittedAt == null || deadlineAt == null) {
                throw new IllegalArgumentException(
                        "eventPayload, admittedAt and deadlineAt must not be null");
            }
            deadlineAt = deadlineAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }
}
