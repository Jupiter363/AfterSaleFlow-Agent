package com.example.dispute.workflow.application.authority.payload;

import java.time.OffsetDateTime;

/** Transactional outbox row bound to the accepted case-command authority. */
public record IntakeCommandOutboxBinding(
        String outboxId,
        String caseWorkflowId,
        String workflowType,
        String taskQueue,
        String updateId,
        OffsetDateTime availableAt) {

    public IntakeCommandOutboxBinding {
        identifier(outboxId, "outboxId", 64);
        identifier(caseWorkflowId, "caseWorkflowId", 128);
        identifier(workflowType, "workflowType", 128);
        identifier(taskQueue, "taskQueue", 128);
        identifier(updateId, "updateId", 128);
        if (availableAt == null) {
            throw new IllegalArgumentException("availableAt must not be null");
        }
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
