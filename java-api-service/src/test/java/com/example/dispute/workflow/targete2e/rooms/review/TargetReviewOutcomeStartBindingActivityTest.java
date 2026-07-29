package com.example.dispute.workflow.targete2e.rooms.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetReviewOutcomeStartBindingActivityTest {
  private static final Instant OPENED = Instant.parse("2026-07-28T09:00:00Z");

  @Test
  void mapsMissingProvisionToNonRetryableControlFailure() {
    var activity = new TargetReviewOutcomeStartBindingActivity(provision -> {
      throw new IllegalStateException("frozen review task is absent");
    });

    assertThatThrownBy(() -> activity.bind(null))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("provision")
        .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
            ((ApplicationFailure) failure).isNonRetryable()).isTrue());
  }

  @Test
  void acceptsApproveModifyAndRejectAgainstTheSameFrozenExecutionContract() {
    var binding = new TargetReviewOutcomeStartBindingPort.Binding(
        "p9act.v1." + "a".repeat(32), hash("manifest"), start());
    binding.requireCompatible(receipt(OutcomeWireTypes.ReviewDecision.APPROVE));
    binding.requireCompatible(receipt(OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE));
    binding.requireCompatible(receipt(OutcomeWireTypes.ReviewDecision.REJECT));

    assertThatThrownBy(() -> binding.requireCompatible(receiptWithPolicyDrift()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("durable start binding");
  }

  @Test
  void derivesDecisionRevisionsFromFrozenRoomRevision() {
    var contract = TargetReviewFrozenExecutionContract.fromFrozenFacts(
        "PACKET_1", hash("action"), "{\"actions\":[{\"action_type\":\"REFUND\"}],\"notifications\":[\"EMAIL\"]}",
        new ObjectMapper(), 9);

    org.assertj.core.api.Assertions.assertThat(contract.requiredOperationCount()).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(contract.kernelRevision()).isEqualTo(9);
    org.assertj.core.api.Assertions.assertThat(contract.decisionSourceRevision()).isEqualTo(9);
    org.assertj.core.api.Assertions.assertThat(contract.decisionRevision()).isEqualTo(10);
  }

  @Test
  void targetNoExternalEffectManifestFreezesOneRequiredOperation() {
    var contract = TargetReviewFrozenExecutionContract.fromFrozenFacts(
        "PACKET_1", hash("action"), """
            {"actions":[{"action_type":"TARGET_NO_EXTERNAL_EFFECT",
            "effect_class":"NO_EXTERNAL_EFFECT","idempotency_key":"target-no-external-effect:abc",
            "schema_version":"target-no-external-effect.v1"}],"notifications":[]}
            """, new ObjectMapper(), 9);

    org.assertj.core.api.Assertions.assertThat(contract.requiredOperationCount()).isEqualTo(1);
  }

  private static OutcomeWorkflowStart start() {
    return new OutcomeWorkflowStart(
        OutcomeWorkflowStart.SCHEMA_VERSION, "outcome:CASE_1:4", "CASE_1", "REVIEW_1", "PACKET_1",
        hash("packet"), "PACKET_1:draft", hash("draft"), "review-packet:PACKET_1:action", hash("action"),
        "review-packet:PACKET_1:operations", hash("operations"), 1, 4, 2, 7, OPENED, OPENED.plusSeconds(60),
        OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW, "review-build.v1", "policy.v1",
        "graph.v1", "prompt.v1", "profile.v1", true);
  }

  private static OutcomeReviewDecisionReceipt receipt(OutcomeWireTypes.ReviewDecision decision) {
    boolean approved = decision != OutcomeWireTypes.ReviewDecision.REJECT;
    return new OutcomeReviewDecisionReceipt(
        OutcomeReviewDecisionReceipt.SCHEMA_VERSION, "outcome:CASE_1:4", "CASE_1", "RECEIPT_1",
        hash("receipt"), "REVIEW_1", "reviewer-authority:" + hash("reviewer"), "PACKET_1", hash("packet"),
        "review-packet:PACKET_1:action", hash("action"), approved ? "approval:DECISION_1:action" : null,
        approved ? (decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE ? hash("modified") : hash("action")) : null,
        "DECISION_1", hash("decision"), null, null, approved ? hash("operation") : null,
        "review-packet:PACKET_1:operations", hash("operations"), 1, decision, approved,
        hash("request"), hash("idempotency"), "policy.v1",
        4, 2, 3, 7, 1, OPENED.plusSeconds(1), true);
  }

  private static OutcomeReviewDecisionReceipt receiptWithPolicyDrift() {
    var receipt = receipt(OutcomeWireTypes.ReviewDecision.APPROVE);
    return new OutcomeReviewDecisionReceipt(
        receipt.schemaVersion(), receipt.workflowId(), receipt.caseId(), receipt.receiptId(), receipt.receiptHash(),
        receipt.reviewTaskId(), receipt.reviewerAuthorityRef(), receipt.frozenReviewPacketRef(),
        receipt.frozenReviewPacketHash(), receipt.actionSnapshotRef(), receipt.actionSnapshotHash(),
        receipt.approvedActionSnapshotRef(), receipt.approvedActionSnapshotHash(), receipt.decisionRecordRef(),
        receipt.decisionRecordHash(), receipt.reasonRef(), receipt.reasonHash(), receipt.operationKeyHash(),
        receipt.requiredOperationSetRef(), receipt.requiredOperationSetHash(), receipt.requiredOperationCount(),
        receipt.decision(), receipt.executionAuthorized(), receipt.requestHash(), receipt.idempotencyKeyHash(),
        "policy.v2", receipt.epoch(), receipt.sourceRevision(), receipt.revision(), receipt.fence(),
        receipt.committedEventSequence(), receipt.committedAt(), receipt.syntheticOnly());
  }

  private static String hash(String value) {
    return ("modified".equals(value) ? "b" : "a").repeat(64);
  }
}
