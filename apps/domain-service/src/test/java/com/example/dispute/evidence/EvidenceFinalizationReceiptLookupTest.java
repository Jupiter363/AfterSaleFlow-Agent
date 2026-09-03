package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup.CommittedFinalization;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup.ReceiptReferenceRejectedException;
import com.example.dispute.evidence.application.graph.EvidenceTerminalSummary;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceFinalizationReceiptLookupTest {

  private static final String MANIFEST_HASH = "a".repeat(64);
  private static final String REQUEST_HASH = "b".repeat(64);
  private static final String RESULT_HASH = "c".repeat(64);
  private static final String AUTHORITY_HASH = "d".repeat(64);
  private static final String ACTOR_SCOPE_HASH = "e".repeat(64);
  private static final String PROPOSAL_HASH = "f".repeat(64);
  private static final Instant COMMITTED_AT = Instant.parse("2026-07-23T10:15:30Z");

  @Test
  void exactCommittedReferenceReturnsReceiptAndValidatedSidecar() {
    CommittedFinalization committed = committed();
    EvidenceFinalizationReceiptLookup lookup = lookupReturning(committed);

    CommittedFinalization resolved = lookup.requireExact(committed.receipt().toSyntheticReceiptRef());

    assertThat(resolved).isEqualTo(committed);
    assertThat(resolved.terminalSummary().graphThreadId())
        .isEqualTo("grt.v1.018f6b7ec30a7430982fffc520c8195c");
    assertThat(resolved.terminalSummary().javaRoomFencingToken()).isEqualTo(7);
    assertThat(resolved.terminalSummary().graphLeaseFencingToken()).isEqualTo(7001);
    assertThat(resolved.terminalSummary().javaFinalizationFencingToken()).isEqualTo(9001);
  }

  @Test
  void missingForeignStaleAndConflictingReferencesAreRejected() {
    CommittedFinalization committed = committed();
    EvidenceFinalizationReceiptRef exact = committed.receipt().toSyntheticReceiptRef();
    EvidenceFinalizationReceiptLookup missing = lookupReturning(null);

    assertThatThrownBy(() -> missing.requireExact(exact))
        .isInstanceOf(ReceiptReferenceRejectedException.class)
        .extracting(failure -> ((ReceiptReferenceRejectedException) failure).rejection())
        .isEqualTo(EvidenceFinalizationReceiptLookup.Rejection.MISSING);

    assertRejected(committed, copy(exact, "TENANT_P5_SYNTHETIC_FOREIGN", 7, REQUEST_HASH), "FOREIGN");
    assertRejected(committed, copy(exact, exact.tenantSurrogate(), 8, REQUEST_HASH), "STALE");
    assertRejected(committed, copy(exact, exact.tenantSurrogate(), 7, "1".repeat(64)), "CONFLICTING");

    EvidenceFinalizationReceiptRef conflictingIdentity =
        new EvidenceFinalizationReceiptRef(
            exact.schemaVersion(),
            "RECEIPT_OTHER",
            "2".repeat(64),
            exact.operationType(),
            exact.operationKey(),
            exact.requestHash(),
            exact.resultHash(),
            exact.tenantSurrogate(),
            exact.caseId(),
            exact.roomEpoch(),
            exact.fencingToken(),
            exact.manifestHash(),
            exact.processRevision(),
            exact.roomRevision(),
            exact.commitScope(),
            exact.status(),
            exact.formalDomainWrite(),
            exact.formalSinkEligible());
    assertRejected(committed, conflictingIdentity, "CONFLICTING");
  }

  @Test
  void activityResponseLossUsesSemanticReceiptLookupWithoutTemporalReceiptIdentity() {
    CommittedFinalization committed = committed();
    EvidenceFinalizationReceiptRef ref = committed.receipt().toSyntheticReceiptRef();
    EvidenceActivityProtocol.ActivityRequest request =
        new EvidenceActivityProtocol.ActivityRequest(
            "evidence-activity-request.v1",
            ref.operationType(),
            ref.tenantSurrogate(),
            ref.caseId(),
            ref.roomEpoch(),
            ref.fencingToken(),
            ref.manifestHash(),
            ref.processRevision(),
            ref.roomRevision(),
            ref.operationKey(),
            ref.requestHash(),
            EvidenceActivityProtocol.InvocationMode.RETRY_RECONCILE_ONLY);
    EvidenceFinalizationReceiptLookup lookup = lookupReturning(committed);

    assertThat(lookup.lookupForActivity(request).receipt()).isEqualTo(ref);

    EvidenceActivityProtocol.ActivityRequest conflict =
        new EvidenceActivityProtocol.ActivityRequest(
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
            "9".repeat(64),
            request.invocationMode());
    assertThatThrownBy(() -> lookup.findForActivity(conflict))
        .isInstanceOf(ReceiptReferenceRejectedException.class)
        .extracting(failure -> ((ReceiptReferenceRejectedException) failure).rejection())
        .isEqualTo(EvidenceFinalizationReceiptLookup.Rejection.CONFLICTING);
  }

  @Test
  void sidecarRejectsConflatedFencesAndCannotBeBuiltFromTemporalState() {
    EvidenceFinalizationReceipt receipt = receipt(COMMITTED_AT, REQUEST_HASH);
    EvidenceCurrentAuthoritySnapshot authority = authority();

    assertThatThrownBy(() -> EvidenceTerminalSummary.create(receipt, authority, 7, 9001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fences differ");
    assertThatThrownBy(() -> EvidenceTerminalSummary.create(receipt, authority, 7001, 7001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fences differ");
  }

  private static void assertRejected(
      CommittedFinalization committed, EvidenceFinalizationReceiptRef reference, String rejection) {
    assertThatThrownBy(() -> committed.requireReference(reference))
        .isInstanceOf(ReceiptReferenceRejectedException.class)
        .extracting(failure -> ((ReceiptReferenceRejectedException) failure).rejection().name())
        .isEqualTo(rejection);
  }

  private static CommittedFinalization require(
      CommittedFinalization committed, EvidenceFinalizationReceiptRef reference) {
    committed.requireReference(reference);
    return committed;
  }

  private static EvidenceFinalizationReceiptLookup lookupReturning(
      CommittedFinalization committed) {
    return new EvidenceFinalizationReceiptLookup() {
      @Override
      public Optional<CommittedFinalization> findExact(
          EvidenceFinalizationReceiptRef reference) {
        if (committed == null) {
          return Optional.empty();
        }
        return Optional.of(require(committed, reference));
      }

      @Override
      public Optional<CommittedFinalization> findForActivity(
          EvidenceActivityProtocol.ActivityRequest request) {
        if (committed == null) {
          return Optional.empty();
        }
        committed.requireActivity(request);
        return Optional.of(committed);
      }
    };
  }

  private static EvidenceFinalizationReceiptRef copy(
      EvidenceFinalizationReceiptRef source,
      String tenant,
      long fence,
      String requestHash) {
    return new EvidenceFinalizationReceiptRef(
        source.schemaVersion(),
        source.receiptId(),
        source.receiptHash(),
        source.operationType(),
        source.operationKey(),
        requestHash,
        source.resultHash(),
        tenant,
        source.caseId(),
        source.roomEpoch(),
        fence,
        source.manifestHash(),
        source.processRevision(),
        source.roomRevision(),
        source.commitScope(),
        source.status(),
        source.formalDomainWrite(),
        source.formalSinkEligible());
  }

  private static CommittedFinalization committed() {
    EvidenceFinalizationReceipt receipt = receipt(COMMITTED_AT, REQUEST_HASH);
    return new CommittedFinalization(
        receipt, EvidenceTerminalSummary.create(receipt, authority(), 7001, 9001));
  }

  private static EvidenceFinalizationReceipt receipt(Instant committedAt, String requestHash) {
    BatchMergeBinding binding =
        new BatchMergeBinding(
            MANIFEST_HASH,
            1,
            PROPOSAL_HASH,
            "LOGICAL_RUN_1",
            "COMMAND_1",
            "ATTEMPT_1",
            "grt.v1.018f6b7ec30a7430982fffc520c8195c");
    return EvidenceFinalizationReceipt.committedSyntheticBatchMerge(
        "RECEIPT_" + requestHash.substring(0, 32),
        requestHash,
        RESULT_HASH,
        "TENANT_P5_SYNTHETIC_1",
        "CASE_P5_SYNTHETIC_1",
        1,
        7,
        3,
        4,
        5,
        binding,
        committedAt);
  }

  private static EvidenceCurrentAuthoritySnapshot authority() {
    return new EvidenceCurrentAuthoritySnapshot(
        AUTHORITY_HASH,
        "EVIDENCE_GRAPH_BINDING_P5_1",
        "SIGNED_SYNTHETIC_SHADOW",
        "evidence-clerk.v2",
        "TENANT_P5_SYNTHETIC_1",
        "CASE_P5_SYNTHETIC_1",
        "ROOM_P5_SYNTHETIC_1",
        1,
        7,
        "USER_P5_SYNTHETIC_1",
        "USER",
        "PARTICIPANT_P5_SYNTHETIC_1",
        ACTOR_SCOPE_HASH,
        "AGENT_SESSION_P5_1",
        3,
        4,
        5,
        List.of("FACT_ORDER_DAMAGE"),
        List.of("SOURCE_SYNTHETIC_001"));
  }
}
