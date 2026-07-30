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
  void resolvesTheReviewCommandFromItsDecisionPayloadAndExactAdmissionMaterial() {
    assertThat(JdbcTargetOutcomeCompletionActivities.COMMAND_ADMISSION_SQL)
        .contains(
            "command.command_type = 'REVIEW_DECISION'",
            "admission.tenant_surrogate = command.tenant_surrogate",
            "admission.command_id = command.command_id",
            "admission.room_epoch = command.room_epoch",
            "approval.id = ?",
            "command.payload_sha256 = ?",
            "material.admission_id = admission.admission_id",
            "material.command_hash = admission.command_hash",
            "material.command_envelope_hash = admission.command_envelope_hash",
            "'{request,command,event_ref,sha256}'",
            "'{request,command,request_hash}'",
            "command.request_hash")
        .doesNotContain("command.request_hash = ?");
  }

  @Test
  void requiresAnExactActivationCompletionAndAppliedResultIdentity() {
    String terminalHash = "c".repeat(64);

    assertThat(JdbcTargetOutcomeCompletionActivities.COMMAND_COMPLETION_SQL)
        .contains(
            "admission_id = ?",
            "activation_id = ?",
            "command_id = ?",
            "command_hash = ?",
            "command_envelope_hash = ?",
            "completion_hash = ?");
    assertThat(JdbcTargetOutcomeCompletionActivities.terminalResultUri(terminalHash))
        .isEqualTo("urn:target-e2e:outcome-terminal:" + terminalHash);
    assertThatThrownBy(() -> JdbcTargetOutcomeCompletionActivities.terminalResultUri("bad"))
        .isInstanceOf(IllegalArgumentException.class);
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
