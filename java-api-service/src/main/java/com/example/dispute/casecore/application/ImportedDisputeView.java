package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import java.time.OffsetDateTime;

public record ImportedDisputeView(
        String id,
        String orderId,
        String afterSaleId,
        String logisticsId,
        String userId,
        String merchantId,
        String disputeType,
        String sourceType,
        String sourceSystem,
        String externalCaseReference,
        RiskLevel riskLevel,
        String title,
        String description,
        CaseStatus caseStatus,
        String currentRoom,
        OffsetDateTime currentDeadlineAt,
        String pendingAction,
        String initiatorRole) {}
