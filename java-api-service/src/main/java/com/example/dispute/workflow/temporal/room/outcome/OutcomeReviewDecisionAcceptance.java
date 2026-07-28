package com.example.dispute.workflow.temporal.room.outcome;

/** Durable acknowledgement returned only after Outcome has admitted an exact Review receipt. */
public record OutcomeReviewDecisionAcceptance(
    String receiptId,
    String receiptHash,
    long sourceRevision,
    long acceptedRevision,
    boolean accepted) {

  public OutcomeReviewDecisionAcceptance {
    if (receiptId == null
        || receiptId.isBlank()
        || receiptHash == null
        || !receiptHash.matches("[0-9a-f]{64}")
        || sourceRevision < 0
        || acceptedRevision < sourceRevision) {
      throw new IllegalArgumentException("Outcome Review decision acceptance is invalid");
    }
  }

  public static OutcomeReviewDecisionAcceptance rejected(
      String receiptId, String receiptHash, long sourceRevision) {
    return new OutcomeReviewDecisionAcceptance(receiptId, receiptHash, sourceRevision, sourceRevision, false);
  }
}
