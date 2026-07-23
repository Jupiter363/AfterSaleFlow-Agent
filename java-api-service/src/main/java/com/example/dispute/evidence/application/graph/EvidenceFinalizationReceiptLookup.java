package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import java.util.Objects;
import java.util.Optional;

/** Read boundary for committed Java Evidence finalization truth. */
public interface EvidenceFinalizationReceiptLookup {

  /**
   * Resolves the semantic operation before consulting current authority or a live Graph lease.
   * Implementations return only a receipt and sidecar committed in the same Java transaction.
   */
  Optional<CommittedFinalization> findExact(EvidenceFinalizationReceiptRef reference);

  /** Semantic response-loss bridge; no receipt identity is available in the Activity request. */
  Optional<CommittedFinalization> findForActivity(
      EvidenceActivityProtocol.ActivityRequest request);

  default EvidenceActivityProtocol.ReceiptLookupResult lookupForActivity(
      EvidenceActivityProtocol.ActivityRequest request) {
    Objects.requireNonNull(request, "request");
    return findForActivity(request)
        .map(value -> EvidenceActivityProtocol.ReceiptLookupResult.committed(
            value.receipt().toSyntheticReceiptRef()))
        .orElseGet(EvidenceActivityProtocol.ReceiptLookupResult::notCommitted);
  }

  default CommittedFinalization requireExact(EvidenceFinalizationReceiptRef reference) {
    Objects.requireNonNull(reference, "reference");
    return findExact(reference)
        .orElseThrow(
            () ->
                new ReceiptReferenceRejectedException(
                    Rejection.MISSING, "committed Evidence finalization receipt is missing"));
  }

  record CommittedFinalization(
      EvidenceFinalizationReceipt receipt, EvidenceTerminalSummary terminalSummary) {
    public CommittedFinalization {
      Objects.requireNonNull(receipt, "receipt");
      Objects.requireNonNull(terminalSummary, "terminalSummary");
      if (!receipt.receiptId().equals(terminalSummary.receiptId())
          || !receipt.receiptHash().equals(terminalSummary.receiptHash())
          || !receipt.tenantSurrogate().equals(terminalSummary.tenantSurrogate())
          || !receipt.caseId().equals(terminalSummary.caseId())
          || receipt.roomEpoch() != terminalSummary.roomEpoch()
          || receipt.fencingToken() != terminalSummary.javaRoomFencingToken()
          || receipt.sourceRevision() != terminalSummary.sourceRevision()
          || receipt.processRevision() != terminalSummary.processRevision()
          || receipt.roomRevision() != terminalSummary.roomRevision()
          || !receipt.resultHash().equals(terminalSummary.resultHash())) {
        throw new IllegalArgumentException("receipt and terminal summary are not the same commit");
      }
    }

    public void requireReference(EvidenceFinalizationReceiptRef reference) {
      Objects.requireNonNull(reference, "reference");
      Rejection rejection = rejectionFor(reference);
      if (rejection != null) {
        throw new ReceiptReferenceRejectedException(
            rejection, "Evidence finalization receipt reference is " + rejection.name());
      }
    }

    public void requireActivity(EvidenceActivityProtocol.ActivityRequest request) {
      Objects.requireNonNull(request, "request");
      Rejection rejection = rejectionFor(request);
      if (rejection != null) {
        throw new ReceiptReferenceRejectedException(
            rejection, "Evidence Activity receipt lookup is " + rejection.name());
      }
    }

    private Rejection rejectionFor(EvidenceFinalizationReceiptRef reference) {
      if (!receipt.tenantSurrogate().equals(reference.tenantSurrogate())) {
        return Rejection.FOREIGN;
      }
      if (!receipt.operationKey().equals(reference.operationKey())
          || !receipt.requestHash().equals(reference.requestHash())) {
        return Rejection.CONFLICTING;
      }
      if (!receipt.receiptId().equals(reference.receiptId())
          || !receipt.receiptHash().equals(reference.receiptHash())
          || !receipt.resultHash().equals(reference.resultHash())) {
        return Rejection.CONFLICTING;
      }
      if (!receipt.caseId().equals(reference.caseId())
          || receipt.roomEpoch() != reference.roomEpoch()
          || receipt.fencingToken() != reference.fencingToken()
          || receipt.processRevision() != reference.processRevision()
          || receipt.roomRevision() != reference.roomRevision()) {
        return Rejection.STALE;
      }
      if (!(receipt.operationBinding()
              instanceof EvidenceFinalizationReceipt.BatchMergeBinding binding)
          || !binding.manifestHash().equals(reference.manifestHash())) {
        return Rejection.FOREIGN;
      }
      return null;
    }

    private Rejection rejectionFor(EvidenceActivityProtocol.ActivityRequest request) {
      if (!receipt.tenantSurrogate().equals(request.tenantSurrogate())) {
        return Rejection.FOREIGN;
      }
      if (!receipt.operationKey().equals(request.operationKey())
          || !receipt.requestHash().equals(request.requestHash())
          || receipt.toSyntheticReceiptRef().operationType() != request.operationType()) {
        return Rejection.CONFLICTING;
      }
      if (!receipt.caseId().equals(request.caseId())
          || receipt.roomEpoch() != request.roomEpoch()
          || receipt.fencingToken() != request.fencingToken()
          || receipt.processRevision() != request.processRevision()
          || receipt.roomRevision() != request.roomRevision()) {
        return Rejection.STALE;
      }
      if (!(receipt.operationBinding()
              instanceof EvidenceFinalizationReceipt.BatchMergeBinding binding)
          || !binding.manifestHash().equals(request.manifestHash())) {
        return Rejection.FOREIGN;
      }
      return null;
    }
  }

  enum Rejection {
    MISSING,
    FOREIGN,
    STALE,
    CONFLICTING,
    CORRUPT
  }

  final class ReceiptReferenceRejectedException extends IllegalStateException {
    private final Rejection rejection;

    public ReceiptReferenceRejectedException(Rejection rejection, String message) {
      super(message);
      this.rejection = Objects.requireNonNull(rejection, "rejection");
    }

    public ReceiptReferenceRejectedException(
        Rejection rejection, String message, Throwable cause) {
      super(message, cause);
      this.rejection = Objects.requireNonNull(rejection, "rejection");
    }

    public Rejection rejection() {
      return rejection;
    }
  }
}
