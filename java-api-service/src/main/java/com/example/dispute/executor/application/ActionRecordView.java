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
        OffsetDateTime executionTime,
        OffsetDateTime createdAt) {}
