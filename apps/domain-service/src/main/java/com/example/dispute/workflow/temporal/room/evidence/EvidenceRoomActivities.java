package com.example.dispute.workflow.temporal.room.evidence;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * Read-only Activity boundary for reconciled Java-ledger receipts. Implementations must never use
 * this contract to create a formal Evidence write.
 */
@ActivityInterface
public interface EvidenceRoomActivities {

  @ActivityMethod(name = "evidenceLoadCommittedReceipt")
  EvidenceActivityProtocol.ReceiptLookupResult loadCommittedReceipt(
      EvidenceActivityProtocol.ActivityRequest request);

  /**
   * Concrete but intentionally unregistered production Activity implementation. Worker assembly
   * may opt in only after the Phase 5 promotion gate; it has no write, allocation, or sink method.
   */
  final class EvidenceRoomActivitiesReconciliation implements EvidenceRoomActivities {

    private final EvidenceOperationalRecoveryReconciler reconciler;

    public EvidenceRoomActivitiesReconciliation(EvidenceOperationalRecoveryReconciler reconciler) {
      this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    @Override
    public EvidenceActivityProtocol.ReceiptLookupResult loadCommittedReceipt(
        EvidenceActivityProtocol.ActivityRequest request) {
      return reconciler.loadCommittedReceipt(request);
    }
  }
}
