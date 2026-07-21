package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Exact durable bootstrap message to publish after bilateral authority is asserted. */
public record EpochBootstrapOutbox(
        String outboxId,
        String epochId,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        long fencingToken,
        WriterMode writerMode,
        String caseWorkflowId,
        String roomWorkflowId,
        String workflowType,
        String taskQueue,
        String updateId,
        String payloadJson,
        String payloadSha256,
        OffsetDateTime availableAt) {

    public EpochBootstrapOutbox {
        required(outboxId, "outboxId");
        required(epochId, "epochId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        Objects.requireNonNull(roomType, "roomType must not be null");
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("bootstrap roomType must be INTAKE");
        }
        if (roomEpoch < 0 || fencingToken <= 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative and fencingToken positive");
        }
        if (writerMode != WriterMode.SHADOW) {
            throw new IllegalArgumentException("R1.5 bootstrap writerMode must be SHADOW");
        }
        required(caseWorkflowId, "caseWorkflowId");
        required(roomWorkflowId, "roomWorkflowId");
        required(workflowType, "workflowType");
        required(taskQueue, "taskQueue");
        required(updateId, "updateId");
        required(payloadJson, "payloadJson");
        if (payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadSha256 must be lowercase SHA-256");
        }
        Objects.requireNonNull(availableAt, "availableAt must not be null");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
