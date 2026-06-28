package com.example.dispute.executor.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Map;

public record ExecutableAction(
        String actionType,
        String idempotencyKey,
        RiskLevel riskLevel,
        Map<String, Object> parameters) {

    public ExecutableAction {
        parameters = Map.copyOf(parameters);
    }
}
