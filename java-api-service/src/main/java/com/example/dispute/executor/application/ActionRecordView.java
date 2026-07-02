package com.example.dispute.executor.application;

import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record ActionRecordView(
        String actionRecordId,
        String caseId,
        String planId,
        String approvalRecordId,
        String actionType,
        RiskLevel riskLevel,
        String idempotencyKey,
        String approvedBy,
        String executedBy,
        ExecutionStatus executionStatus,
        int attemptCount,
        JsonNode request,
        JsonNode result,
        String errorCode,
        String errorMessage,
        String reviewPacketId,
        String actionSnapshotHash,
        JsonNode evidenceRefs,
        JsonNode ruleRefs,
        JsonNode agentRunRefs,
        String externalResultRef,
        OffsetDateTime executionTime,
        OffsetDateTime createdAt) {

    public ActionRecordView(
            String actionRecordId,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            ExecutionStatus executionStatus,
            int attemptCount,
            JsonNode request,
            JsonNode result,
            String errorCode,
            String errorMessage,
            OffsetDateTime executionTime,
            OffsetDateTime createdAt) {
        this(
                actionRecordId,
                caseId,
                planId,
                approvalRecordId,
                actionType,
                riskLevel,
                idempotencyKey,
                approvedBy,
                executedBy,
                executionStatus,
                attemptCount,
                request,
                result,
                errorCode,
                errorMessage,
                null,
                null,
                null,
                null,
                null,
                null,
                executionTime,
                createdAt);
    }
}
