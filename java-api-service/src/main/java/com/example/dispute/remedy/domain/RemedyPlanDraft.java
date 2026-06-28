package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

public record RemedyPlanDraft(
        String sourceConclusionCode,
        String sourceDraftId,
        RiskLevel riskLevel,
        List<PlannedRemedyAction> actions,
        List<String> preconditions,
        List<String> notificationPlan,
        boolean requiresHumanReview) {

    public RemedyPlanDraft {
        actions = List.copyOf(actions);
        preconditions = List.copyOf(preconditions);
        notificationPlan = List.copyOf(notificationPlan);
    }
}
