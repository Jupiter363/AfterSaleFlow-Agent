package com.example.dispute.workflow.temporal.room.evidence;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only source for Evidence recovery. Receipt material is supplied by the C3 ledger read
 * port; this boundary never creates a receipt, a formal effect, or a Temporal epoch allocation.
 */
public interface EvidenceOperationalRecoveryStore {

  Optional<DurableReceipt> findCommitted(EvidenceActivityProtocol.ActivityRequest request);

  Optional<JavaRecoveryAuthority> findCurrentJavaAuthority(RecoveryScope scope);

  record RecoveryScope(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long javaRoomFencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      String authoritySnapshotHash) {
    public RecoveryScope {
      EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
      EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
      EvidenceOperationKeys.requireHash(authoritySnapshotHash, "authoritySnapshotHash");
      if (roomEpoch < 0 || javaRoomFencingToken < 1 || sourceRevision < 1
          || processRevision < 0 || roomRevision < 0) {
        throw new IllegalArgumentException("recovery scope contains an invalid authority revision");
      }
    }
  }

  /** Immutable C3 receipt plus the terminal-summary sidecar that binds the distinct Graph fence. */
  record DurableReceipt(EvidenceFinalizationReceiptRef receipt, TerminalSummary terminalSummary) {
    public DurableReceipt {
      receipt = Objects.requireNonNull(receipt, "receipt");
      terminalSummary = Objects.requireNonNull(terminalSummary, "terminalSummary");
      if (!terminalSummary.matches(receipt)) {
        throw new IllegalArgumentException("terminal summary does not bind its receipt reference");
      }
    }
  }

  record TerminalSummary(
      String summaryId,
      String summaryHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long javaRoomFencingToken,
      String graphThreadId,
      long graphLeaseFencingToken,
      long javaFinalizationFencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      String authoritySnapshotHash,
      String manifestHash,
      String operationKey,
      String requestHash,
      String resultHash) {
    public TerminalSummary {
      EvidenceOperationKeys.requireIdentifier(summaryId, "summaryId");
      EvidenceOperationKeys.requireHash(summaryHash, "summaryHash");
      EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
      EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
      if (graphThreadId == null || !graphThreadId.matches("^grt[.]v1[.][0-9a-f]{32}$")) {
        throw new IllegalArgumentException("graphThreadId must be a Graph v1 thread identifier");
      }
      EvidenceOperationKeys.requireHash(manifestHash, "manifestHash");
      EvidenceOperationKeys.requireHash(authoritySnapshotHash, "authoritySnapshotHash");
      EvidenceOperationKeys.requireValid(operationKey);
      EvidenceOperationKeys.requireHash(requestHash, "requestHash");
      EvidenceOperationKeys.requireHash(resultHash, "resultHash");
      if (roomEpoch < 0 || javaRoomFencingToken < 1 || graphLeaseFencingToken < 1
          || javaFinalizationFencingToken < 1
          || sourceRevision < 1 || processRevision < 0 || roomRevision < 0) {
        throw new IllegalArgumentException("terminal summary contains an invalid fence or revision");
      }
      if (javaRoomFencingToken == graphLeaseFencingToken
          || javaRoomFencingToken == javaFinalizationFencingToken
          || graphLeaseFencingToken == javaFinalizationFencingToken) {
        throw new IllegalArgumentException("Java room, Graph lease, and finalization fences must remain distinct");
      }
    }

    boolean matches(EvidenceFinalizationReceiptRef receipt) {
      return tenantSurrogate.equals(receipt.tenantSurrogate())
          && caseId.equals(receipt.caseId())
          && roomEpoch == receipt.roomEpoch()
          && javaRoomFencingToken == receipt.fencingToken()
          && processRevision == receipt.processRevision()
          && roomRevision == receipt.roomRevision()
          && manifestHash.equals(receipt.manifestHash())
          && operationKey.equals(receipt.operationKey())
          && requestHash.equals(receipt.requestHash())
          && resultHash.equals(receipt.resultHash());
    }
  }

  /** Current Java authority only. It deliberately does not contain a cached Graph lease. */
  record JavaRecoveryAuthority(
      String tenantSurrogate,
      String caseId,
      String roomId,
      String roomType,
      String authoritySnapshotHash,
      long roomEpoch,
      long javaRoomFencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      String runtimeMode,
      boolean javaSignedSynthetic,
      boolean formalSinkEligible,
      boolean temporalEvidenceAllocation) {
    public JavaRecoveryAuthority {
      EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
      EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
      EvidenceOperationKeys.requireIdentifier(roomId, "roomId");
      EvidenceOperationKeys.requireHash(authoritySnapshotHash, "authoritySnapshotHash");
      if (!"EVIDENCE".equals(roomType) || roomEpoch < 0 || javaRoomFencingToken < 1
          || sourceRevision < 1 || processRevision < 0 || roomRevision < 0) {
        throw new IllegalArgumentException("Java recovery authority is invalid");
      }
      if (!"DISABLED".equals(runtimeMode) && !"SIGNED_SYNTHETIC_SHADOW".equals(runtimeMode)) {
        throw new IllegalArgumentException("Evidence recovery runtime mode is invalid");
      }
      if (formalSinkEligible || temporalEvidenceAllocation) {
        throw new IllegalArgumentException("recovery authority must not enable a formal sink or allocation");
      }
    }

    boolean permitsRecovery(RecoveryScope scope) {
      return "SIGNED_SYNTHETIC_SHADOW".equals(runtimeMode)
          && javaSignedSynthetic
          && tenantSurrogate.equals(scope.tenantSurrogate())
          && caseId.equals(scope.caseId())
          && authoritySnapshotHash.equals(scope.authoritySnapshotHash())
          && roomEpoch == scope.roomEpoch()
          && javaRoomFencingToken == scope.javaRoomFencingToken()
          && sourceRevision == scope.sourceRevision()
          && processRevision == scope.processRevision()
          && roomRevision == scope.roomRevision();
    }
  }
}
