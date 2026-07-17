package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;

public record RoomControlStart(
        String schemaVersion,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        String parentWorkflowId,
        long firstCommandSequence,
        long firstCaseEventSequence) {

    public RoomControlStart {
        if (!"room-control-start.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be room-control-start.v1");
        }
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        if (roomType == null) {
            throw new IllegalArgumentException("roomType must not be null");
        }
        if (roomEpoch < 0) {
            throw new IllegalArgumentException("roomEpoch must not be negative");
        }
        requireText(parentWorkflowId, "parentWorkflowId");
        if (firstCommandSequence < 1 || firstCaseEventSequence < 1) {
            throw new IllegalArgumentException("initial sequence must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
