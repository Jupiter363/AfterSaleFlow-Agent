package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.RiskLevel;

public record SimulatedExternalDispute(
        String sourceSystem,
        String externalCaseReference,
        String orderReference,
        String afterSalesReference,
        String logisticsReference,
        String userId,
        String merchantId,
        String initiatorRole,
        String disputeType,
        String title,
        String description,
        RiskLevel riskLevel) {}
