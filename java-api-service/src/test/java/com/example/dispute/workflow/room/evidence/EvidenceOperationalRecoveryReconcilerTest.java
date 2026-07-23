package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup.CommittedFinalization;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ReceiptLookupResult;
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
        new FixedReceiptLookup(durable),
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
        new FixedReceiptLookup(null),
        new FixedStore(null, javaAuthority(request)), ignored -> {
          throw new AssertionError("no Graph lookup is valid without a durable receipt");
        });

    assertThat(reconciler.reconcile(request)).isEmpty();
    assertThat(reconciler.loadCommittedReceipt(request))
        .isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.notCommitted());
  }

  @Test
  void committedReceiptReplaySurvivesGraphLeaseTakeoverWhileRecoveryRejects() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    DurableReceipt durable = durable(request);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedReceiptLookup(durable),
        new FixedStore(durable, javaAuthority(request)), ignored -> {
          throw new IllegalStateException("Graph lease was taken over");
        });

    assertThat(reconciler.loadCommittedReceipt(request))
        .isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.committed(durable.receipt()));
    assertThatThrownBy(() -> reconciler.reconcile(request))
        .isInstanceOf(RecoveryAuthorityRejectedException.class)
        .hasMessageContaining("current Graph lease");
  }

  @Test
  void committedReceiptReplaySurvivesJavaAuthorityTakeoverWhileRecoveryRejects() {
    ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    DurableReceipt durable = durable(request);
    JavaRecoveryAuthority takeover = new JavaRecoveryAuthority(
        request.tenantSurrogate(), request.caseId(), "ROOM_P5_EVIDENCE_1", "EVIDENCE",
        "5".repeat(64), request.roomEpoch() + 1, request.fencingToken() + 1, 4,
        request.processRevision() + 1, request.roomRevision() + 1,
        "SIGNED_SYNTHETIC_SHADOW", true, false, false);
    EvidenceOperationalRecoveryReconciler reconciler = new EvidenceOperationalRecoveryReconciler(
        new FixedReceiptLookup(durable), new FixedStore(durable, takeover), ignored -> {
          throw new AssertionError("Graph lease must not be checked after Java authority takeover");
        });

    assertThat(reconciler.loadCommittedReceipt(request))
        .isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.committed(durable.receipt()));
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

  record FixedReceiptLookup(DurableReceipt durable) implements EvidenceFinalizationReceiptLookup {
    @Override
    public Optional<CommittedFinalization> findExact(EvidenceFinalizationReceiptRef ignored) {
      throw new AssertionError("receipt replay must use the semantic Activity lookup");
    }

    @Override
    public Optional<CommittedFinalization> findForActivity(ActivityRequest ignored) {
      throw new AssertionError("the test lookup supplies the protocol result directly");
    }

    @Override
    public ReceiptLookupResult lookupForActivity(ActivityRequest request) {
      return lookupResult(durable, request);
    }
  }

  record FixedStore(DurableReceipt durable, JavaRecoveryAuthority authority)
      implements EvidenceOperationalRecoveryStore, EvidenceFinalizationReceiptLookup {
    @Override
    public Optional<DurableReceipt> findCommitted(ActivityRequest ignored) {
      return Optional.ofNullable(durable);
    }

    @Override
    public Optional<JavaRecoveryAuthority> findCurrentJavaAuthority(RecoveryScope ignored) {
      return Optional.ofNullable(authority);
    }

    @Override
    public Optional<CommittedFinalization> findExact(EvidenceFinalizationReceiptRef ignored) {
      throw new AssertionError("receipt replay must use the semantic Activity lookup");
    }

    @Override
    public Optional<CommittedFinalization> findForActivity(ActivityRequest ignored) {
      throw new AssertionError("the test lookup supplies the protocol result directly");
    }

    @Override
    public ReceiptLookupResult lookupForActivity(ActivityRequest request) {
      return lookupResult(durable, request);
    }
  }

  private static ReceiptLookupResult lookupResult(DurableReceipt durable, ActivityRequest request) {
    if (durable == null) {
      return ReceiptLookupResult.notCommitted();
    }
    if (!durable.receipt().matches(request)) {
      throw new IllegalArgumentException(
          "fixed C3 lookup requires tenant, operationKey, and requestHash to match");
    }
    return ReceiptLookupResult.committed(durable.receipt());
  }
}
