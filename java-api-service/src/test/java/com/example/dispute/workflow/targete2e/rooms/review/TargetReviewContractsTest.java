package com.example.dispute.workflow.targete2e.rooms.review;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TargetReviewContractsTest {
  private static final String HASH = "a".repeat(64);

  @Test void outcomeHandoffReceiptRequiresAnExactHash() {
    assertDoesNotThrow(() -> new TargetReviewOutcomeHandoffPort.HandoffReceipt("HANDOFF_1", HASH));
    assertThrows(IllegalArgumentException.class,
        () -> new TargetReviewOutcomeHandoffPort.HandoffReceipt("HANDOFF_1", "bad"));
  }

  @Test void triggerRejectsUnknownSchemaBeforeAnyRequestCanBeReleased() {
    assertThrows(IllegalArgumentException.class, () -> new TargetReviewAgentRunTrigger(
        "unknown", "p9act.v1." + "b".repeat(32), HASH, "CMD_1", 0L, 1L, 0L, 0L, HASH, HASH, null));
  }

  @Test void humanDecisionAuthorityCannotBeRelabeledAsGraphAuthority() {
    assertThrows(IllegalArgumentException.class, () -> new TargetReviewHumanDecisionReceipt(
        TargetReviewHumanDecisionReceipt.SCHEMA_VERSION, "GRAPH", "DECISION_1", HASH, null));
  }

  @Test void outcomeRelayUsesOnlyTheDurableHandoffRoute() {
    assertDoesNotThrow(() -> new TargetReviewOutcomeHandoffActivities.RelayRequest(
        "p9act.v1." + "b".repeat(32), HASH, "tenant_1", "CASE_1", "CMD_1", 0L, 1L));
    assertThrows(IllegalArgumentException.class, () -> new TargetReviewOutcomeHandoffActivities.RelayRequest(
        "p9act.v1." + "b".repeat(32), "bad", "tenant_1", "CASE_1", "CMD_1", 0L, 1L));
  }
}
