package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record IntakeLobbySeed(
        @JsonProperty("order_reference") String orderReference,
        @JsonProperty("after_sales_reference") String afterSalesReference,
        @JsonProperty("logistics_reference") String logisticsReference,
        @JsonProperty("initiator_role") String initiatorRole,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("requested_outcome_hint") String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed") ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed") RespondentAttitudeSeed respondentAttitudeSeed) {

    public IntakeLobbySeed(
            String orderReference,
            String afterSalesReference,
            String logisticsReference,
            String initiatorRole,
            String rawText,
            String requestedOutcomeHint) {
        this(
                orderReference,
                afterSalesReference,
                logisticsReference,
                initiatorRole,
                rawText,
                requestedOutcomeHint,
                null,
                null);
    }

    public IntakeLobbySeed {
        if ((requestedOutcomeHint == null || requestedOutcomeHint.isBlank())
                && claimResolutionSeed != null
                && claimResolutionSeed.requestedResolution() != null
                && !claimResolutionSeed.requestedResolution().isBlank()) {
            requestedOutcomeHint = claimResolutionSeed.requestedResolution();
        }
    }

    public record ClaimResolutionSeed(
            @JsonProperty("initiator_role") @Pattern(regexp = "USER|MERCHANT") String initiatorRole,
            @JsonProperty("requested_resolution")
                    @Pattern(
                            regexp =
                                    "REFUND|RETURN_REFUND|RESHIP|REPLACE_OR_REPAIR|COMPENSATION|CANCEL_ORDER|VERIFY_OR_EXPLAIN_ONLY|OTHER|UNKNOWN")
                    String requestedResolution,
            @JsonProperty("requested_amount") @PositiveOrZero @Digits(integer = 12, fraction = 2)
                    BigDecimal requestedAmount,
            @JsonProperty("requested_items") @Size(max = 512) String requestedItems,
            @JsonProperty("request_reason") @Size(max = 4000) String requestReason,
            @JsonProperty("original_statement") @Size(max = 4000) String originalStatement) {}

    public record RespondentAttitudeSeed(
            @JsonProperty("respondent_role") @Pattern(regexp = "USER|MERCHANT") String respondentRole,
            @JsonProperty("attitude")
                    @Pattern(
                            regexp =
                                    "NOT_RESPONDED|AGREE|PARTIALLY_AGREE|DISAGREE|ALTERNATIVE_PROPOSED|NEED_MORE_INFO|PLATFORM_UNKNOWN")
                    String attitude,
            @JsonProperty("position") @Size(max = 4000) String position,
            @JsonProperty("source") @Size(max = 128) String source,
            @JsonProperty("confidence") @DecimalMin("0.0") @DecimalMax("1.0") Double confidence) {}
}
