package com.example.dispute.tool.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

public record ToolDefinition(
        String actionType,
        String toolName,
        String operation,
        String displayName,
        String description,
        RiskLevel riskLevel,
        boolean simulated,
        boolean requiresApprovedPlan) {

    public ToolDefinition {
        actionType = requireText(actionType, "actionType");
        toolName = requireText(toolName, "toolName");
        operation = requireText(operation, "operation");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
