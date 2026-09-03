package com.example.dispute.workflow.temporal.room.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeAttemptReconciliationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeCompensationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutcomeRoomWorkflowReplayTest {

  private static final String TASK_QUEUE = "phase7-outcome-replay-test";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private Worker worker;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(OutcomeRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void ambiguousAttemptMustReconcileBeforeClosureAndEvaluationCannotReopen() throws Exception {
    Started started = start("full-ordering", 2);
    OutcomeReviewDecisionReceipt decision = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.APPROVE, 0, 1, started.now().plusSeconds(1));
    started.workflow().reviewDecisionCommitted(decision);
    started.workflow().operationCommandCommitted(
        started.receipts().operationCommand(decision, 1, 1, 2, 1, true));
    started.workflow().operationCommandCommitted(
        started.receipts().operationCommand(decision, 2, 2, 3, 1, true));
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("EXECUTING"));

    started.workflow().operationReceiptCommitted(started.receipts().operationReceipt(
        1, OutcomeWireTypes.TerminalStatus.SUCCEEDED, 3, 4, true));
    OutcomeExecutionAttemptObservation observation = started.receipts().observation(2, 4, 5, true);
    started.workflow().attemptObservationCommitted(observation);
    OutcomeWorkflowDiagnostics reconciling = OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("RECONCILING"));
    assertThat(reconciling.ambiguousOperationId()).isEqualTo("OPERATION_2");
    assertThat(started.workflow().projection().unresolvedAmbiguousCount()).isEqualTo(1);

    OutcomeAttemptReconciliationReceipt reconciled = started.receipts().reconciliation(
        2,
        5,
        6,
        5,
        OutcomeWireTypes.ReconciliationResolution.CONFIRMED_SUCCESS,
        true);
    started.workflow().attemptReconciliationCommitted(reconciled);
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("CLOSURE_PENDING"));

    OutcomeClosureReceipt closure = started.receipts().closure(decision, 6, 7);
    started.workflow().closureReceiptCommitted(closure);
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("CLOSED"));

    OutcomeEvaluationReceipt failedEvaluation = started.receipts().evaluation(
        closure, OutcomeWireTypes.EvaluationStatus.FAILED, 7, 8);
    started.workflow().evaluationReceiptCommitted(failedEvaluation);
    OutcomeWorkflowDiagnostics stillClosed = OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.revision() == 8);
    assertThat(stillClosed.phase()).isEqualTo("CLOSED");
    assertThat(stillClosed.evaluationFailureCount()).isEqualTo(1);

    OutcomeEvaluationReceipt successfulEvaluation = started.receipts().evaluation(
        closure, OutcomeWireTypes.EvaluationStatus.SUCCEEDED, 8, 9);
    started.workflow().evaluationReceiptCommitted(successfulEvaluation);
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.EVALUATED);
    assertThat(result.terminalSuccessReceiptCount()).isEqualTo(2);
    assertThat(result.unresolvedAmbiguousCount()).isZero();
    assertThat(result.evaluationReceiptRef()).isEqualTo(successfulEvaluation.receiptId());

    replay(started.workflowId());
  }

  @Test
  void failedOperationCompensatesSuccessfulParentsInReverseOrderThenRequiresManualRecovery()
      throws Exception {
    Started started = start("compensation", 2);
    OutcomeReviewDecisionReceipt decision = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE,
        0,
        1,
        started.now().plusSeconds(1));
    started.workflow().reviewDecisionCommitted(decision);
    started.workflow().operationCommandCommitted(
        started.receipts().operationCommand(decision, 1, 1, 2, 1, true));
    started.workflow().operationCommandCommitted(
        started.receipts().operationCommand(decision, 2, 2, 3, 1, true));

    OutcomeOperationReceipt firstSuccess = started.receipts().operationReceipt(
        1, OutcomeWireTypes.TerminalStatus.SUCCEEDED, 3, 4, true);
    started.workflow().operationReceiptCommitted(firstSuccess);
    started.workflow().operationReceiptCommitted(started.receipts().operationReceipt(
        2, OutcomeWireTypes.TerminalStatus.FAILED, 4, 5, true));
    OutcomeWorkflowDiagnostics compensating = OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("COMPENSATING"));
    assertThat(compensating.compensationOrder()).containsExactly("OPERATION_1");

    OutcomeCompensationReceipt compensation = started.receipts().compensation(
        1,
        firstSuccess.receiptId(),
        firstSuccess.receiptHash(),
        1,
        OutcomeWireTypes.CompensationStatus.SUCCEEDED,
        5,
        6);
    started.workflow().compensationReceiptCommitted(compensation);
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.MANUAL_RECOVERY);
    assertThat(result.failedRequiredReceiptCount()).isEqualTo(1);
    assertThat(result.unresolvedManualRecoveryCount()).isEqualTo(1);
    assertThat(result.closureReceiptRef()).isNull();

    replay(started.workflowId());
  }

  @Test
  void replayPreservesPreDeadlineCommitDeliveredAfterTheTimer() throws Exception {
    Started started = start("late-delivery-replay", 0);
    OutcomeReviewDecisionReceipt committedBeforeDeadline = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.REJECT,
        0,
        1,
        started.now().plusSeconds(1));
    environment.sleep(Duration.ofMinutes(6));
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("WAITING_SLA_ESCALATION"));

    started.workflow().reviewDecisionCommitted(committedBeforeDeadline);
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.DECISION_COMMITTED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(committedBeforeDeadline.receiptId());
    replay(started.workflowId());
  }

  @Test
  void persistedExactSignalReplaysCoalesceWithoutConsumingUniqueInboxCapacity() throws Exception {
    Instant now = Instant.ofEpochMilli(environment.currentTimeMillis());
    OutcomeWorkflowStart start = OutcomeReceiptTestFactory.start(now, Duration.ofMinutes(5), 0);
    OutcomeReceiptTestFactory receipts = new OutcomeReceiptTestFactory(start);
    OutcomeReviewDecisionReceipt decision = receipts.decision(
        OutcomeWireTypes.ReviewDecision.APPROVE, 0, 1, now.plusSeconds(1));
    String workflowId = start.workflowId() + "_inbox-coalescing";
    OutcomeRoomWorkflow workflow = client.newWorkflowStub(
        OutcomeRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());

    worker.suspendPolling();
    try {
      WorkflowClient.start(workflow::run, start);
      for (int signal = 0; signal <= OutcomeRoomWorkflowImpl.MAX_INBOX_EVENTS; signal++) {
        workflow.reviewDecisionCommitted(decision);
      }
    } finally {
      worker.resumePolling();
    }

    OutcomeWorkflowDiagnostics coalesced = OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        workflow,
        value -> value.phase().equals("CLOSURE_PENDING")
            && value.duplicateSignalCount() == OutcomeRoomWorkflowImpl.MAX_INBOX_EVENTS);
    assertThat(coalesced.rejectedSignalCount()).isZero();
    assertThat(coalesced.orderedReceiptIds()).containsExactly(decision.receiptId());

    OutcomeClosureReceipt closure = receipts.closure(decision, 1, 2);
    workflow.closureReceiptCommitted(closure);
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        workflow, value -> value.phase().equals("CLOSED"));
    OutcomeEvaluationReceipt evaluation = receipts.evaluation(
        closure, OutcomeWireTypes.EvaluationStatus.SUCCEEDED, 2, 3);
    workflow.evaluationReceiptCommitted(evaluation);
    OutcomeProjection result = WorkflowStub.fromTyped(workflow).getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.EVALUATED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(decision.receiptId());
    assertThat(result.unresolvedManualRecoveryCount()).isZero();
    replay(workflowId);
  }

  @Test
  void sameReceiptIdWithDifferentPayloadReachesKernelConflictRejection() throws Exception {
    Instant now = Instant.ofEpochMilli(environment.currentTimeMillis());
    OutcomeWorkflowStart start = OutcomeReceiptTestFactory.start(now, Duration.ofMinutes(5), 0);
    OutcomeReceiptTestFactory receipts = new OutcomeReceiptTestFactory(start);
    OutcomeReviewDecisionReceipt approval = receipts.decision(
        OutcomeWireTypes.ReviewDecision.APPROVE, 0, 1, now.plusSeconds(1));
    OutcomeReviewDecisionReceipt conflicting = receipts.decision(
        OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE, 0, 1, now.plusSeconds(1));
    String workflowId = start.workflowId() + "_inbox-conflict";
    OutcomeRoomWorkflow workflow = client.newWorkflowStub(
        OutcomeRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());

    worker.suspendPolling();
    try {
      WorkflowClient.start(workflow::run, start);
      workflow.reviewDecisionCommitted(approval);
      workflow.reviewDecisionCommitted(conflicting);
    } finally {
      worker.resumePolling();
    }

    OutcomeWorkflowDiagnostics rejected = OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        workflow,
        value -> value.phase().equals("CLOSURE_PENDING") && value.rejectedSignalCount() == 1);
    assertThat(rejected.protocolErrorCode()).isEqualTo("OUTCOME_RECEIPT_ID_PAYLOAD_CONFLICT");
    assertThat(rejected.duplicateSignalCount()).isZero();

    OutcomeClosureReceipt closure = receipts.closure(approval, 1, 2);
    workflow.closureReceiptCommitted(closure);
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        workflow, value -> value.phase().equals("CLOSED"));
    OutcomeEvaluationReceipt evaluation = receipts.evaluation(
        closure, OutcomeWireTypes.EvaluationStatus.SUCCEEDED, 2, 3);
    workflow.evaluationReceiptCommitted(evaluation);
    OutcomeProjection result = WorkflowStub.fromTyped(workflow).getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.EVALUATED);
    replay(workflowId);
  }

  private Started start(String suffix, int operationCount) {
    Instant now = Instant.ofEpochMilli(environment.currentTimeMillis());
    OutcomeWorkflowStart start = OutcomeReceiptTestFactory.start(
        now, Duration.ofMinutes(5), operationCount);
    OutcomeReceiptTestFactory receipts = new OutcomeReceiptTestFactory(start);
    String workflowId = start.workflowId() + '_' + suffix;
    OutcomeRoomWorkflow workflow = client.newWorkflowStub(
        OutcomeRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    OutcomeRoomWorkflowTimerTest.awaitDiagnostics(
        workflow, value -> value.phase().equals("WAITING_REVIEW"));
    return new Started(workflow, workflowId, now, receipts);
  }

  private void replay(String workflowId) throws Exception {
    WorkflowExecutionHistory history = client.fetchHistory(workflowId);
    WorkflowReplayer.replayWorkflowExecution(history, OutcomeRoomWorkflowImpl.class);
  }

  private record Started(
      OutcomeRoomWorkflow workflow,
      String workflowId,
      Instant now,
      OutcomeReceiptTestFactory receipts) {}
}
