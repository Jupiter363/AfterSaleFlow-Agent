package com.example.dispute.workflow.temporal.room.evidence;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Read-only Activity boundary for reconciled Java-ledger receipts. Implementations must never use
 * this contract to create a formal Evidence write.
 */
@ActivityInterface
public interface EvidenceRoomActivities {

  @ActivityMethod(name = "evidenceLoadCommittedReceipt")
  EvidenceActivityProtocol.ReceiptLookupResult loadCommittedReceipt(
      EvidenceActivityProtocol.ActivityRequest request);
}
