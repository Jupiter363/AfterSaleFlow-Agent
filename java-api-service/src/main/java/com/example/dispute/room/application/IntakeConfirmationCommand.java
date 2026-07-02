package com.example.dispute.room.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

public record IntakeConfirmationCommand(
        boolean admissible,
        String disputeType,
        RiskLevel riskLevel,
        String confirmationNote) {

    public IntakeConfirmationCommand {
        requireText(disputeType, "disputeType");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        requireText(confirmationNote, "confirmationNote");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
