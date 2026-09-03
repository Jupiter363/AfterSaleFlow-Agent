package com.example.dispute.workflow.targete2e.rooms.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetOutcomeCompletionBindingTest {

  @Test
  void convertsOutcomeWriteTimesToExplicitJdbcTimestamps() {
    Instant value = Instant.parse("2026-08-23T10:00:00.123456Z");

    assertThat(JdbcTargetOutcomeCompletionActivities.sqlTimestamp(value).toInstant())
        .isEqualTo(value);
  }

  @Test
  void beginsExecutionOnlyFromTheHumanApprovedCaseState() {
    assertThat(JdbcTargetOutcomeCompletionActivities.BEGIN_EXECUTION_SQL)
        .contains(
            "case_status = 'EXECUTING'",
            "case_status = 'APPROVED_FOR_EXECUTION'",
            "version = version + 1");
  }

  @Test
  void terminalizesTheEpochUsingOnlyColumnsOwnedByTheEpochTable() {
    assertThat(JdbcTargetOutcomeCompletionActivities.TERMINALIZE_EPOCH_SQL)
        .contains(
            "lifecycle_status = 'TERMINAL'",
            "process_revision = ?",
            "room_revision = ?",
            "terminal_at = ?")
        .doesNotContain("writer_activation_status");
  }

  @Test
  void bindsTheApprovalIdentityAndApprovedActionSnapshotSeparately() {
    assertThat(JdbcTargetTemporalOutcomeBindingResolver.BINDING_SQL)
        .contains(
            "approval.action_hash as approval_hash",
            "approval.action_snapshot_hash as approval_action_snapshot_hash")
        .contains("approval.reviewer_decision_action in")
        .contains("approval.approved_plan_json ->> 'decision_action' =");
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
            "approval.ai_decision_action =",
            "approval.reviewer_decision_action =",
            "approval.approved_plan_json ->> 'decision_action' =",
            "command.payload_sha256 = ?",
            "material.admission_id = admission.admission_id",
            "material.command_hash = admission.command_hash",
            "material.command_envelope_hash = admission.command_envelope_hash",
            "'{request,command,event_ref,sha256}'")
        .doesNotContain(
            "material.material_canonical_json::jsonb #>> '{request,command,request_hash}'",
            "command.request_hash = ?");
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

  @Test
  void terminalProgressAcceptsTheFirstZeroEpochButRejectsNegativeCoordinates() {
    assertThatCode(
            () ->
                new TargetOutcomeCompletionActivities.TerminalProgressRequest(
                    "outcome:CASE_1:0", "CASE_1", 0, 9, "APPROVAL_1", "a".repeat(64), 0))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                new TargetOutcomeCompletionActivities.TerminalProgressRequest(
                    "outcome:CASE_1:-1", "CASE_1", -1, 9, "APPROVAL_1", "a".repeat(64), 0))
        .isInstanceOf(IllegalArgumentException.class);
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
