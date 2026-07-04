package com.example.dispute.hearing.api;

import com.example.dispute.hearing.application.SubmitHearingRoundCommand;
import jakarta.validation.constraints.Min;

public record SubmitHearingRoundRequest(
        @Min(1) int dossierVersion,
        String statementJson) {
    SubmitHearingRoundCommand toCommand() {
        return new SubmitHearingRoundCommand(dossierVersion, statementJson);
    }
}
