package com.example.dispute.workflow.temporal.room.outcome;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;

/** Exact Review coordinates at which the parent asks Outcome to perform its post-routing close. */
public record OutcomeCompletionRequest(
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String reviewReceiptId,
    String reviewReceiptHash,
    long reviewReceiptRevision) {

  public OutcomeCompletionRequest {
    if (roomType != RoomType.REVIEW
        || roomEpoch < 1
        || fencingToken < 1
        || expectedProcessRevision < 0
        || expectedRoomRevision < 0
        || reviewReceiptId == null
        || reviewReceiptId.isBlank()
        || reviewReceiptHash == null
        || !reviewReceiptHash.matches("[0-9a-f]{64}")
        || reviewReceiptRevision < 0) {
      throw new IllegalArgumentException("target Outcome completion request is invalid");
    }
  }
}
