package com.example.dispute.router.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

public record FlowConclusionView(
        String conclusionType,
        String conclusionStatus,
        String conclusionCode,
        String summary,
        List<String> recommendedActions,
        String policyRuleId,
        Integer policyVersion,
        RiskLevel riskLevel,
        boolean requiresRemedyPlanning,
        boolean requiresHumanReview) {}
