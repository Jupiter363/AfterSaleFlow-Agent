package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import java.util.Objects;

/** Java-authoritative binding of a provisioned Review epoch to the frozen human-review facts. */
public interface TargetReviewOutcomeStartBindingPort {
  Binding bind(ProvisionRoomEpoch provision);

  record Binding(String activationId, String activationManifestHash, OutcomeWorkflowStart start) {
    public Binding {
      if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")
          || activationManifestHash == null || !activationManifestHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Review Outcome start binding activation is invalid");
      }
      start = Objects.requireNonNull(start, "start");
    }

    /** Rejects a decision receipt that was not made against this exact frozen review start. */
    public void requireCompatible(OutcomeReviewDecisionReceipt receipt) {
      receipt = Objects.requireNonNull(receipt, "receipt");
      if (!start.workflowId().equals(receipt.workflowId())
          || !start.caseId().equals(receipt.caseId())
          || !start.reviewTaskId().equals(receipt.reviewTaskId())
          || !start.frozenReviewPacketRef().equals(receipt.frozenReviewPacketRef())
          || !start.frozenReviewPacketHash().equals(receipt.frozenReviewPacketHash())
          || !start.actionSnapshotRef().equals(receipt.actionSnapshotRef())
          || !start.actionSnapshotHash().equals(receipt.actionSnapshotHash())
          || !start.requiredOperationSetRef().equals(receipt.requiredOperationSetRef())
          || !start.requiredOperationSetHash().equals(receipt.requiredOperationSetHash())
          || start.requiredOperationCount() != receipt.requiredOperationCount()
          || !start.policyVersion().equals(receipt.policyVersion())
          || start.epoch() != receipt.epoch()
          || start.revision() != receipt.sourceRevision()
          || receipt.revision() != receipt.sourceRevision() + 1
          || start.fence() != receipt.fence()
          || start.syntheticOnly() != receipt.syntheticOnly()) {
        throw new IllegalStateException("Outcome Review decision receipt does not match its durable start binding");
      }
    }
  }
}
