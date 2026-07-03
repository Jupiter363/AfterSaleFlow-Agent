package com.example.dispute.hearing.application;

public record SettlementProposalCommand(String proposalText, String proposalJson) {
    public SettlementProposalCommand {
        if (proposalText == null || proposalText.isBlank()) {
            throw new IllegalArgumentException("proposalText must not be blank");
        }
        if (proposalJson == null || proposalJson.isBlank()) {
            throw new IllegalArgumentException("proposalJson must not be blank");
        }
    }
}
