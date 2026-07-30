package com.example.dispute.workflow.targete2e.rooms.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.CompletionResult;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.DispositionReceipt;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities.EvidenceTransition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcTargetReviewNonExecutionActivitiesTest {
  private static final Instant NOW = Instant.parse("2026-07-30T03:00:00Z");

  @Test
  void acceptsExactlyTheThreeNonExecutableHumanDecisions() {
    for (OutcomeWireTypes.ReviewDecision decision :
        List.of(
            OutcomeWireTypes.ReviewDecision.REJECT,
            OutcomeWireTypes.ReviewDecision.REQUEST_MORE_EVIDENCE,
            OutcomeWireTypes.ReviewDecision.ESCALATE_MANUAL)) {
      OutcomeReviewDecisionReceipt receipt = decision(decision);
      assertThatCode(
              () ->
                  JdbcTargetReviewNonExecutionActivities.requireRequest(
                      start(), receipt, command(receipt)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void rejectsExecutableDecisionBeforeAnyDatabaseWrite() {
    OutcomeReviewDecisionReceipt receipt = decision(OutcomeWireTypes.ReviewDecision.APPROVE);

    assertThatThrownBy(
            () ->
                JdbcTargetReviewNonExecutionActivities.requireRequest(
                    start(), receipt, command(receipt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inconsistent");
  }

  @Test
  void requestMoreEvidenceReceiptKeepsTheSourceReviewProgressNonTerminal() {
    String receiptHash = hash("disposition");
    DispositionReceipt receipt =
        disposition(
            OutcomeWireTypes.ReviewDecision.REQUEST_MORE_EVIDENCE,
            new EvidenceTransition(
                "epoch-evidence-2",
                "room-evidence",
                2,
                18,
                8,
                0,
                "room-workflow:CASE_1:EVIDENCE:2",
                NOW.plusSeconds(7200)));

    CompletionResult result = new CompletionResult(receipt, receiptHash);

    assertThat(result.terminalCaseProcess()).isFalse();
    assertThat(result.sourceProgressReceipt().roomType()).isEqualTo(RoomType.REVIEW);
    assertThat(result.sourceProgressReceipt().processRevision()).isEqualTo(8);
    assertThat(result.sourceProgressReceipt().roomRevision()).isEqualTo(4);
    assertThat(result.sourceProgressReceipt().javaReceiptHash()).isEqualTo(receiptHash);
    assertThat(JdbcTargetReviewNonExecutionActivities.validEvidenceTransition(receipt)).isTrue();
  }

  @Test
  void requestMoreEvidenceRejectsAReceiptBoundToTheCaseWorkflowInsteadOfTheRoomWorkflow() {
    DispositionReceipt receipt =
        disposition(
            OutcomeWireTypes.ReviewDecision.REQUEST_MORE_EVIDENCE,
            new EvidenceTransition(
                "epoch-evidence-2",
                "room-evidence",
                2,
                18,
                8,
                0,
                "case:tenant-1:CASE_1",
                NOW.plusSeconds(7200)));

    assertThat(JdbcTargetReviewNonExecutionActivities.validEvidenceTransition(receipt)).isFalse();
  }

  @Test
  void rejectAndManualEscalationAreTerminalWithoutEvidenceCoordinates() {
    for (OutcomeWireTypes.ReviewDecision decision :
        List.of(
            OutcomeWireTypes.ReviewDecision.REJECT,
            OutcomeWireTypes.ReviewDecision.ESCALATE_MANUAL)) {
      CompletionResult result = new CompletionResult(disposition(decision, null), hash(decision.name()));
      assertThat(result.terminalCaseProcess()).isTrue();
      assertThat(result.receipt().evidenceTransition()).isNull();
    }
  }

  @Test
  void authorityQueryAllowsExpiredActiveOnlyThroughStoredAppliedReplay() {
    assertThat(JdbcTargetReviewNonExecutionActivities.AUTHORITY_SQL)
        .contains("'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL'")
        .contains("activation.expires_at > clock_timestamp()")
        .contains("as accepts_new_write");
    assertThat(JdbcTargetReviewNonExecutionActivities.resultUri(hash("receipt")))
        .isEqualTo(JdbcTargetReviewNonExecutionActivities.RESULT_URI_PREFIX + hash("receipt"));
  }

  @Test
  void appliedReplayAcceptsEveryLegalNextEvidenceProvisioningWindow() {
    String workflowId = "room:CASE_1:EVIDENCE:2";

    assertThat(
            JdbcTargetReviewNonExecutionActivities.validProvisioningIdentity(
                "PREPARING", null, workflowId, null, workflowId))
        .isTrue();
    assertThat(
            JdbcTargetReviewNonExecutionActivities.validProvisioningIdentity(
                "PROVISIONING", null, workflowId, null, workflowId))
        .isTrue();
    assertThat(
            JdbcTargetReviewNonExecutionActivities.validProvisioningIdentity(
                "ACTIVE", "case-first-run", workflowId, "room-run", workflowId))
        .isTrue();
    assertThat(
            JdbcTargetReviewNonExecutionActivities.validProvisioningIdentity(
                "PROVISIONING", "unexpected-run", workflowId, null, workflowId))
        .isFalse();
  }

  private static DispositionReceipt disposition(
      OutcomeWireTypes.ReviewDecision decision, EvidenceTransition evidence) {
    return new DispositionReceipt(
        DispositionReceipt.SCHEMA_VERSION,
        "RVNEX_12345678901234567890123456789012",
        "tenant-1",
        "CASE_1",
        "case:tenant-1:CASE_1",
        "run-1",
        "review-command-1",
        "DECISION_1",
        hash("decision"),
        decision,
        4,
        17,
        8,
        4,
        evidence,
        NOW);
  }

  private static OutcomeWorkflowStart start() {
    return new OutcomeWorkflowStart(
        OutcomeWorkflowStart.SCHEMA_VERSION,
        "outcome:CASE_1:4",
        "CASE_1",
        "REVIEW_1",
        "PACKET_1",
        hash("packet"),
        "PACKET_1:draft",
        hash("draft"),
        "review-packet:PACKET_1:action",
        hash("action"),
        "review-packet:PACKET_1:operations",
        hash("operations"),
        1,
        4,
        2,
        17,
        NOW.minusSeconds(60),
        NOW.plusSeconds(3600),
        OutcomeWireTypes.RuntimeMode.TEMPORAL,
        "review-build.v1",
        "policy.v1",
        "graph.v1",
        "prompt.v1",
        "profile.v1",
        false);
  }

  private static OutcomeReviewDecisionReceipt decision(
      OutcomeWireTypes.ReviewDecision decision) {
    boolean approved =
        decision == OutcomeWireTypes.ReviewDecision.APPROVE
            || decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE;
    return new OutcomeReviewDecisionReceipt(
        OutcomeReviewDecisionReceipt.SCHEMA_VERSION,
        "outcome:CASE_1:4",
        "CASE_1",
        "DECISION_1",
        hash("decision"),
        "REVIEW_1",
        "reviewer-authority:1",
        "PACKET_1",
        hash("packet"),
        "review-packet:PACKET_1:action",
        hash("action"),
        approved ? "approval:DECISION_1:action" : null,
        approved ? hash("action") : null,
        "DECISION_1",
        hash("decision"),
        null,
        null,
        approved ? hash("operation") : null,
        "review-packet:PACKET_1:operations",
        hash("operations"),
        1,
        decision,
        approved,
        hash("request"),
        hash("idempotency"),
        "policy.v1",
        4,
        2,
        3,
        17,
        11,
        NOW,
        false);
  }

  private static CaseCommandRef command(OutcomeReviewDecisionReceipt decision) {
    return new CaseCommandRef(
        "case-command-ref.v1",
        "review-command-1",
        "tenant-1",
        "CASE_1",
        9,
        CommandType.REVIEW_DECISION,
        RoomType.REVIEW,
        4,
        new ActorRef("reviewer-1", ActorRole.PLATFORM_REVIEWER, List.of("review:decide")),
        new PayloadRef(
            "target-e2e-review-human-decision-event.v1",
            "urn:target-e2e:review-decision:event-1",
            decision.decisionRecordHash(),
            128),
        7,
        NOW.minusSeconds(5),
        NOW.plusSeconds(600),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        hash("request"));
  }

  private static String hash(String seed) {
    char value = (char) ('a' + Math.floorMod(seed.hashCode(), 6));
    return String.valueOf(value).repeat(64);
  }
}
