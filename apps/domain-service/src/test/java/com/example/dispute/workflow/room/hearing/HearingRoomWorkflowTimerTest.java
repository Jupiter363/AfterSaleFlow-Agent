package com.example.dispute.workflow.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomSnapshot;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HearingRoomWorkflowTimerTest {

  private static final String TASK_QUEUE = "phase6-hearing-room-timer-test";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(HearingRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void earlyAutoTimeoutWaitsForOriginalWorkflowTimer() {
    Started started = startAtAnswerWait("early-timeout", Duration.ofSeconds(3));
    HearingRoomSnapshot waiting = started.workflow().state();
    Instant originalDeadline = waiting.stageDeadlineAt();

    HearingPartyTerminalReceipt timeout = started.receipts().party(
        waiting,
        HearingReceiptTestFactory.INITIATOR,
        "EARLY_TIMEOUT_I",
        HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT,
        false);
    started.workflow().partyTerminal(timeout);

    HearingRoomSnapshot pending = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> !state.pendingReceiptRevisions().isEmpty());
    assertThat(pending.processRevision()).isEqualTo(waiting.processRevision());
    assertThat(pending.partyTerminals()).isEmpty();
    assertThat(pending.stageDeadlineAt()).isEqualTo(originalDeadline);
    environment.sleep(Duration.ofSeconds(2));
    assertThat(started.workflow().state().partyTerminals()).isEmpty();

    environment.sleep(Duration.ofSeconds(2));
    HearingRoomSnapshot applied = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 1);
    assertThat(applied.deadlineReached()).isTrue();
    assertThat(applied.stageDeadlineAt()).isEqualTo(originalDeadline);
    assertThat(applied.partyTerminals())
        .containsEntry(
            HearingReceiptTestFactory.INITIATOR,
            HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT);
    assertThat(applied.timeoutRequiredParticipantIds())
        .containsExactly(HearingReceiptTestFactory.RESPONDENT);
  }

  @Test
  void leavingPartyWaitCancelsItsOriginalTimer() {
    Started started = startAtAnswerWait("cancel-timer", Duration.ofSeconds(3));
    HearingRoomSnapshot waiting = started.workflow().state();
    String deadlineKey = HearingOperationKeys.partyDeadline(
        HearingReceiptTestFactory.TENANT,
        HearingReceiptTestFactory.CASE_ID,
        HearingReceiptTestFactory.ROOM_EPOCH,
        waiting.stage(),
        waiting.stageSequence());

    started.workflow().partyTerminal(
        started.receipts().party(
            waiting,
            HearingReceiptTestFactory.INITIATOR,
            "ANSWER_I",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot one = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 1);
    started.workflow().partyTerminal(
        started.receipts().party(
            one,
            HearingReceiptTestFactory.RESPONDENT,
            "ANSWER_R",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot both = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 2);
    started.workflow().stageCompleted(
        started.receipts().stageCompletion(
            both, HearingWorkflowStage.INTAKE_SYNTHESIZING, null));
    HearingRoomSnapshot advanced = HearingRoomWorkflowTest.awaitStage(
        started.workflow(), HearingWorkflowStage.INTAKE_SYNTHESIZING);

    environment.sleep(Duration.ofSeconds(5));
    HearingRoomSnapshot afterOldDeadline = started.workflow().state();
    assertThat(afterOldDeadline.stage()).isEqualTo(HearingWorkflowStage.INTAKE_SYNTHESIZING);
    assertThat(afterOldDeadline.deadlineReached()).isFalse();
    assertThat(afterOldDeadline.orderedOperationKeys()).doesNotContain(deadlineKey);
    assertThat(afterOldDeadline.processRevision()).isEqualTo(advanced.processRevision());
  }

  @Test
  void sameTimestampSignalAndTimeoutHaveOneReplayableHistoryOrder() throws Exception {
    Started started = startAtAnswerWait("same-tick", Duration.ofSeconds(3));
    HearingRoomSnapshot waiting = started.workflow().state();
    HearingPartyTerminalReceipt submitted = started.receipts().party(
        waiting,
        HearingReceiptTestFactory.INITIATOR,
        "SAME_TICK_I",
        HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
        false);
    long delayMillis = waiting.stageDeadlineAt().toEpochMilli() - environment.currentTimeMillis();
    environment.registerDelayedCallback(
        Duration.ofMillis(delayMillis),
        () -> started.workflow().partyTerminal(submitted));

    environment.sleep(Duration.ofMillis(delayMillis + 1));
    HearingRoomSnapshot resolved = HearingRoomWorkflowTest.awaitState(
        started.workflow(),
        state -> state.deadlineReached() && state.partyTerminals().size() == 1);
    assertThat(resolved.partyTerminals())
        .containsOnlyKeys(HearingReceiptTestFactory.INITIATOR);
    assertThat(resolved.timeoutRequiredParticipantIds())
        .containsExactly(HearingReceiptTestFactory.RESPONDENT);
    assertThat(resolved.orderedOperationKeys())
        .contains(
            submitted.committed().operationKey(),
            HearingOperationKeys.partyDeadline(
                HearingReceiptTestFactory.TENANT,
                HearingReceiptTestFactory.CASE_ID,
                HearingReceiptTestFactory.ROOM_EPOCH,
                waiting.stage(),
                waiting.stageSequence()));

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, HearingRoomWorkflowImpl.class);
  }

  private Started startAtAnswerWait(String suffix, Duration partyWindow) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    HearingRoomStart start = HearingReceiptTestFactory.start(openedAt, partyWindow);
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String workflowId = "hearing-room:" + HearingReceiptTestFactory.CASE_ID + ':'
        + HearingReceiptTestFactory.ROOM_EPOCH + ":timer:" + suffix;
    HearingRoomWorkflow workflow = client.newWorkflowStub(
        HearingRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    HearingRoomSnapshot state = HearingRoomWorkflowTest.awaitStage(
        workflow, HearingWorkflowStage.COURT_PREPARING);
    for (int sequence = 1; sequence <= 3; sequence++) {
      workflow.stageCompleted(receipts.stageCompletion(state, state.stage().next(), null));
      int expectedSequence = sequence + 1;
      state = HearingRoomWorkflowTest.awaitState(
          workflow, current -> current.stageSequence() == expectedSequence);
    }
    Instant deadline = Instant.ofEpochMilli(environment.currentTimeMillis())
        .plusSeconds(partyWindow.toSeconds());
    workflow.stageCompleted(
        receipts.finalizer(
            state,
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            deadline,
            "hearing_question_set.v1"));
    HearingRoomWorkflowTest.awaitState(
        workflow,
        current -> current.stage() == HearingWorkflowStage.PARTY_ANSWERS_OPEN
            && current.stageDeadlineAt() != null);
    return new Started(workflow, workflowId, receipts);
  }

  private record Started(
      HearingRoomWorkflow workflow,
      String workflowId,
      HearingReceiptTestFactory receipts) {}
}
