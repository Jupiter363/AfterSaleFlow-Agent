package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityExecutionState;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ProtocolRejectedException;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ReceiptLookupResult;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.Violation;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef.OperationType;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomActivities;
import io.temporal.activity.ActivityMethod;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class EvidenceRoomActivityContractTest {

  private static final String TENANT = "TENANT_P5_SYNTHETIC_ACTIVITY";
  private static final String CASE_ID = "CASE_P5_SYNTHETIC_ACTIVITY";
  private static final String MANIFEST_HASH = "a".repeat(64);

  @Test
  void matchingCommittedReceiptIsTheOnlyWayToPermitProgress() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    EvidenceActivityExecutionState state = EvidenceActivityProtocol.begin(request);

    EvidenceActivityExecutionState unconfirmed =
        EvidenceActivityProtocol.reconcile(state, request, ReceiptLookupResult.notCommitted());
    assertThat(unconfirmed.permitsProgress()).isFalse();
    assertThat(unconfirmed.status())
        .isEqualTo(EvidenceActivityExecutionState.Status.RECONCILING_RECEIPT);

    EvidenceFinalizationReceiptRef receipt = receipt(request);
    EvidenceActivityExecutionState confirmed =
        EvidenceActivityProtocol.reconcile(
            unconfirmed, request, ReceiptLookupResult.committed(receipt));
    assertThat(confirmed.permitsProgress()).isTrue();
    assertThat(confirmed.committedReceipt()).isEqualTo(receipt);
    assertThat(confirmed.receiptLookupAttempts()).isEqualTo(2);
  }

  @Test
  void staleEpochFenceManifestAndIdempotencyNeverAdvanceState() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    EvidenceActivityExecutionState state = EvidenceActivityProtocol.begin(request);

    assertBindingRejected(state, request, receiptWithEpoch(request, 8));
    assertBindingRejected(state, request, receiptWithFence(request, 10));
    assertBindingRejected(state, request, receiptWithManifest(request, "b".repeat(64)));
    assertBindingRejected(state, request, receiptWithRequestHash(request, "c".repeat(64)));

    ActivityRequest conflictingRequest = new ActivityRequest(
        request.schemaVersion(),
        request.operationType(),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        request.manifestHash(),
        request.processRevision(),
        request.roomRevision(),
        request.operationKey(),
        "d".repeat(64),
        InvocationMode.RETRY_RECONCILE_ONLY);
    assertThatThrownBy(() -> EvidenceActivityProtocol.reconcile(
            state, conflictingRequest, ReceiptLookupResult.notCommitted()))
        .isInstanceOfSatisfying(
            ProtocolRejectedException.class,
            rejected -> assertThat(rejected.violation()).isEqualTo(Violation.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void lostResponseAndCancellationRequireReceiptReconciliationRatherThanHistoryInference() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    EvidenceActivityExecutionState lost =
        EvidenceActivityProtocol.activityResponseLost(EvidenceActivityProtocol.begin(request));
    assertThat(lost.permitsProgress()).isFalse();
    assertThat(lost.recoveryReason())
        .isEqualTo(EvidenceActivityExecutionState.RecoveryReason.ACTIVITY_RESPONSE_LOST);

    EvidenceActivityExecutionState cancelled = EvidenceActivityProtocol.cancellationRequested(lost);
    assertThat(cancelled.permitsProgress()).isFalse();
    assertThat(cancelled.status())
        .isEqualTo(EvidenceActivityExecutionState.Status.CANCELLATION_RECONCILING);
    assertThat(cancelled.committedReceipt()).isNull();

    EvidenceActivityExecutionState stillUnconfirmed =
        EvidenceActivityProtocol.reconcile(
            cancelled,
            request(InvocationMode.CANCELLATION_RECONCILE_ONLY),
            ReceiptLookupResult.notCommitted());
    assertThat(stillUnconfirmed.permitsProgress()).isFalse();
    assertThat(stillUnconfirmed.status())
        .isEqualTo(EvidenceActivityExecutionState.Status.RECONCILING_RECEIPT);
  }

  @Test
  void committedRetryConsumesTheOriginalReceiptAndRejectsAConflictingOne() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    EvidenceFinalizationReceiptRef receipt = receipt(request);
    EvidenceActivityExecutionState confirmed = EvidenceActivityProtocol.reconcile(
        EvidenceActivityProtocol.begin(request), request, ReceiptLookupResult.committed(receipt));

    EvidenceActivityExecutionState replay = EvidenceActivityProtocol.reconcile(
        confirmed,
        request(InvocationMode.RETRY_RECONCILE_ONLY),
        ReceiptLookupResult.committed(receipt));
    assertThat(replay).isEqualTo(confirmed);
    assertThatThrownBy(() -> EvidenceActivityProtocol.reconcile(
            confirmed,
            request(InvocationMode.RETRY_RECONCILE_ONLY),
            ReceiptLookupResult.committed(receiptWithResultHash(request, "e".repeat(64)))))
        .isInstanceOfSatisfying(
            ProtocolRejectedException.class,
            rejected -> assertThat(rejected.violation())
                .isEqualTo(Violation.COMMITTED_RECEIPT_CONFLICT));
  }

  @Test
  void receiptReferenceRejectsAnyFormalSinkShape() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    assertThatThrownBy(() -> new EvidenceFinalizationReceiptRef(
        "evidence-finalization-receipt-ref.v1",
        "RECEIPT_P5_ACTIVITY",
        "1".repeat(64),
        request.operationType(),
        request.operationKey(),
        request.requestHash(),
        "2".repeat(64),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        request.manifestHash(),
        request.processRevision(),
        request.roomRevision(),
        EvidenceFinalizationReceiptRef.ISOLATED_SYNTHETIC_LEDGER,
        "COMMITTED",
        true,
        false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("isolated synthetic");
  }

  @Test
  void manifestHashAndReceiptLookupBudgetAreBoundedBeforeProgress() {
    ActivityRequest request = request(InvocationMode.INITIAL_LOOKUP);
    assertThatThrownBy(() -> new ActivityRequest(
        request.schemaVersion(),
        request.operationType(),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        "9".repeat(64),
        request.processRevision(),
        request.roomRevision(),
        request.operationKey(),
        request.requestHash(),
        request.invocationMode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manifest hash");

    EvidenceActivityExecutionState state = EvidenceActivityProtocol.begin(request);
    for (int attempt = 0;
        attempt < EvidenceActivityExecutionState.MAX_RECEIPT_LOOKUPS;
        attempt++) {
      state = EvidenceActivityProtocol.reconcile(state, request, ReceiptLookupResult.notCommitted());
    }
    assertThat(state.status())
        .isEqualTo(EvidenceActivityExecutionState.Status.RECEIPT_UNCONFIRMED);
    assertThat(state.permitsProgress()).isFalse();
  }

  @Test
  void activitySurfaceOnlyLoadsAnAlreadyCommittedReceipt() throws Exception {
    Method method = EvidenceRoomActivities.class.getDeclaredMethod(
        "loadCommittedReceipt", ActivityRequest.class);
    assertThat(EvidenceRoomActivities.class.getDeclaredMethods()).containsExactly(method);
    assertThat(method.getAnnotation(ActivityMethod.class).name())
        .isEqualTo("evidenceLoadCommittedReceipt");
    assertThat(method.getReturnType()).isEqualTo(EvidenceActivityProtocol.ReceiptLookupResult.class);
  }

  private static void assertBindingRejected(
      EvidenceActivityExecutionState state,
      ActivityRequest request,
      EvidenceFinalizationReceiptRef receipt) {
    assertThatThrownBy(() -> EvidenceActivityProtocol.reconcile(
        state, request, ReceiptLookupResult.committed(receipt)))
        .isInstanceOfSatisfying(
            ProtocolRejectedException.class,
            rejected -> assertThat(rejected.violation()).isEqualTo(Violation.RECEIPT_BINDING_MISMATCH));
    assertThat(state.permitsProgress()).isFalse();
  }

  static ActivityRequest request(InvocationMode invocationMode) {
    return new ActivityRequest(
        "evidence-activity-request.v1",
        OperationType.GRAPH_REQUEST,
        TENANT,
        CASE_ID,
        7,
        11,
        MANIFEST_HASH,
        4,
        6,
        EvidenceOperationKeys.graphRequest(CASE_ID, 7, MANIFEST_HASH, "RUN_P5_ACTIVITY"),
        "f".repeat(64),
        invocationMode);
  }

  static EvidenceFinalizationReceiptRef receipt(ActivityRequest request) {
    return receipt(
        request,
        request.roomEpoch(),
        request.fencingToken(),
        request.manifestHash(),
        request.requestHash(),
        "0".repeat(64));
  }

  private static EvidenceFinalizationReceiptRef receiptWithEpoch(
      ActivityRequest request, long epoch) {
    return receipt(
        request,
        epoch,
        request.fencingToken(),
        request.manifestHash(),
        request.requestHash(),
        "0".repeat(64));
  }

  private static EvidenceFinalizationReceiptRef receiptWithFence(
      ActivityRequest request, long fence) {
    return receipt(
        request,
        request.roomEpoch(),
        fence,
        request.manifestHash(),
        request.requestHash(),
        "0".repeat(64));
  }

  private static EvidenceFinalizationReceiptRef receiptWithManifest(
      ActivityRequest request, String manifestHash) {
    return receipt(
        request,
        request.roomEpoch(),
        request.fencingToken(),
        manifestHash,
        request.requestHash(),
        "0".repeat(64));
  }

  private static EvidenceFinalizationReceiptRef receiptWithRequestHash(
      ActivityRequest request, String requestHash) {
    return receipt(
        request,
        request.roomEpoch(),
        request.fencingToken(),
        request.manifestHash(),
        requestHash,
        "0".repeat(64));
  }

  private static EvidenceFinalizationReceiptRef receiptWithResultHash(
      ActivityRequest request, String resultHash) {
    return receipt(
        request,
        request.roomEpoch(),
        request.fencingToken(),
        request.manifestHash(),
        request.requestHash(),
        resultHash);
  }

  private static EvidenceFinalizationReceiptRef receipt(
      ActivityRequest request,
      long epoch,
      long fence,
      String manifestHash,
      String requestHash,
      String resultHash) {
    return new EvidenceFinalizationReceiptRef(
        "evidence-finalization-receipt-ref.v1",
        "RECEIPT_P5_ACTIVITY",
        "1".repeat(64),
        request.operationType(),
        EvidenceOperationKeys.graphRequest(
            request.caseId(), epoch, manifestHash, "RUN_P5_ACTIVITY"),
        requestHash,
        resultHash,
        request.tenantSurrogate(),
        request.caseId(),
        epoch,
        fence,
        manifestHash,
        request.processRevision(),
        request.roomRevision(),
        EvidenceFinalizationReceiptRef.ISOLATED_SYNTHETIC_LEDGER,
        "COMMITTED",
        false,
        false);
  }
}
