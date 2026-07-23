package com.example.dispute.workflow.temporal.room.evidence;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseRequirement;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery.StateEnricher;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ReceiptLookupResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed recovery assembler. It reads an already committed receipt, then separately checks
 * current Java authority and a live Graph lease before revealing a recovery projection.
 */
public final class EvidenceOperationalRecoveryReconciler {

  private final EvidenceOperationalRecoveryStore store;
  private final GraphLeaseAuthority graphLeaseAuthority;

  public EvidenceOperationalRecoveryReconciler(
      EvidenceOperationalRecoveryStore store, GraphLeaseAuthority graphLeaseAuthority) {
    this.store = Objects.requireNonNull(store, "store");
    this.graphLeaseAuthority = Objects.requireNonNull(graphLeaseAuthority, "graphLeaseAuthority");
  }

  public ReceiptLookupResult loadCommittedReceipt(ActivityRequest request) {
    return reconcile(request)
        .map(projection -> ReceiptLookupResult.committed(projection.receipt()))
        .orElseGet(ReceiptLookupResult::notCommitted);
  }

  public Optional<EvidenceOperationalRecoveryProjection> reconcile(ActivityRequest request) {
    Objects.requireNonNull(request, "request");
    Optional<EvidenceOperationalRecoveryStore.DurableReceipt> durable = store.findCommitted(request);
    if (durable.isEmpty()) {
      return Optional.empty();
    }

    EvidenceOperationalRecoveryStore.DurableReceipt material = durable.orElseThrow();
    if (!material.receipt().matches(request)) {
      throw rejected("durable receipt does not match the activity request");
    }
    EvidenceOperationalRecoveryStore.RecoveryScope scope = new EvidenceOperationalRecoveryStore.RecoveryScope(
        request.tenantSurrogate(), request.caseId(), request.roomEpoch(), request.fencingToken(),
        material.terminalSummary().sourceRevision(), request.processRevision(), request.roomRevision(),
        material.terminalSummary().authoritySnapshotHash());
    EvidenceOperationalRecoveryStore.JavaRecoveryAuthority javaAuthority = store
        .findCurrentJavaAuthority(scope)
        .orElseThrow(() -> rejected("current Java recovery authority is unavailable"));
    if (!javaAuthority.permitsRecovery(scope)) {
      throw rejected("current Java recovery authority rejects this receipt");
    }

    requireCurrentGraphLease(material.terminalSummary(), javaAuthority);
    return Optional.of(new EvidenceOperationalRecoveryProjection(
        "evidence-operational-recovery-projection.v1",
        material.receipt(),
        material.terminalSummary(),
        javaAuthority));
  }

  private void requireCurrentGraphLease(
      EvidenceOperationalRecoveryStore.TerminalSummary summary,
      EvidenceOperationalRecoveryStore.JavaRecoveryAuthority javaAuthority) {
    try {
      graphLeaseAuthority.requireCurrent(new GraphLeaseRequirement(
          summary.authoritySnapshotHash(), summary.tenantSurrogate(), summary.caseId(),
          javaAuthority.roomId(),
          summary.roomEpoch(), summary.javaRoomFencingToken(), summary.graphThreadId(),
          summary.graphLeaseFencingToken()));
    } catch (RuntimeException failure) {
      throw rejected("current Graph lease rejected the terminal summary", failure);
    }
  }

  private static RecoveryAuthorityRejectedException rejected(String message) {
    return new RecoveryAuthorityRejectedException(message);
  }

  private static RecoveryAuthorityRejectedException rejected(String message, Throwable cause) {
    return new RecoveryAuthorityRejectedException(message, cause);
  }

  /**
   * Optional D1 projection extension. Wiring must supply a durable Java recovery reader; absent
   * receipt material retains the D1 state and never falls back to Temporal history or Graph state.
   */
  public static final class EvidenceOperationalRecoveryStateEnricher implements StateEnricher {

    private final DurableRecoveryStateReader recoveryStateReader;

    public EvidenceOperationalRecoveryStateEnricher(
        DurableRecoveryStateReader recoveryStateReader) {
      this.recoveryStateReader = Objects.requireNonNull(recoveryStateReader, "recoveryStateReader");
    }

    @Override
    public EvidenceProcessProjectionAdapter.ProjectionEvidenceState enrich(
        EvidenceProcessProjectionAdapter.ProjectionRow row,
        AuthenticatedActor actor,
        EvidenceProcessProjectionAdapter.ProjectionEvidenceState current) {
      Objects.requireNonNull(row, "row");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(current, "current");
      return recoveryStateReader.findDurableRecovery(row, actor)
          .map(recovery -> withRecovery(current, recovery))
          .orElse(current);
    }

    private static EvidenceProcessProjectionAdapter.ProjectionEvidenceState withRecovery(
        EvidenceProcessProjectionAdapter.ProjectionEvidenceState state, Recovery recovery) {
      return new EvidenceProcessProjectionAdapter.ProjectionEvidenceState(
          state.originalDeadlineAt(),
          state.warningSent(),
          state.warningSentAt(),
          state.partyCompletion(),
          state.assessmentCounts(),
          state.dossierVersion(),
          state.lastEventSequence(),
          state.terminalReason(),
          state.terminalProposal(),
          recovery);
    }

    @FunctionalInterface
    public interface DurableRecoveryStateReader {
      Optional<Recovery> findDurableRecovery(
          EvidenceProcessProjectionAdapter.ProjectionRow row, AuthenticatedActor actor);
    }
  }

  public static final class RecoveryAuthorityRejectedException extends IllegalStateException {
    public RecoveryAuthorityRejectedException(String message) {
      super("Evidence recovery authority rejected: " + message);
    }

    public RecoveryAuthorityRejectedException(String message, Throwable cause) {
      super("Evidence recovery authority rejected: " + message, cause);
    }
  }
}
