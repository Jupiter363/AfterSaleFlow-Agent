package com.example.dispute.workflow.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomSnapshot;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HearingRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase6-hearing-room-workflow-test";
  private static final String CASE_ID = "CASE_P6_SYNTHETIC_HEARING";
  private static final long EPOCH = 6;
  private static final String INITIATOR = "PARTICIPANT_P6_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P6_RESPONDENT";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private AtomicLong revision;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(HearingRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
    revision = new AtomicLong();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void stageContractContainsExactlyFifteenStagesAndFourteenAdjacentEdges() {
    assertThat(HearingWorkflowStage.values())
        .extracting(Enum::name)
        .containsExactly(
            "COURT_PREPARING",
            "CASE_INTRODUCTION",
            "EVIDENCE_INTRODUCTION",
            "INTAKE_QUESTIONS_GENERATING",
            "PARTY_ANSWERS_OPEN",
            "INTAKE_SYNTHESIZING",
            "EVIDENCE_REQUESTS_GENERATING",
            "PARTY_EVIDENCE_OPEN",
            "EVIDENCE_SYNTHESIZING",
            "DOSSIER_FREEZING",
            "JUDGE_V1_GENERATING",
            "JURY_REVIEWING",
            "JUDGE_V2_GENERATING",
            "HUMAN_REVIEW_OPEN",
            "CLOSED");
    assertThat(
            Arrays.stream(HearingWorkflowStage.values())
                .filter(HearingWorkflowStage::isPartyWait)
                .toList())
        .containsExactly(
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    for (int sequence = 1; sequence < 15; sequence++) {
      assertThat(HearingWorkflowStage.atSequence(sequence).next())
          .isEqualTo(HearingWorkflowStage.atSequence(sequence + 1));
    }
    assertThat(HearingWorkflowStage.CLOSED.next()).isNull();
    assertThatThrownBy(() -> HearingWorkflowStage.atSequence(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HearingWorkflowStage.atSequence(16))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fullReceiptDrivenFlowPreservesDeadlineAndWaitsForJavaTimeoutReceipt()
      throws Exception {
    Started started = start("full", Duration.ofSeconds(3));
    advanceSystemStages(started.workflow(), HearingWorkflowStage.PARTY_ANSWERS_OPEN);

    HearingRoomSnapshot answerWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    Instant answerDeadline = answerWait.stageDeadlineAt();
    started.workflow().partyTerminal(partyReceipt(answerWait, INITIATOR, "ANSWER_I", false));
    HearingRoomSnapshot afterFirstAnswer =
        awaitState(started.workflow(), state -> state.partyTerminals().size() == 1);
    assertThat(afterFirstAnswer.stageDeadlineAt()).isEqualTo(answerDeadline);
    started.workflow().partyTerminal(partyReceipt(afterFirstAnswer, RESPONDENT, "ANSWER_R", false));
    awaitStage(started.workflow(), HearingWorkflowStage.INTAKE_SYNTHESIZING);

    advanceSystemStages(started.workflow(), HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    HearingRoomSnapshot evidenceWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    started.workflow().partyTerminal(
        partyReceipt(evidenceWait, INITIATOR, "EVIDENCE_I", false));
    environment.sleep(Duration.ofSeconds(4));
    HearingRoomSnapshot expired =
        awaitState(started.workflow(), HearingRoomSnapshot::deadlineReached);
    assertThat(expired.stage()).isEqualTo(HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    assertThat(expired.timeoutRequiredParticipantIds()).containsExactly(RESPONDENT);
    assertThat(expired.orderedOperationKeys())
        .contains(
            HearingOperationKeys.partyDeadline(
                CASE_ID,
                EPOCH,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN.sequence()));
    started.workflow().partyTerminal(
        partyReceipt(expired, RESPONDENT, "EVIDENCE_R_TIMEOUT", true));
    awaitStage(started.workflow(), HearingWorkflowStage.EVIDENCE_SYNTHESIZING);

    advanceSystemStages(started.workflow(), HearingWorkflowStage.CLOSED);
    HearingRoomSnapshot result =
        WorkflowStub.fromTyped(started.workflow()).getResult(HearingRoomSnapshot.class);
    assertThat(result.status()).isEqualTo("CLOSED");
    assertThat(result.stage()).isEqualTo(HearingWorkflowStage.CLOSED);
    assertThat(result.stageSequence()).isEqualTo(15);
    assertThat(result.rejectedSignalCount()).isZero();
    assertThat(result.processRevision()).isEqualTo(revision.get());
    assertThat(result.roomRevision()).isEqualTo(revision.get());

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, HearingRoomWorkflowImpl.class);
  }

  @Test
  void duplicateReceiptIsIdempotentAndConflictingOperationDoesNotAdvance() {
    Started started = start("dedupe", Duration.ofMinutes(20));
    HearingRoomSnapshot initial = awaitStage(started.workflow(), HearingWorkflowStage.COURT_PREPARING);
    HearingStageReceipt first = stageReceipt(initial);
    started.workflow().stageCompleted(first);
    started.workflow().stageCompleted(first);
    HearingRoomSnapshot introduction =
        awaitStage(started.workflow(), HearingWorkflowStage.CASE_INTRODUCTION);
    assertThat(awaitState(started.workflow(), state -> state.duplicateSignalCount() == 1)
            .duplicateSignalCount())
        .isEqualTo(1);

    long next = revision.incrementAndGet();
    HearingStageReceipt wrong =
        new HearingStageReceipt(
            "hearing-stage-receipt.v1",
            "RECEIPT_WRONG_KEY",
            introduction.stage(),
            introduction.stageSequence(),
            HearingOperationKeys.stageCompletion(
                CASE_ID,
                EPOCH,
                HearingWorkflowStage.EVIDENCE_INTRODUCTION,
                HearingWorkflowStage.EVIDENCE_INTRODUCTION.sequence()),
            hash('a'),
            hash('b'),
            next,
            next,
            next);
    started.workflow().stageCompleted(wrong);
    HearingRoomSnapshot rejected =
        awaitState(started.workflow(), state -> state.rejectedSignalCount() == 1);
    assertThat(rejected.stage()).isEqualTo(HearingWorkflowStage.CASE_INTRODUCTION);
    assertThat(rejected.protocolErrorCode())
        .isEqualTo("HEARING_STAGE_RECEIPT_OPERATION_KEY_MISMATCH");

    completeRemaining(started.workflow());
    assertThat(WorkflowStub.fromTyped(started.workflow()).getResult(HearingRoomSnapshot.class).status())
        .isEqualTo("CLOSED");
  }

  private Started start(String suffix, Duration partyWindow) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    HearingRoomStart start =
        new HearingRoomStart(
            "hearing-room-start.v1",
            "TENANT_P6_SYNTHETIC_HEARING",
            CASE_ID,
            "ROOM_P6_HEARING",
            EPOCH,
            19,
            INITIATOR,
            RESPONDENT,
            openedAt,
            openedAt.plus(Duration.ofHours(3)),
            partyWindow.toSeconds(),
            0,
            0,
            "hearing-workflow.synthetic.v1");
    String workflowId = "hearing-room:" + CASE_ID + ":" + EPOCH + ":" + suffix;
    HearingRoomWorkflow workflow =
        client.newWorkflowStub(
            HearingRoomWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build());
    WorkflowClient.start(workflow::run, start);
    return new Started(workflow, workflowId);
  }

  private void advanceSystemStages(
      HearingRoomWorkflow workflow, HearingWorkflowStage target) {
    while (true) {
      HearingRoomSnapshot current = workflow.state();
      if (current.stage() == target) {
        return;
      }
      assertThat(current.stage()).isNotNull();
      assertThat(current.stage().isPartyWait()).isFalse();
      workflow.stageCompleted(stageReceipt(current));
      awaitState(workflow, state -> state.stageSequence() > current.stageSequence());
    }
  }

  private void completeRemaining(HearingRoomWorkflow workflow) {
    while (workflow.state().stage() != HearingWorkflowStage.CLOSED) {
      HearingRoomSnapshot state = workflow.state();
      if (state.stage().isPartyWait()) {
        workflow.partyTerminal(partyReceipt(state, INITIATOR, "AUTO_I_" + state.stageSequence(), false));
        HearingRoomSnapshot one = awaitState(workflow, item -> item.partyTerminals().size() == 1);
        workflow.partyTerminal(partyReceipt(one, RESPONDENT, "AUTO_R_" + state.stageSequence(), false));
        awaitState(workflow, item -> item.stageSequence() > state.stageSequence());
      } else {
        workflow.stageCompleted(stageReceipt(state));
        awaitState(workflow, item -> item.stageSequence() > state.stageSequence());
      }
    }
  }

  private HearingStageReceipt stageReceipt(HearingRoomSnapshot state) {
    long next = revision.incrementAndGet();
    return new HearingStageReceipt(
        "hearing-stage-receipt.v1",
        "RECEIPT_" + state.stage().name() + "_" + next,
        state.stage(),
        state.stageSequence(),
        HearingOperationKeys.stageCompletion(
            CASE_ID, EPOCH, state.stage(), state.stageSequence()),
        hash('c'),
        hash('d'),
        next,
        next,
        next);
  }

  private HearingPartyTerminalReceipt partyReceipt(
      HearingRoomSnapshot state, String participantId, String requestId, boolean timeout) {
    long next = revision.incrementAndGet();
    return new HearingPartyTerminalReceipt(
        "hearing-party-terminal-receipt.v1",
        requestId,
        participantId,
        state.stage(),
        state.stageSequence(),
        timeout
            ? HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT
            : HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
        HearingOperationKeys.partyTerminal(
            CASE_ID,
            EPOCH,
            state.stage(),
            state.stageSequence(),
            participantId,
            requestId),
        hash('e'),
        next,
        next,
        next);
  }

  private static HearingRoomSnapshot awaitStage(
      HearingRoomWorkflow workflow, HearingWorkflowStage expected) {
    return awaitState(workflow, state -> state.stage() == expected);
  }

  private static HearingRoomSnapshot awaitState(
      HearingRoomWorkflow workflow, Predicate<HearingRoomSnapshot> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    HearingRoomSnapshot last = null;
    while (System.nanoTime() < deadline) {
      last = workflow.state();
      if (predicate.test(last)) {
        return last;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    throw new AssertionError("workflow state did not reach expected condition: " + last);
  }

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }

  private record Started(HearingRoomWorkflow workflow, String workflowId) {}
}
