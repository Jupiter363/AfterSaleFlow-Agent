package com.example.dispute.caseintake.application;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.time.OffsetDateTime;
import java.util.List;

public record CaseView(
        String id,
        String orderId,
        String afterSaleId,
        String userId,
        String merchantId,
        String caseType,
        String disputeType,
        CaseStatus caseStatus,
        RouteType routeType,
        RiskLevel riskLevel,
        String title,
        String description,
        boolean potentialDispute,
        List<String> missingSlots,
        boolean agentDegraded,
        CaseSourceType sourceType,
        String sourceSystem,
        String externalCaseReference,
        String currentRoom,
        OffsetDateTime deadlineAt,
        String pendingAction,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt) {}
