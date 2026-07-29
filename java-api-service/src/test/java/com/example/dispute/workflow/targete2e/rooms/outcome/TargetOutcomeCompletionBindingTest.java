package com.example.dispute.workflow.targete2e.rooms.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import org.junit.jupiter.api.Test;

class TargetOutcomeCompletionBindingTest {

  @Test
  void usesTheApprovedActionSnapshotRatherThanTheApprovalIdentityHash() {
    assertThat(JdbcTargetTemporalOutcomeBindingResolver.BINDING_SQL)
        .contains("approval.action_snapshot_hash as approval_action_hash")
        .doesNotContain("approval.action_hash as approval_action_hash");
  }

  @Test
  void scopesCompletionFactsToTheExactHumanReceipt() {
    assertThat(JdbcTargetOutcomeCompletionActivities.FACTS_SQL)
        .contains("human_receipt_id = ?", "human_receipt_hash = ?");
  }

  @Test
  void advancesIndependentProcessAndRoomRevisionsFromTheirOwnSources() {
    OutcomeCompletionRequest request = request(13, 8);

    assertThatCode(() -> JdbcTargetOutcomeCompletionActivities.requireInitialEpochCoordinates(
        request, 7, 8, 12, 7))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> JdbcTargetOutcomeCompletionActivities.requireInitialEpochCoordinates(
        request, 7, 8, 7, 7))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("revision is stale");
  }

  private static OutcomeCompletionRequest request(long processRevision, long roomRevision) {
    return new OutcomeCompletionRequest(
        RoomType.REVIEW,
        4,
        9,
        processRevision,
        roomRevision,
        "APPROVAL_1",
        "a".repeat(64),
        roomRevision);
  }
}
