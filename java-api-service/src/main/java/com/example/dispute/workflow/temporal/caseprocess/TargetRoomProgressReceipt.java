package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/** Bounded, replay-stable parent callback for one Java-formal target-room receipt. */
public record TargetRoomProgressReceipt(
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    long processRevision,
    long roomRevision,
    String javaReceiptId,
    String javaReceiptHash) {
  public TargetRoomProgressReceipt {
    roomType = Objects.requireNonNull(roomType, "roomType");
    if (roomEpoch < 0 || fencingToken < 1 || processRevision < 0 || roomRevision < 0
        || javaReceiptId == null || javaReceiptId.isBlank()
        || javaReceiptHash == null || !javaReceiptHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target room progress receipt is invalid");
    }
  }
}
