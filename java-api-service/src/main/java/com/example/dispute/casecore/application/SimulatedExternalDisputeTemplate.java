package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;

public record SimulatedExternalDisputeTemplate(
        int templateNo,
        String title,
        String description,
        String disputeType,
        RiskLevel riskLevel,
        String requestedResolution,
        BigDecimal requestedAmount,
        String requestedItems,
        String requestReason,
        String respondentAttitude,
        String respondentPosition) {

    public SimulatedExternalDisputeTemplate {
        if (templateNo < 1 || templateNo > 20) {
            throw new IllegalArgumentException("templateNo must be between 1 and 20");
        }
        requireText(title, "title");
        requireText(description, "description");
        requireText(disputeType, "disputeType");
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
        requireText(requestedResolution, "requestedResolution");
        requireText(requestedItems, "requestedItems");
        requireText(requestReason, "requestReason");
        requireText(respondentAttitude, "respondentAttitude");
        requireText(respondentPosition, "respondentPosition");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
