package com.example.dispute.room.api;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IntakeConfirmationRequest(
        boolean admissible,
        @NotBlank String disputeType,
        @NotNull RiskLevel riskLevel,
        String confirmationNote) {

    IntakeConfirmationCommand toCommand() {
        return new IntakeConfirmationCommand(
                admissible, disputeType, riskLevel, confirmationNote);
    }
}
