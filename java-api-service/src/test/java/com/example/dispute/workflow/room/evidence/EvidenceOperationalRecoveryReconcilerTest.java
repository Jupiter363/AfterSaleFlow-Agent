package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryReconciler;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryReconciler.RecoveryAuthorityRejectedException;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.DurableReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.JavaRecoveryAuthority;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.RecoveryScope;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.TerminalSummary;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceOperationalRecoveryReconcilerTest {

  @Test
  void exposesProjectionOnlyWhenReceiptJavaAuthorityAndLiveGraphLeaseAllMatch() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    DurableReceipt durable = durable(request);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedStore(durable, javaAuthority(request)), graphReader(durable.terminalSummary()));

    var projection = reconciler.reconcile(request);

    assertThat(projection).isPresent();
    assertThat(projection.orElseThrow().receipt()).isEqualTo(durable.receipt());
    assertThat(reconciler.loadCommittedReceipt(request))
        .isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.committed(durable.receipt()));
  }

  @Test
  void absentReceiptNeverUsesTemporalRequestMemoryToFabricateRecovery() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedStore(null, javaAuthority(request)), ignored -> {
          throw new AssertionError("no Graph lookup is valid without a durable receipt");
        });

    assertThat(reconciler.reconcile(request)).isEmpty();
    assertThat(reconciler.loadCommittedReceipt(request))
        .isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.notCommitted());
  }

  @Test
  void staleLiveGraphLeaseFailsClosedEvenWhenTheJavaReceiptIsReplayable() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    DurableReceipt durable = durable(request);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedStore(durable, javaAuthority(request)), ignored -> {
          throw new IllegalStateException("stale Graph lease");
        });

    assertThatThrownBy(() -> reconciler.reconcile(request))
        .isInstanceOf(RecoveryAuthorityRejectedException.class)
        .hasMessageContaining("current Graph lease");
  }

  @Test
  void disabledOrNonSyntheticAuthorityCannotTurnAReceiptIntoRecoveryState() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    DurableReceipt durable = durable(request);
    JavaRecoveryAuthority disabled = new JavaRecoveryAuthority(
        request.tenantSurrogate(), request.caseId(), "ROOM_P5_EVIDENCE_1", "EVIDENCE",
        "2".repeat(64), request.roomEpoch(), request.fencingToken(), 3,
        request.processRevision(), request.roomRevision(),
        "DISABLED", false, false, false);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedStore(durable, disabled), graphReader(durable.terminalSummary()));

    assertThatThrownBy(() -> reconciler.reconcile(request))
        .isInstanceOf(RecoveryAuthorityRejectedException.class)
        .hasMessageContaining("current Java recovery authority");
  }

  static DurableReceipt durable(ActivityRequest request) {
    EvidenceFinalizationReceiptRef receipt = EvidenceRoomActivityContractTest.receipt(request);
    return new DurableReceipt(receipt, new TerminalSummary(
        "SUMMARY_P5_ACTIVITY", "3".repeat(64), request.tenantSurrogate(), request.caseId(),
        request.roomEpoch(), request.fencingToken(), "grt.v1." + "4".repeat(32), 41, 42, 3,
        request.processRevision(), request.roomRevision(), "2".repeat(64), request.manifestHash(),
        request.operationKey(), request.requestHash(), receipt.resultHash()));
  }

  static JavaRecoveryAuthority javaAuthority(ActivityRequest request) {
    return new JavaRecoveryAuthority(
        request.tenantSurrogate(), request.caseId(), "ROOM_P5_EVIDENCE_1", "EVIDENCE",
        "2".repeat(64), request.roomEpoch(), request.fencingToken(), 3,
        request.processRevision(), request.roomRevision(),
        "SIGNED_SYNTHETIC_SHADOW", true, false, false);
  }

  static com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority graphReader(
      TerminalSummary ignored) {
    return requirement -> { };
  }

  record FixedStore(DurableReceipt durable, JavaRecoveryAuthority authority)
      implements EvidenceOperationalRecoveryStore {
    @Override
    public Optional<DurableReceipt> findCommitted(ActivityRequest ignored) {
      return Optional.ofNullable(durable);
    }

    @Override
    public Optional<JavaRecoveryAuthority> findCurrentJavaAuthority(RecoveryScope ignored) {
      return Optional.ofNullable(authority);
    }
  }
}
