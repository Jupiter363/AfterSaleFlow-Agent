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
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HearingRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase6-hearing-room-workflow-test";

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
  void stageContractContainsExactlyFifteenStagesAndSevenAgentOperations() {
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
    assertThat(
            Arrays.stream(HearingWorkflowStage.values())
                .filter(HearingWorkflowStage::requiresAgentRun)
                .map(HearingWorkflowStage::agentOperation)
                .toList())
        .containsExactly(
            "intake_questions",
            "intake_synthesis",
            "evidence_requests",
            "evidence_synthesis",
            "judge_v1",
            "jury_review",
            "judge_v2");
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
    advanceTo(started, HearingWorkflowStage.PARTY_ANSWERS_OPEN);

    HearingRoomSnapshot answerWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    Instant answerDeadline = answerWait.stageDeadlineAt();
    started.workflow().partyTerminal(
        started.receipts().party(
            answerWait,
            HearingReceiptTestFactory.INITIATOR,
            "ANSWER_I",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot afterFirstAnswer = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 1);
    assertThat(afterFirstAnswer.stageDeadlineAt()).isEqualTo(answerDeadline);
    started.workflow().partyTerminal(
        started.receipts().party(
            afterFirstAnswer,
            HearingReceiptTestFactory.RESPONDENT,
            "ANSWER_R",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot bothAnswers = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 2);
    started.workflow().stageCompleted(
        started.receipts().stageCompletion(
            bothAnswers, HearingWorkflowStage.INTAKE_SYNTHESIZING, null));
    awaitStage(started.workflow(), HearingWorkflowStage.INTAKE_SYNTHESIZING);

    advanceTo(started, HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    HearingRoomSnapshot evidenceWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    started.workflow().partyTerminal(
        started.receipts().party(
            evidenceWait,
            HearingReceiptTestFactory.INITIATOR,
            "EVIDENCE_I",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    environment.sleep(Duration.ofSeconds(4));
    HearingRoomSnapshot expired =
        awaitState(started.workflow(), HearingRoomSnapshot::deadlineReached);
    assertThat(expired.stage()).isEqualTo(HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    assertThat(expired.timeoutRequiredParticipantIds())
        .containsExactly(HearingReceiptTestFactory.RESPONDENT);
    assertThat(expired.orderedOperationKeys())
        .contains(
            HearingOperationKeys.partyDeadline(
                HearingReceiptTestFactory.TENANT,
                HearingReceiptTestFactory.CASE_ID,
                HearingReceiptTestFactory.ROOM_EPOCH,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN.sequence()));
    started.workflow().partyTerminal(
        started.receipts().party(
            expired,
            HearingReceiptTestFactory.RESPONDENT,
            "EVIDENCE_R_TIMEOUT",
            HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT,
            false));
    HearingRoomSnapshot bothEvidence = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 2);
    started.workflow().stageCompleted(
        started.receipts().stageCompletion(
            bothEvidence, HearingWorkflowStage.EVIDENCE_SYNTHESIZING, null));
    awaitStage(started.workflow(), HearingWorkflowStage.EVIDENCE_SYNTHESIZING);

    completeRemaining(started);
    HearingRoomSnapshot result =
        WorkflowStub.fromTyped(started.workflow()).getResult(HearingRoomSnapshot.class);
    assertThat(result.status()).isEqualTo("CLOSED");
    assertThat(result.stage()).isEqualTo(HearingWorkflowStage.CLOSED);
    assertThat(result.stageSequence()).isEqualTo(15);
    assertThat(result.rejectedSignalCount()).isZero();
    assertThat(result.processRevision()).isEqualTo(result.acceptedReceiptCount());
    assertThat(result.roomRevision()).isEqualTo(result.acceptedReceiptCount());
    assertThat(result.pendingReceiptRevisions()).isEmpty();
    assertThat(result.lastReceiptId()).isNotBlank();
    assertThat(result.handoffReceiptId()).isNotBlank();

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, HearingRoomWorkflowImpl.class);
  }

  @Test
  void completedTargetAgentRunReceiptIsPersistedOnlyAtItsExactAgentStage() {
    Started started = start("agent-run-receipt", Duration.ofSeconds(3));
    advanceTo(started, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    HearingRoomSnapshot current = started.workflow().state();
    TargetRoomAgentRunFinalizationReceipt receipt =
        new TargetRoomAgentRunFinalizationReceipt(
            TargetRoomAgentRunFinalizationReceipt.SCHEMA_VERSION,
            current.tenantSurrogate(),
            current.caseId(),
            RoomType.HEARING,
            current.roomEpoch(),
            current.fencingToken(),
            current.processRevision(),
            current.roomRevision(),
            current.stageSequence(),
            "CMD_HEARING_AGENT",
            "RUN_HEARING_AGENT",
            "ATTEMPT_HEARING_AGENT",
            1,
            "a".repeat(64));

    started.workflow().agentRunFinalized(receipt);
    started.workflow().agentRunFinalized(receipt);

    HearingRoomSnapshot observed =
        awaitState(
            started.workflow(), state -> state.agentRunFinalizationReceipts().size() == 1);
    assertThat(observed.agentRunFinalizationReceipts()).containsExactly(receipt);
    assertThat(observed.duplicateSignalCount()).isEqualTo(current.duplicateSignalCount() + 1);
    assertThat(observed.processRevision()).isEqualTo(current.processRevision());
    assertThat(observed.roomRevision()).isEqualTo(current.roomRevision());
  }

  @Test
  void agentResultCannotAdvanceWithoutJavaFinalizerReceipt() {
    Started started = start("agent-order", Duration.ofMinutes(20));
    advanceTo(started, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    HearingRoomSnapshot agentStage = started.workflow().state();
    started.workflow().stageCompleted(started.receipts().agentResult(agentStage));
    HearingRoomSnapshot observed = awaitState(
        started.workflow(), state -> state.agentResultReceiptId() != null);
    assertThat(observed.stage()).isEqualTo(HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);

    started.workflow().stageCompleted(
        started.receipts().finalizer(
            observed,
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            nextPartyDeadline(started.start()),
            "hearing_question_set.v1"));
    assertThat(awaitStage(started.workflow(), HearingWorkflowStage.PARTY_ANSWERS_OPEN).status())
        .isEqualTo("RUNNING");
  }

  private Started start(String suffix, Duration partyWindow) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    HearingRoomStart start = HearingReceiptTestFactory.start(openedAt, partyWindow);
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String workflowId = "hearing-room:" + HearingReceiptTestFactory.CASE_ID + ':'
        + HearingReceiptTestFactory.ROOM_EPOCH + ':' + suffix;
    HearingRoomWorkflow workflow = client.newWorkflowStub(
        HearingRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    awaitStage(workflow, HearingWorkflowStage.COURT_PREPARING);
    return new Started(workflow, workflowId, start, receipts);
  }

  private void advanceTo(Started started, HearingWorkflowStage target) {
    while (started.workflow().state().stage() != target) {
      HearingRoomSnapshot current = started.workflow().state();
      if (current.stage().isPartyWait()) {
        started.workflow().partyTerminal(
            started.receipts().party(
                current,
                HearingReceiptTestFactory.INITIATOR,
                "AUTO_I_" + current.stageSequence(),
                HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
                false));
        HearingRoomSnapshot one = awaitState(
            started.workflow(), state -> state.partyTerminals().size() == 1);
        started.workflow().partyTerminal(
            started.receipts().party(
                one,
                HearingReceiptTestFactory.RESPONDENT,
                "AUTO_R_" + current.stageSequence(),
                HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
                false));
        HearingRoomSnapshot both = awaitState(
            started.workflow(), state -> state.partyTerminals().size() == 2);
        started.workflow().stageCompleted(
            started.receipts().stageCompletion(both, current.stage().next(), null));
        awaitState(
            started.workflow(), state -> state.stageSequence() > current.stageSequence());
      } else {
        completeNonWaitStage(started, current);
      }
    }
  }

  private void completeRemaining(Started started) {
    advanceTo(started, HearingWorkflowStage.HUMAN_REVIEW_OPEN);
    HearingRoomSnapshot review = started.workflow().state();
    started.workflow().stageCompleted(
        started.receipts().handoff(
            review,
            "JUDGE_V2_SYNTHETIC",
            HearingReceiptTestFactory.hash("judge-v2")));
    HearingRoomSnapshot handedOff = awaitState(
        started.workflow(), state -> state.handoffReceiptId() != null);
    started.workflow().stageCompleted(started.receipts().close(handedOff));
  }

  private void completeNonWaitStage(Started started, HearingRoomSnapshot current) {
    HearingWorkflowStage next = current.stage().next();
    if (current.stage().requiresAgentRun()) {
      started.workflow().stageCompleted(
          started.receipts().finalizer(
              current,
              next,
              next.isPartyWait() ? nextPartyDeadline(started.start()) : null,
              artifactType(current.stage())));
    } else if (current.stage() == HearingWorkflowStage.DOSSIER_FREEZING) {
      started.workflow().stageCompleted(
          started.receipts().finalizer(
              current, next, null, "trial_dossier.v1"));
    } else {
      started.workflow().stageCompleted(
          started.receipts().stageCompletion(current, next, null));
    }
    awaitState(started.workflow(), state -> state.stageSequence() > current.stageSequence());
  }

  private Instant nextPartyDeadline(HearingRoomStart start) {
    Instant window = Instant.ofEpochMilli(environment.currentTimeMillis())
        .plusSeconds(start.partyStageWindowSeconds());
    return window.isBefore(start.hearingDeadlineAt()) ? window : start.hearingDeadlineAt();
  }

  private static String artifactType(HearingWorkflowStage stage) {
    return switch (stage) {
      case INTAKE_QUESTIONS_GENERATING -> "hearing_question_set.v1";
      case INTAKE_SYNTHESIZING -> "hearing_intake_matrix.v1";
      case EVIDENCE_REQUESTS_GENERATING -> "hearing_evidence_request_set.v1";
      case EVIDENCE_SYNTHESIZING -> "hearing_evidence_matrix.v1";
      case JUDGE_V1_GENERATING -> "hearing_judge_v1.v1";
      case JURY_REVIEWING -> "hearing_jury_review.v1";
      case JUDGE_V2_GENERATING -> "hearing_judge_v2.v1";
      default -> throw new IllegalArgumentException("stage has no Agent finalizer artifact");
    };
  }

  static HearingRoomSnapshot awaitStage(
      HearingRoomWorkflow workflow, HearingWorkflowStage expected) {
    return awaitState(workflow, state -> state.stage() == expected);
  }

  static HearingRoomSnapshot awaitState(
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

  private record Started(
      HearingRoomWorkflow workflow,
      String workflowId,
      HearingRoomStart start,
      HearingReceiptTestFactory receipts) {}
}
