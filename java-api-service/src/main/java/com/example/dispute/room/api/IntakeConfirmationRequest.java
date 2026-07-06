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
                admissible, disputeType, riskLevel, normalizedConfirmationNote());
    }

    private String normalizedConfirmationNote() {
        if (confirmationNote == null || confirmationNote.isBlank()) {
            return "确认发起并上报，进入证据书记官室";
        }
        return confirmationNote;
    }
}
