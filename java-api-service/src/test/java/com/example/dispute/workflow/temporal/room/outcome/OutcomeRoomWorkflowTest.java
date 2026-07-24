package com.example.dispute.workflow.temporal.room.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutcomeRoomWorkflowTest {

  private static final Instant OPENED_AT = Instant.parse("2026-07-24T08:00:00Z");

  @Test
  void exactlyFiveHumanDecisionsHaveClosedExecutionSemantics() {
    Map<OutcomeWorkflowKernel.Decision, OutcomeWorkflowKernel.Phase> expected = Map.of(
        OutcomeWorkflowKernel.Decision.APPROVE,
        OutcomeWorkflowKernel.Phase.EXECUTION_INTENT,
        OutcomeWorkflowKernel.Decision.MODIFY_AND_APPROVE,
        OutcomeWorkflowKernel.Phase.EXECUTION_INTENT,
        OutcomeWorkflowKernel.Decision.REQUEST_MORE_EVIDENCE,
        OutcomeWorkflowKernel.Phase.MORE_EVIDENCE_REQUESTED,
        OutcomeWorkflowKernel.Decision.REJECT,
        OutcomeWorkflowKernel.Phase.REJECTED,
        OutcomeWorkflowKernel.Decision.ESCALATE_MANUAL,
        OutcomeWorkflowKernel.Phase.MANUAL_ESCALATED);

    expected.forEach((decision, phase) -> {
      OutcomeWorkflowKernel kernel = kernel();
      kernel.submit(decision(decision, 0, 1, "DECISION_" + decision.name()));
      OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
      assertThat(state.phase()).isEqualTo(phase);
      assertThat(state.decision()).isEqualTo(decision);
      assertThat(state.requiredOperationCount())
          .isEqualTo(decision.authorizesExecution() ? 2 : 0);
      assertThat(state.operations()).isEmpty();
    });
  }

  @Test
  void systemSlaEscalationIsNotAHumanDecisionAndNeverAuthorizesExecution() {
    OutcomeWorkflowKernel kernel = kernel();
    OutcomeWorkflowKernel.SlaReceipt early = sla(0, 1, "SLA_RECEIPT");
    kernel.submit(early);
    assertThat(kernel.snapshot().phase()).isEqualTo(OutcomeWorkflowKernel.Phase.WAITING_REVIEW);
    assertThat(kernel.snapshot().pendingRevisions()).containsExactly(1L);

    kernel.deadlineReached();
    OutcomeWorkflowKernel.Snapshot escalated = kernel.snapshot();
    assertThat(escalated.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.SLA_ESCALATED);
    assertThat(escalated.decision()).isNull();
    assertThat(escalated.requiredOperationCount()).isZero();
    assertThat(escalated.operations()).isEmpty();
  }

  @Test
  void deduplicatesBuffersGapsAndRejectsConflictsAndStaleFence() {
    OutcomeWorkflowKernel kernel = kernel();
    OutcomeWorkflowKernel.DecisionReceipt approval = decision(
        OutcomeWorkflowKernel.Decision.APPROVE, 0, 1, "DECISION_APPROVE");
    kernel.submit(approval);
    kernel.submit(approval);

    OutcomeWorkflowKernel.OperationCommandReceipt second = command(2, 2, 3, "COMMAND_2");
    kernel.submit(second);
    assertThat(kernel.snapshot().pendingRevisions()).containsExactly(3L);

    OutcomeWorkflowKernel.OperationCommandReceipt first = command(1, 1, 2, "COMMAND_1");
    kernel.submit(first);
    OutcomeWorkflowKernel.Snapshot ordered = kernel.snapshot();
    assertThat(ordered.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTING);
    assertThat(ordered.revision()).isEqualTo(3);
    assertThat(ordered.operations())
        .extracting(OutcomeWorkflowKernel.OperationSnapshot::operationId)
        .containsExactly("OPERATION_1", "OPERATION_2");
    assertThat(ordered.duplicateSignalCount()).isEqualTo(1);

    OutcomeWorkflowKernel.OperationCommandReceipt semanticReplay =
        new OutcomeWorkflowKernel.OperationCommandReceipt(
            authority(), "COMMAND_2_REPLAY", hash("command:COMMAND_2_REPLAY"), 3, 4, 4,
            "OPERATION_2", hash("operation-key:2"), hash("operation-request:2"),
            hash("idempotency:2"), 2, true, true, 1,
            com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode.TEMPORAL,
            false, false);
    kernel.submit(semanticReplay);
    assertThat(kernel.snapshot().revision()).isEqualTo(4);
    assertThat(kernel.snapshot().operations()).hasSize(2);

    kernel.submit(new OutcomeWorkflowKernel.OperationCommandReceipt(
        authority(), "COMMAND_2", hash("different-command"), 4, 5, 5,
        "OPERATION_2", hash("operation-key:2"), hash("operation-request:2"),
        hash("idempotency:2"), 2, true, true, 2,
        com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false, false));
    kernel.submit(new OutcomeWorkflowKernel.OperationReceipt(
        new OutcomeWorkflowKernel.Authority("OUTCOME_WORKFLOW", "CASE_P7", 7, 70),
        "STALE_FENCE_RECEIPT", hash("stale-fence"), 4, 5, 6,
        "OPERATION_1", hash("operation-key:1"), hash("operation-request:1"),
        hash("idempotency:1"), 1, true, true,
        OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED, "ref:result:1", hash("result:1"),
        com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false));

    OutcomeWorkflowKernel.Snapshot rejected = kernel.snapshot();
    assertThat(rejected.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTING);
    assertThat(rejected.rejectedSignalCount()).isEqualTo(2);
    assertThat(rejected.revision()).isEqualTo(4);
  }

  private static OutcomeWorkflowKernel kernel() {
    return new OutcomeWorkflowKernel(new OutcomeWorkflowKernel.Start(
        "OUTCOME_WORKFLOW", "CASE_P7", 7, 71, 0, OPENED_AT, OPENED_AT.plusSeconds(30),
        com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false));
  }

  private static OutcomeWorkflowKernel.Authority authority() {
    return new OutcomeWorkflowKernel.Authority("OUTCOME_WORKFLOW", "CASE_P7", 7, 71);
  }

  private static OutcomeWorkflowKernel.DecisionReceipt decision(
      OutcomeWorkflowKernel.Decision decision,
      long sourceRevision,
      long eventSequence,
      String receiptId) {
    boolean approval = decision.authorizesExecution();
    return new OutcomeWorkflowKernel.DecisionReceipt(
        authority(), receiptId, hash("receipt:" + receiptId), sourceRevision, sourceRevision + 1,
        eventSequence, decision, approval ? hash("decision-operation") : null,
        hash("decision-request:" + receiptId), approval ? "ref:approved-action" : null,
        approval ? hash("approved-action") : null, approval ? "ref:operation-set" : null,
        approval ? hash("operation-set") : null, approval ? 2 : 0,
        OPENED_AT.plusSeconds(1), false);
  }

  private static OutcomeWorkflowKernel.SlaReceipt sla(
      long sourceRevision, long eventSequence, String receiptId) {
    return new OutcomeWorkflowKernel.SlaReceipt(
        authority(), receiptId, hash("receipt:" + receiptId), sourceRevision, sourceRevision + 1,
        eventSequence, OPENED_AT.plusSeconds(30), OPENED_AT.plusSeconds(30), false);
  }

  private static OutcomeWorkflowKernel.OperationCommandReceipt command(
      int sequence, long sourceRevision, long eventSequence, String commandId) {
    return new OutcomeWorkflowKernel.OperationCommandReceipt(
        authority(), commandId, hash("command:" + commandId), sourceRevision, sourceRevision + 1,
        eventSequence, "OPERATION_" + sequence, hash("operation-key:" + sequence),
        hash("operation-request:" + sequence), hash("idempotency:" + sequence), sequence, true,
        true, 1,
        com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false, false);
  }

  private static String hash(String value) {
    return OutcomeReceiptTestFactory.hash(value);
  }
}
