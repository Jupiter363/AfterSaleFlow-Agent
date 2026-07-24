package com.example.dispute.workflow.temporal.room.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSlaEscalationReceipt;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutcomeRoomWorkflowTimerTest {

  private static final String TASK_QUEUE = "phase7-outcome-timer-test";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(OutcomeRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void earlySystemEscalationReceiptWaitsForTheImmutableDeadline() throws Exception {
    Started started = start(Duration.ofSeconds(3), 0);
    OutcomeSlaEscalationReceipt sla = started.receipts().sla(
        0, 1, started.start().reviewDeadlineAt());
    started.workflow().slaEscalationCommitted(sla);

    OutcomeWorkflowDiagnostics pending = awaitDiagnostics(
        started.workflow(), value -> value.pendingRevisions().contains(1L));
    assertThat(pending.phase()).isEqualTo("WAITING_REVIEW");
    assertThat(pending.reviewDeadlineReached()).isFalse();
    environment.sleep(Duration.ofSeconds(4));

    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.SLA_ESCALATED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(sla.receiptId());
    assertThat(result.terminalSuccessReceiptCount()).isZero();

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, OutcomeRoomWorkflowImpl.class);
  }

  @Test
  void sameBoundaryJavaDecisionIsIndependentOfTimerSignalDeliveryOrder() throws Exception {
    Started started = start(Duration.ofSeconds(3), 0);
    OutcomeReviewDecisionReceipt decision = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.REJECT,
        0,
        1,
        started.start().reviewDeadlineAt());
    long delayMillis = started.start().reviewDeadlineAt().toEpochMilli()
        - environment.currentTimeMillis();
    environment.registerDelayedCallback(
        Duration.ofMillis(delayMillis),
        () -> started.workflow().reviewDecisionCommitted(decision));

    environment.sleep(Duration.ofMillis(delayMillis + 1));
    OutcomeWorkflowDiagnostics resolved = awaitDiagnostics(
        started.workflow(),
        value -> value.phase().equals("REJECTED"));
    assertThat(resolved.rejectedSignalCount()).isZero();
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.DECISION_COMMITTED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(decision.receiptId());

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, OutcomeRoomWorkflowImpl.class);
  }

  @Test
  void preDeadlineJavaCommitSurvivesLateTransportDeliveryWithoutWaitingForSla() throws Exception {
    Started started = start(Duration.ofSeconds(3), 0);
    OutcomeReviewDecisionReceipt committedEarly = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.REJECT,
        0,
        1,
        started.start().reviewDeadlineAt().minusMillis(1));

    environment.sleep(Duration.ofSeconds(4));
    awaitDiagnostics(
        started.workflow(), value -> value.phase().equals("WAITING_SLA_ESCALATION"));
    started.workflow().reviewDecisionCommitted(committedEarly);

    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.DECISION_COMMITTED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(committedEarly.receiptId());
    replay(started.workflowId());
  }

  @Test
  void postDeadlineDecisionIsRejectedWithoutConsumingTheSlaRevision() throws Exception {
    Started started = start(Duration.ofSeconds(3), 0);
    environment.sleep(Duration.ofSeconds(4));
    OutcomeReviewDecisionReceipt committedLate = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.REJECT,
        0,
        1,
        started.start().reviewDeadlineAt().plusMillis(1));
    started.workflow().reviewDecisionCommitted(committedLate);

    OutcomeWorkflowDiagnostics rejected = awaitDiagnostics(
        started.workflow(), value -> value.rejectedSignalCount() == 1);
    assertThat(rejected.phase()).isEqualTo("WAITING_SLA_ESCALATION");
    assertThat(rejected.revision()).isZero();
    assertThat(rejected.pendingRevisions()).isEmpty();
    assertThat(rejected.protocolErrorCode())
        .isEqualTo("OUTCOME_DECISION_COMMITTED_AFTER_DEADLINE");

    OutcomeSlaEscalationReceipt sla = started.receipts().sla(
        0, 1, started.start().reviewDeadlineAt());
    started.workflow().slaEscalationCommitted(sla);
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.SLA_ESCALATED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(sla.receiptId());
    replay(started.workflowId());
  }

  @Test
  void sameBoundaryFactsUseJavaRevisionOrderEvenWhenSignalsArriveOutOfOrder() throws Exception {
    Started started = start(Duration.ofSeconds(3), 0);
    OutcomeReviewDecisionReceipt laterRevisionDecision = started.receipts().decision(
        OutcomeWireTypes.ReviewDecision.REJECT,
        1,
        2,
        started.start().reviewDeadlineAt());
    OutcomeSlaEscalationReceipt firstRevisionSla = started.receipts().sla(
        0, 1, started.start().reviewDeadlineAt());
    started.workflow().reviewDecisionCommitted(laterRevisionDecision);
    started.workflow().slaEscalationCommitted(firstRevisionSla);

    environment.sleep(Duration.ofSeconds(4));
    OutcomeProjection result = WorkflowStub.fromTyped(started.workflow())
        .getResult(OutcomeProjection.class);
    assertThat(result.phase()).isEqualTo(OutcomeWireTypes.ProjectionPhase.SLA_ESCALATED);
    assertThat(result.terminalReviewReceiptRef()).isEqualTo(firstRevisionSla.receiptId());
    replay(started.workflowId());
  }

  private Started start(Duration reviewWindow, int operationCount) {
    Instant now = Instant.ofEpochMilli(environment.currentTimeMillis());
    OutcomeWorkflowStart start = OutcomeReceiptTestFactory.start(now, reviewWindow, operationCount);
    OutcomeReceiptTestFactory receipts = new OutcomeReceiptTestFactory(start);
    OutcomeRoomWorkflow workflow = client.newWorkflowStub(
        OutcomeRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(start.workflowId())
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    awaitDiagnostics(workflow, value -> value.phase().equals("WAITING_REVIEW"));
    return new Started(workflow, start.workflowId(), start, receipts);
  }

  static OutcomeWorkflowDiagnostics awaitDiagnostics(
      OutcomeRoomWorkflow workflow, Predicate<OutcomeWorkflowDiagnostics> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    OutcomeWorkflowDiagnostics last = null;
    while (System.nanoTime() < deadline) {
      last = workflow.diagnostics();
      if (last != null && predicate.test(last)) {
        return last;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    throw new AssertionError("Outcome state did not reach the expected condition: " + last);
  }

  private void replay(String workflowId) throws Exception {
    WorkflowExecutionHistory history = client.fetchHistory(workflowId);
    WorkflowReplayer.replayWorkflowExecution(history, OutcomeRoomWorkflowImpl.class);
  }

  record Started(
      OutcomeRoomWorkflow workflow,
      String workflowId,
      OutcomeWorkflowStart start,
      OutcomeReceiptTestFactory receipts) {}
}
