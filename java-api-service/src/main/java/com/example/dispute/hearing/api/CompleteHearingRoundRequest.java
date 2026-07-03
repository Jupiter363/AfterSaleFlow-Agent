package com.example.dispute.hearing.api;

import com.example.dispute.hearing.application.CompleteHearingRoundCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CompleteHearingRoundRequest(
        @Min(1) int dossierVersion,
        @NotBlank String summaryJson,
        boolean factsSufficient) {
    CompleteHearingRoundCommand toCommand() {
        return new CompleteHearingRoundCommand(
                dossierVersion, summaryJson, factsSufficient);
    }
}
