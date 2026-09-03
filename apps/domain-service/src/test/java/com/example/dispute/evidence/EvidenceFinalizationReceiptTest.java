package com.example.dispute.evidence.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.CommitRequest;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.Lookup;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.OperationType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceFinalizationReceiptTest {

  @Test
  void readsTheFullCanonicalContractAndConvertsThroughTheExplicitB2Boundary() throws Exception {
    ObjectNode fixture =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    EvidenceFinalizationReceipt receipt = fromFixture(fixture);
    EvidenceFinalizationReceiptRef ref = receipt.toSyntheticReceiptRef();

    assertThat(ContractJson.canonicalString(receipt.toContractJson()))
        .isEqualTo(ContractJson.canonicalString(fixture));
    assertThat(ref.receiptHash()).isEqualTo(receipt.receiptHash());
    assertThat(ref.formalDomainWrite()).isFalse();
    assertThat(ref.formalSinkEligible()).isFalse();
  }

  @Test
  void workflowLookupReturnsTheCommittedReferenceAfterResponseLoss() throws Exception {
    ObjectNode fixture =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    EvidenceFinalizationReceipt receipt = fromFixture(fixture);
    EvidenceFinalizationLedger ledger = ledger(receipt);
    EvidenceActivityProtocol.ActivityRequest request =
        new EvidenceActivityProtocol.ActivityRequest(
            "evidence-activity-request.v1",
            EvidenceFinalizationReceiptRef.OperationType.BATCH_MERGE,
            receipt.tenantSurrogate(),
            receipt.caseId(),
            receipt.roomEpoch(),
            receipt.fencingToken(),
            ((BatchMergeBinding) receipt.operationBinding()).manifestHash(),
            receipt.processRevision(),
            receipt.roomRevision(),
            receipt.operationKey(),
            receipt.requestHash(),
            EvidenceActivityProtocol.InvocationMode.RETRY_RECONCILE_ONLY);

    EvidenceActivityProtocol.ReceiptLookupResult lookup = ledger.lookupForWorkflow(request);

    assertThat(lookup.status()).isEqualTo(EvidenceActivityProtocol.ReceiptLookupStatus.COMMITTED);
    assertThat(lookup.receipt().matches(request)).isTrue();
  }

  @Test
  void rejectsCanonicalTamperingAndAnyClaimedFormalEffect() throws Exception {
    ObjectNode fixture =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    fixture.put("result_hash", "f".repeat(64));

    assertThatThrownBy(() -> fromFixture(fixture)).hasMessage("receiptHash is not canonical");

    ObjectNode clean =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    assertThatThrownBy(
            () ->
                new EvidenceFinalizationReceipt(
                    clean.required("schema_version").textValue(),
                    clean.required("receipt_id").textValue(),
                    clean.required("receipt_hash").textValue(),
                    OperationType.BATCH_MERGE,
                    clean.required("operation_key").textValue(),
                    clean.required("request_hash").textValue(),
                    clean.required("result_hash").textValue(),
                    clean.required("commit_scope").textValue(),
                    clean.required("status").textValue(),
                    true,
                    false,
                    clean.required("tenant_surrogate").textValue(),
                    clean.required("case_id").textValue(),
                    clean.required("room_epoch").longValue(),
                    clean.required("fencing_token").longValue(),
                    clean.required("source_revision").longValue(),
                    clean.required("process_revision").longValue(),
                    clean.required("room_revision").longValue(),
                    batchBinding((ObjectNode) clean.required("operation_binding")),
                    0,
                    List.of(),
                    List.of(),
                    false,
                    Instant.parse(clean.required("committed_at").textValue())))
        .hasMessage("synthetic receipt cannot authorize a formal write");
  }

  @Test
  void constructorEnforcesSyntheticPrefixesAndJsSafeRevisionBounds() throws Exception {
    ObjectNode fixture =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    fixture.put("tenant_surrogate", "TENANT_REAL_1");
    assertThatThrownBy(() -> fromFixture(fixture))
        .hasMessage("tenantSurrogate is not a Phase 5 synthetic tenant");

    ObjectNode oversized =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    oversized.put("process_revision", 9_007_199_254_740_992L);
    assertThatThrownBy(() -> fromFixture(oversized))
        .hasMessage("receipt epoch, fence, and revisions are invalid");
  }

  @Test
  void workflowLookupFindsSemanticKeyBeforeComparingRequestHash() throws Exception {
    ObjectNode fixture =
        (ObjectNode)
            EvidenceGraphCommandFactoryTest.MAPPER.readTree(
                EvidenceGraphCommandFactoryTest.EVIDENCE_FIXTURES
                    .resolve("evidence-finalization-receipt-valid.json")
                    .toFile());
    EvidenceFinalizationReceipt receipt = fromFixture(fixture);
    EvidenceActivityProtocol.ActivityRequest conflict =
        new EvidenceActivityProtocol.ActivityRequest(
            "evidence-activity-request.v1",
            EvidenceFinalizationReceiptRef.OperationType.BATCH_MERGE,
            receipt.tenantSurrogate(),
            receipt.caseId(),
            receipt.roomEpoch(),
            receipt.fencingToken(),
            ((BatchMergeBinding) receipt.operationBinding()).manifestHash(),
            receipt.processRevision(),
            receipt.roomRevision(),
            receipt.operationKey(),
            "f".repeat(64),
            EvidenceActivityProtocol.InvocationMode.RETRY_RECONCILE_ONLY);

    assertThatThrownBy(() -> ledger(receipt).lookupForWorkflow(conflict))
        .isInstanceOf(EvidenceFinalizationLedger.IdempotencyConflictException.class)
        .hasMessageContaining("different canonical request");
  }

  static EvidenceFinalizationReceipt fromFixture(ObjectNode value) {
    return new EvidenceFinalizationReceipt(
        value.required("schema_version").textValue(),
        value.required("receipt_id").textValue(),
        value.required("receipt_hash").textValue(),
        OperationType.valueOf(value.required("operation_type").textValue()),
        value.required("operation_key").textValue(),
        value.required("request_hash").textValue(),
        value.required("result_hash").textValue(),
        value.required("commit_scope").textValue(),
        value.required("status").textValue(),
        value.required("formal_domain_write").booleanValue(),
        value.required("formal_sink_eligible").booleanValue(),
        value.required("tenant_surrogate").textValue(),
        value.required("case_id").textValue(),
        value.required("room_epoch").longValue(),
        value.required("fencing_token").longValue(),
        value.required("source_revision").longValue(),
        value.required("process_revision").longValue(),
        value.required("room_revision").longValue(),
        batchBinding((ObjectNode) value.required("operation_binding")),
        value.required("merge_count").intValue(),
        List.of(),
        List.of(),
        value.required("hearing_opened").booleanValue(),
        Instant.parse(value.required("committed_at").textValue()));
  }

  private static BatchMergeBinding batchBinding(ObjectNode value) {
    return new BatchMergeBinding(
        value.required("manifest_hash").textValue(),
        value.required("dossier_target_version").longValue(),
        value.required("proposal_hash").textValue(),
        value.required("logical_run_id").textValue(),
        value.required("command_id").textValue(),
        value.required("attempt_id").textValue(),
        value.required("thread_id").textValue());
  }

  private static EvidenceFinalizationLedger ledger(EvidenceFinalizationReceipt receipt) {
    return new EvidenceFinalizationLedger() {
      @Override
      public Optional<EvidenceFinalizationReceipt> findCommitted(Lookup lookup) {
        return Optional.of(receipt);
      }

      @Override
      public EvidenceFinalizationReceipt commitOrReplay(CommitRequest request) {
        throw new AssertionError("lookup must not commit");
      }
    };
  }
}
