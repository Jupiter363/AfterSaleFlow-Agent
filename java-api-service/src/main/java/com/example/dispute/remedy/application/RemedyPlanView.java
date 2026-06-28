package com.example.dispute.remedy.application;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record RemedyPlanView(
        String id,
        String caseId,
        String adjudicationDraftId,
        int planVersion,
        RouteType sourceRoute,
        String planStatus,
        RiskLevel riskLevel,
        BigDecimal totalAmount,
        String currency,
        List<PlannedRemedyAction> actions,
        List<String> preconditions,
        List<String> notificationPlan,
        boolean requiresHumanReview,
        OffsetDateTime createdAt) {}
