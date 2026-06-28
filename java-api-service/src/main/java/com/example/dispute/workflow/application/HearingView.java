package com.example.dispute.workflow.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record HearingView(
        String hearingId,
        String caseId,
        String workflowId,
        String status,
        String currentNode,
        int roundNo,
        BigDecimal confidence,
        boolean manualRequired,
        String pendingRequestsJson,
        OffsetDateTime waitingUntil,
        OffsetDateTime completedAt,
        String latestDraftId) {}
