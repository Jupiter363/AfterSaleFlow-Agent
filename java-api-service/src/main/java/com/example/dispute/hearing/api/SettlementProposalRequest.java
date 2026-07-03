package com.example.dispute.hearing.api;

import com.example.dispute.hearing.application.SettlementProposalCommand;
import jakarta.validation.constraints.NotBlank;

public record SettlementProposalRequest(
        @NotBlank String proposalText,
        @NotBlank String proposalJson) {
    SettlementProposalCommand toCommand() {
        return new SettlementProposalCommand(proposalText, proposalJson);
    }
}
