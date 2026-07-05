package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntakeLobbySeed(
        @JsonProperty("order_reference") String orderReference,
        @JsonProperty("after_sales_reference") String afterSalesReference,
        @JsonProperty("logistics_reference") String logisticsReference,
        @JsonProperty("initiator_role") String initiatorRole,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("requested_outcome_hint") String requestedOutcomeHint) {}
