package com.example.dispute.hearing.application;

public record CompleteHearingRoundCommand(
        int dossierVersion, String summaryJson, boolean factsSufficient) {
    public CompleteHearingRoundCommand {
        if (dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (summaryJson == null || summaryJson.isBlank()) {
            throw new IllegalArgumentException("summaryJson must not be blank");
        }
    }
}
