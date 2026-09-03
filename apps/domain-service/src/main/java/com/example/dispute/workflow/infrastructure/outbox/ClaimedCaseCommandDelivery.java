package com.example.dispute.workflow.infrastructure.outbox;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import java.time.OffsetDateTime;
import java.util.Objects;

public record ClaimedCaseCommandDelivery(
        String outboxId,
        String caseCommandId,
        DeliveryKind deliveryKind,
        String workflowId,
        String workflowType,
        String taskQueue,
        String updateId,
        CaseCommandRef command,
        int attemptCount,
        String leaseToken,
        OffsetDateTime leaseExpiresAt) {

    public ClaimedCaseCommandDelivery {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(caseCommandId, "caseCommandId must not be null");
        Objects.requireNonNull(deliveryKind, "deliveryKind must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowType, "workflowType must not be null");
        Objects.requireNonNull(taskQueue, "taskQueue must not be null");
        Objects.requireNonNull(updateId, "updateId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
    }

    public TemporalUpdateGateway.UpdateWithStartRequest toGatewayRequest() {
        return new TemporalUpdateGateway.UpdateWithStartRequest(
                workflowId, workflowType, taskQueue, updateId, command);
    }
}
