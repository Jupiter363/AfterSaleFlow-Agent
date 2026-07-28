package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Java-minted human decision fact. Graph output is deliberately absent from this authority. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TargetReviewHumanDecisionReceipt(
    String schemaVersion, String decisionAuthority, String decisionRecordId, String decisionRecordHash,
    OutcomeReviewDecisionReceipt outcomeReceipt) {
  public static final String SCHEMA_VERSION = "target-e2e-review-human-decision-receipt.v1";
  public static final String DECISION_AUTHORITY = "JAVA_HUMAN";
  public TargetReviewHumanDecisionReceipt {
    if (!SCHEMA_VERSION.equals(schemaVersion) || !DECISION_AUTHORITY.equals(decisionAuthority)
        || decisionRecordId == null || decisionRecordId.isBlank() || decisionRecordHash == null
        || !decisionRecordHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target Review human decision receipt is invalid");
    }
    outcomeReceipt = Objects.requireNonNull(outcomeReceipt, "outcomeReceipt");
    if (!decisionRecordId.equals(outcomeReceipt.decisionRecordRef())
        || !decisionRecordHash.equals(outcomeReceipt.decisionRecordHash())) {
      throw new IllegalArgumentException("human decision receipt does not bind Outcome receipt");
    }
  }
}
