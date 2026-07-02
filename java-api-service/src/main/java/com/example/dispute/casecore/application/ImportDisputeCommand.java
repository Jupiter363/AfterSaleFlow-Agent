package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import java.time.OffsetDateTime;
import java.util.Objects;

public record ImportDisputeCommand(
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
        RiskLevel riskLevel,
        CaseStatus caseStatus,
        String currentRoom,
        OffsetDateTime currentDeadlineAt) {

    public ImportDisputeCommand {
        requireText(sourceSystem, "sourceSystem");
        requireText(externalCaseReference, "externalCaseReference");
        requireText(orderReference, "orderReference");
        requireText(userId, "userId");
        requireText(merchantId, "merchantId");
        requireText(initiatorRole, "initiatorRole");
        requireText(disputeType, "disputeType");
        requireText(title, "title");
        requireText(description, "description");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(caseStatus, "caseStatus must not be null");
        requireText(currentRoom, "currentRoom");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
