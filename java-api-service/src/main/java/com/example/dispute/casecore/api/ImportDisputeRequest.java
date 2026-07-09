package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ImportDisputeRequest(
        @NotBlank String sourceSystem,
        @NotBlank String externalCaseReference,
        @NotBlank String orderReference,
        String afterSalesReference,
        String logisticsReference,
        @NotBlank String userId,
        @NotBlank String merchantId,
        @NotBlank @Pattern(regexp = "USER|MERCHANT") String initiatorRole,
        @NotBlank String disputeType,
        @NotBlank String title,
        @NotBlank String description,
        @JsonProperty("requested_outcome_hint")
                @Pattern(
                        regexp =
                                "REFUND|RETURN_REFUND|RESHIP|REPLACE_OR_REPAIR|COMPENSATION|CANCEL_ORDER|VERIFY_OR_EXPLAIN_ONLY|OTHER|UNKNOWN")
                String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed")
                @Valid IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed")
                @Valid IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed,
        @NotNull RiskLevel riskLevel) {

    ImportDisputeCommand toCommand() {
        return new ImportDisputeCommand(
                sourceSystem,
                externalCaseReference,
                orderReference,
                afterSalesReference,
                logisticsReference,
                userId,
                merchantId,
                initiatorRole,
                disputeType,
                title,
                description,
                riskLevel,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                requestedOutcomeHint,
                claimResolutionSeed,
                respondentAttitudeSeed);
    }
}
