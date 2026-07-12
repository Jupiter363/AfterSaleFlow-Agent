package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntakeInitialCaseFacts(
        @JsonProperty("order_reference") String orderReference,
        @JsonProperty("after_sales_reference") String afterSalesReference,
        @JsonProperty("logistics_reference") String logisticsReference,
        @JsonProperty("initiator_role") String initiatorRole,
        @JsonProperty("requested_outcome_hint") String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed")
                IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed")
                IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed) {

    public static IntakeInitialCaseFacts from(IntakeLobbySeed seed) {
        return new IntakeInitialCaseFacts(
                seed.orderReference(),
                seed.afterSalesReference(),
                seed.logisticsReference(),
                seed.initiatorRole(),
                seed.requestedOutcomeHint(),
                seed.claimResolutionSeed(),
                seed.respondentAttitudeSeed());
    }
}
