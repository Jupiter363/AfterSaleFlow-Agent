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
        RiskLevel riskLevel) {

    public SimulatedExternalDispute {
        requireText(sourceSystem, 64, "sourceSystem");
        requireText(externalCaseReference, 128, "externalCaseReference");
        requireText(orderReference, 64, "orderReference");
        optionalText(afterSalesReference, 64, "afterSalesReference");
        optionalText(logisticsReference, 64, "logisticsReference");
        requireText(userId, 128, "userId");
        requireText(merchantId, 128, "merchantId");
        DemoImportActors.requireImportedParties(userId, merchantId);
        requireText(initiatorRole, 32, "initiatorRole");
        if (!"USER".equals(initiatorRole) && !"MERCHANT".equals(initiatorRole)) {
            throw new IllegalArgumentException(
                    "initiatorRole must be USER or MERCHANT");
        }
        requireText(disputeType, 64, "disputeType");
        requireText(title, 256, "title");
        requireText(description, 2000, "description");
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
    }

    private static void requireText(
            String value,
            int maxLength,
            String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
    }

    private static void optionalText(
            String value,
            int maxLength,
            String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
    }
}
