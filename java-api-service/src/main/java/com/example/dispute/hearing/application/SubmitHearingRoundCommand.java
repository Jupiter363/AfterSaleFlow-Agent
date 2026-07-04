package com.example.dispute.hearing.application;

public record SubmitHearingRoundCommand(
        int dossierVersion,
        String statementJson) {
    public SubmitHearingRoundCommand {
        if (dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (statementJson == null || statementJson.isBlank()) {
            statementJson = "{}";
        }
    }
}
