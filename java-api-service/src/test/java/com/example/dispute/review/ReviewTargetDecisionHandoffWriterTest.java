package com.example.dispute.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewTargetDecisionHandoffWriterTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void approveReplaysTheExactFrozenActionAndReceiptBinding() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);

    JsonNode operation = ReviewTargetDecisionHandoffWriter.requireApprovedOperation(
        mapper, ApprovalDecisionType.APPROVE, "APPROVAL_1", frozenHash, frozenHash,
        frozen, frozen.deepCopy(), OutcomeWireTypes.ReviewDecision.APPROVE,
        "APPROVAL_1", "e".repeat(64), "APPROVAL_1", frozenHash);

    assertThat(operation.path("idempotency_key").asText()).isEqualTo("frozen-key");
    assertThat(operation.path("marker").asText()).isEqualTo("frozen");
  }

  @Test
  void modifyAndApproveSelectsOnlyTheChangedApprovedAction() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    JsonNode approved = plan("approved-key", "approved");
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);
    String approvedHash = ActionSnapshotHasher.hash(mapper, approved);

    JsonNode operation = ReviewTargetDecisionHandoffWriter.requireApprovedOperation(
        mapper, ApprovalDecisionType.MODIFY_AND_APPROVE, "APPROVAL_2", approvedHash,
        frozenHash, frozen, approved, OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE,
        "APPROVAL_2", "f".repeat(64), "APPROVAL_2", approvedHash);

    assertThat(operation.path("idempotency_key").asText()).isEqualTo("approved-key");
    assertThat(operation.path("marker").asText()).isEqualTo("approved");
  }

  @Test
  void rejectsModifyThatReusesTheFrozenPlanAndHash() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);

    assertThatThrownBy(() -> ReviewTargetDecisionHandoffWriter.requireApprovedOperation(
        mapper, ApprovalDecisionType.MODIFY_AND_APPROVE, "APPROVAL_3", frozenHash,
        frozenHash, frozen, frozen.deepCopy(), OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE,
        "APPROVAL_3", "a".repeat(64), "APPROVAL_3", frozenHash))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed approved remedy");
  }

  @Test
  void rejectsAnApprovedPlanThatDoesNotBindTheDecisionReceipt() throws Exception {
    JsonNode approved = plan("approved-key", "approved");
    JsonNode frozen = plan("frozen-key", "frozen");
    String approvedHash = ActionSnapshotHasher.hash(mapper, approved);
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);

    assertThatThrownBy(() -> ReviewTargetDecisionHandoffWriter.requireApprovedOperation(
        mapper, ApprovalDecisionType.MODIFY_AND_APPROVE, "APPROVAL_4", approvedHash,
        frozenHash, frozen, approved, OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE,
        "APPROVAL_OTHER", "b".repeat(64), "APPROVAL_4", approvedHash))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("human decision receipt");
  }

  @Test
  void exactHandoffReplayReturnsTheDurableSelfHash() throws Exception {
    String canonical = handoff(7, "receipt-1");
    String durableJson = mapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(mapper.readTree(canonical));
    NotificationOutboxEntity durable = NotificationOutboxEntity.pending(
        "HANDOFF_1", "CASE_1", "target-review-handoff:key", "TARGET_REVIEW_OUTCOME_HANDOFF",
        durableJson, Instant.parse("2026-07-30T01:00:00Z"));

    var replay = ReviewTargetDecisionHandoffWriter.requireExactReplay(
        mapper, durable, "HANDOFF_1", "CASE_1", "target-review-handoff:key",
        "TARGET_REVIEW_OUTCOME_HANDOFF", canonical);

    assertThat(replay.handoffId()).isEqualTo("HANDOFF_1");
    assertThat(replay.handoffHash())
        .isEqualTo(mapper.readTree(canonical).path("handoff_hash").asText());
  }

  @Test
  void rejectsHandoffReplayWithTheSameKeyButDifferentEpoch() {
    String durablePayload = handoff(7, "receipt-1");
    String currentPayload = handoff(8, "receipt-1");
    NotificationOutboxEntity durable = NotificationOutboxEntity.pending(
        "HANDOFF_1", "CASE_1", "target-review-handoff:key", "TARGET_REVIEW_OUTCOME_HANDOFF",
        durablePayload, Instant.parse("2026-07-30T01:00:00Z"));

    assertThatThrownBy(() -> ReviewTargetDecisionHandoffWriter.requireExactReplay(
        mapper, durable, "HANDOFF_1", "CASE_1", "target-review-handoff:key",
        "TARGET_REVIEW_OUTCOME_HANDOFF", currentPayload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("payload conflicts");
  }

  @Test
  void rejectsHandoffReplayWithTheSameKeyButDifferentHumanDecision() {
    String durablePayload = handoff(7, "receipt-1");
    String currentPayload = handoff(7, "receipt-2");
    NotificationOutboxEntity durable = NotificationOutboxEntity.pending(
        "HANDOFF_1", "CASE_1", "target-review-handoff:key", "TARGET_REVIEW_OUTCOME_HANDOFF",
        durablePayload, Instant.parse("2026-07-30T01:00:00Z"));

    assertThatThrownBy(() -> ReviewTargetDecisionHandoffWriter.requireExactReplay(
        mapper, durable, "HANDOFF_1", "CASE_1", "target-review-handoff:key",
        "TARGET_REVIEW_OUTCOME_HANDOFF", currentPayload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("payload conflicts");
  }

  @Test
  void rejectsHandoffReplayWhoseDurableSelfHashIsInvalid() throws Exception {
    ObjectNode corrupted = (ObjectNode) mapper.readTree(handoff(7, "receipt-1"));
    corrupted.put("handoff_hash", "0".repeat(64));
    String corruptedCanonical = ContractJson.canonicalString(corrupted);
    NotificationOutboxEntity durable = NotificationOutboxEntity.pending(
        "HANDOFF_1", "CASE_1", "target-review-handoff:key", "TARGET_REVIEW_OUTCOME_HANDOFF",
        corruptedCanonical, Instant.parse("2026-07-30T01:00:00Z"));

    assertThatThrownBy(() -> ReviewTargetDecisionHandoffWriter.requireExactReplay(
        mapper, durable, "HANDOFF_1", "CASE_1", "target-review-handoff:key",
        "TARGET_REVIEW_OUTCOME_HANDOFF", corruptedCanonical))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("payload conflicts");
  }

  private JsonNode plan(String idempotencyKey, String marker) throws Exception {
    return mapper.readTree("""
        {
          "id": "PLAN_1",
          "version": 7,
          "actions": [{
            "action_type": "TARGET_NO_EXTERNAL_EFFECT",
            "effect_class": "NO_EXTERNAL_EFFECT",
            "idempotency_key": "%s",
            "marker": "%s"
          }],
          "preconditions": [],
          "notifications": []
        }
        """.formatted(idempotencyKey, marker));
  }

  private String handoff(long roomEpoch, String receiptId) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schema_version", "target-e2e-review-outcome-handoff.v1");
    payload.put("handoff_id", "HANDOFF_1");
    payload.put("activation_id", "p9act.v1." + "a".repeat(32));
    payload.put("activation_manifest_hash", "b".repeat(64));
    payload.put("tenant_surrogate", "TENANT_1");
    payload.put("case_id", "CASE_1");
    payload.put("command_id", "COMMAND_1");
    payload.put("room_epoch", roomEpoch);
    payload.put("room_fencing_token", 11);
    payload.putObject("human_decision").put("receipt_id", receiptId);
    payload.put("handoff_hash", ContractJson.sha256Hex(payload));
    return ContractJson.canonicalString(payload);
  }
}
