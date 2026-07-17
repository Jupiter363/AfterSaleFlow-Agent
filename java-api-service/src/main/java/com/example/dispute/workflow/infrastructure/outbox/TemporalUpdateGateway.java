package com.example.dispute.workflow.infrastructure.outbox;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import java.util.Objects;

public interface TemporalUpdateGateway {

    DeliveryReceipt deliver(UpdateWithStartRequest request);

    record UpdateWithStartRequest(
            String workflowId,
            String workflowType,
            String taskQueue,
            String updateId,
            CaseCommandRef command) {

        public UpdateWithStartRequest {
            requireText(workflowId, "workflowId");
            requireText(workflowType, "workflowType");
            requireText(taskQueue, "taskQueue");
            requireText(updateId, "updateId");
            Objects.requireNonNull(command, "command must not be null");
            if (!updateId.equals(command.commandId())) {
                throw new IllegalArgumentException(
                        "updateId must match the durable command id");
            }
        }
    }

    record DeliveryReceipt(String temporalRunId) {

        public DeliveryReceipt {
            requireText(temporalRunId, "temporalRunId");
            if (temporalRunId.length() > 128) {
                throw new IllegalArgumentException("temporalRunId is too long");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
