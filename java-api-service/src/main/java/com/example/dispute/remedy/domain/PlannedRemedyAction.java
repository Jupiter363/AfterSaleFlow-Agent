package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;
import java.util.Map;

public record PlannedRemedyAction(
        String actionType,
        Map<String, Object> parameters,
        String idempotencyKey,
        List<String> preconditions,
        RiskLevel riskLevel,
        boolean requiresApproval) {

    public PlannedRemedyAction {
        parameters = Map.copyOf(parameters);
        preconditions = List.copyOf(preconditions);
    }
}
