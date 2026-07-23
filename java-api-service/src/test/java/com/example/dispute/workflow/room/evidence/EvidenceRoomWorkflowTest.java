package com.example.dispute.workflow.room.evidence;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomPhase;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSnapshot;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceTimerPlan;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase5-evidence-room-workflow-test";
  private static final String CASE_ID = "CASE_P5_SYNTHETIC_TIMER";
  private static final long EPOCH = 5;
  private static final String INITIATOR = "PARTICIPANT_P5_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P5_RESPONDENT";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private Worker worker;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void duplicatePartyCompletionIsIdempotentAndEarlyCompletionSkipsTimers() {
    StartedWorkflow started = start("early", Duration.ofHours(2));
    EvidenceRoomSignal initiator = signal(INITIATOR, "COMPLETE_INITIATOR", 1);

    started.workflow().partyCompleted(initiator);
    started.workflow().partyCompleted(initiator);
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_RESPONDENT", 2));

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.roomPhase()).isEqualTo(EvidenceRoomPhase.COMPLETED);
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.warningSent()).isFalse();
    assertThat(result.deadlineExpired()).isFalse();
    assertThat(result.duplicateSignalCount()).isEqualTo(1);
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            initiator.operationKey(),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_RESPONDENT"));
    assertThat(result.originalDeadlineAt()).isEqualTo(started.start().originalDeadlineAt());
  }

  @Test
  void warningFiresExactlyThirtyMinutesBeforeTheImmutableDeadline() {
    StartedWorkflow started = start("warning", Duration.ofHours(2));
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_WARN_INITIATOR", 3));

    environment.sleep(Duration.ofMinutes(90));
    EvidenceRoomSnapshot warned = started.workflow().state();
    assertThat(warned.warningSent()).isTrue();
    assertThat(warned.warningAt())
        .isEqualTo(started.start().originalDeadlineAt().minus(Duration.ofMinutes(30)));
    assertThat(warned.warningSentAt()).isEqualTo(warned.warningAt());
    assertThat(warned.originalDeadlineAt()).isEqualTo(started.start().originalDeadlineAt());

    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_WARN_RESPONDENT", 4));
    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, INITIATOR, "COMPLETE_WARN_INITIATOR"),
            EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_WARN_RESPONDENT"));
  }

  @Test
  void firstPartyCompletionDoesNotResetWarningOrExpiry() {
    StartedWorkflow started = start("expiry", Duration.ofHours(2));
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_EXPIRY_INITIATOR", 5));

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("DEADLINE_EXPIRED");
    assertThat(result.initiatorCompleted()).isTrue();
    assertThat(result.respondentCompleted()).isFalse();
    assertThat(result.warningSent()).isTrue();
    assertThat(result.deadlineExpired()).isTrue();
    assertThat(result.originalDeadlineAt()).isEqualTo(started.start().originalDeadlineAt());
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, INITIATOR, "COMPLETE_EXPIRY_INITIATOR"),
            EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1),
            EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
  }

  @Test
  void completionAcceptedBeforeDeadlineWinsWithoutAnExpiryCommand() {
    StartedWorkflow started = start("race-completion", Duration.ofHours(2));
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_RACE_INITIATOR", 6));
    environment.sleep(Duration.ofMinutes(119));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_RACE_RESPONDENT", 7));

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.deadlineExpired()).isFalse();
    assertThat(result.orderedOperationKeys())
        .doesNotContain(EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
  }

  @Test
  void completionSignalsRecordedBeforeWarningWinWhenHistoryIsDeliveredInOneWorkflowTask() {
    String suffix = "same-task-warning";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 1);
    worker.suspendPolling();
    try {
      started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_WARNING_I", 1));
      started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_WARNING_R", 2));
      forceTimeSkippingAcrossPendingWorkflowTask(Duration.ofMinutes(90).plusSeconds(1));
      awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 1);
      assertSignalsPrecedeLastTimerFired(workflowId);
    } finally {
      worker.resumePolling();
    }

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.warningSent()).isFalse();
    assertThat(result.deadlineExpired()).isFalse();
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, INITIATOR, "COMPLETE_BATCHED_WARNING_I"),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_BATCHED_WARNING_R"));
  }

  @Test
  void completionSignalsRecordedBeforeDeadlineWinWhenHistoryIsDeliveredInOneWorkflowTask() {
    String suffix = "same-task-deadline";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    environment.sleep(Duration.ofMinutes(90).plusSeconds(1));
    assertThat(started.workflow().state().warningSent()).isTrue();
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 2);
    worker.suspendPolling();
    try {
      started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_DEADLINE_I", 3));
      started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_DEADLINE_R", 4));
      forceTimeSkippingAcrossPendingWorkflowTask(Duration.ofMinutes(30).plusSeconds(1));
      awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 2);
      assertSignalsPrecedeLastTimerFired(workflowId);
    } finally {
      worker.resumePolling();
    }

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.warningSent()).isTrue();
    assertThat(result.deadlineExpired()).isFalse();
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, INITIATOR, "COMPLETE_BATCHED_DEADLINE_I"),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_BATCHED_DEADLINE_R"));
  }

  @Test
  void wrongSemanticOperationKeyIsRejectedWithoutAdvancingPartyState() {
    StartedWorkflow started = start("reject", Duration.ofHours(2));
    EvidenceRoomSignal malformed =
        new EvidenceRoomSignal(
            "evidence-room-party-completion.v1",
            INITIATOR,
            "COMPLETE_WRONG_KEY",
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_WRONG_KEY"),
            hash(8));
    started.workflow().partyCompleted(malformed);
    environment.sleep(Duration.ofSeconds(1));

    EvidenceRoomSnapshot state = started.workflow().state();
    assertThat(state.initiatorCompleted()).isFalse();
    assertThat(state.respondentCompleted()).isFalse();
    assertThat(state.rejectedSignalCount()).isEqualTo(1);
    assertThat(state.protocolErrorCode())
        .isEqualTo("EVIDENCE_COMPLETION_OPERATION_KEY_MISMATCH");
    assertThat(state.orderedOperationKeys()).isEmpty();

    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_REJECT_INITIATOR", 9));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_REJECT_RESPONDENT", 0));
    assertThat(result(started.workflow()).terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
  }

  @Test
  void timerPlanAndAllFrozenOperationKeysUseContractOrder() {
    Instant openedAt = Instant.parse("2026-07-23T10:00:00Z");
    EvidenceRoomStart start = startAt(openedAt, openedAt.plus(Duration.ofHours(2)));
    EvidenceTimerPlan plan = EvidenceTimerPlan.from(start);

    assertThat(plan.warningAt()).isEqualTo(Instant.parse("2026-07-23T11:30:00Z"));
    assertThat(plan.warningOperationKey())
        .isEqualTo("evidence.deadline.warn:" + CASE_ID + ":" + EPOCH + ":1");
    assertThat(plan.expiryOperationKey())
        .isEqualTo("evidence.deadline.expire:" + CASE_ID + ":" + EPOCH + ":1");
    assertThat(
            EvidenceOperationKeys.manifestIssue(CASE_ID, EPOCH, "SUBMISSION_P5", 2))
        .isEqualTo(
            "evidence.manifest.issue:" + CASE_ID + ":" + EPOCH + ":SUBMISSION_P5:2");
    assertThat(EvidenceOperationKeys.graphRequest(CASE_ID, EPOCH, "a".repeat(64), "RUN_P5"))
        .isEqualTo(
            "evidence.graph.request:"
                + CASE_ID
                + ":"
                + EPOCH
                + ":"
                + "a".repeat(64)
                + ":RUN_P5");
    assertThat(EvidenceOperationKeys.batchMerge(CASE_ID, EPOCH, "b".repeat(64), 3))
        .isEqualTo(
            "evidence.batch.merge:"
                + CASE_ID
                + ":"
                + EPOCH
                + ":"
                + "b".repeat(64)
                + ":3");
    assertThat(EvidenceOperationKeys.dossierFreeze(CASE_ID, EPOCH, 3))
        .isEqualTo("evidence.dossier.freeze:" + CASE_ID + ":" + EPOCH + ":3");
    assertThat(EvidenceOperationKeys.hearingOpen(CASE_ID, EPOCH, "c".repeat(64)))
        .isEqualTo(
            "evidence.hearing.open:"
                + CASE_ID
                + ":"
                + EPOCH
                + ":"
                + "c".repeat(64));
    assertThatThrownBy(() -> EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private StartedWorkflow start(String suffix, Duration window) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    EvidenceRoomStart start = startAt(openedAt, openedAt.plus(window));
    EvidenceRoomWorkflow workflow =
        client.newWorkflowStub(
            EvidenceRoomWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId(suffix))
                .setTaskQueue(TASK_QUEUE)
                .build());
    WorkflowClient.start(workflow::run, start);
    return new StartedWorkflow(workflow, start);
  }

  private static EvidenceRoomStart startAt(Instant openedAt, Instant deadlineAt) {
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        "TENANT_P5_SYNTHETIC_TIMER",
        CASE_ID,
        "ROOM_P5_EVIDENCE_TIMER",
        EPOCH,
        11,
        INITIATOR,
        RESPONDENT,
        openedAt,
        deadlineAt,
        1,
        4,
        6,
        "evidence-workflow.synthetic.v1");
  }

  private static EvidenceRoomSignal signal(
      String participantId, String completionRequestId, int hashDigit) {
    return new EvidenceRoomSignal(
        "evidence-room-party-completion.v1",
        participantId,
        completionRequestId,
        EvidenceOperationKeys.partyComplete(
            CASE_ID, EPOCH, participantId, completionRequestId),
        hash(hashDigit));
  }

  private static String hash(int digit) {
    return Integer.toString(Math.abs(digit) % 10).repeat(64);
  }

  private static EvidenceRoomSnapshot result(EvidenceRoomWorkflow workflow) {
    return WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
  }

  private static String workflowId(String suffix) {
    return "evidence-room:" + CASE_ID + ":" + EPOCH + ":" + suffix;
  }

  private void awaitTimerCount(
      String workflowId, io.temporal.api.enums.v1.EventType eventType, long expectedCount) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      long count =
          client.fetchHistory(workflowId).getEvents().stream()
              .filter(event -> event.getEventType() == eventType)
              .count();
      if (count >= expectedCount) {
        return;
      }
      sleepBriefly();
    }
    throw new AssertionError(
        "expected " + expectedCount + " history events of type " + eventType);
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(10);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test interrupted", exception);
    }
  }

  private void assertSignalsPrecedeLastTimerFired(String workflowId) {
    var events = client.fetchHistory(workflowId).getEvents();
    long lastSignalEventId =
        events.stream()
            .filter(event -> event.getEventType() == EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
            .mapToLong(event -> event.getEventId())
            .max()
            .orElseThrow();
    long lastTimerFiredEventId =
        events.stream()
            .filter(event -> event.getEventType() == EVENT_TYPE_TIMER_FIRED)
            .mapToLong(event -> event.getEventId())
            .max()
            .orElseThrow();
    assertThat(lastSignalEventId).isLessThan(lastTimerFiredEventId);
  }

  private void forceTimeSkippingAcrossPendingWorkflowTask(Duration duration) {
    var testService =
        TestServiceGrpc.newBlockingStub(environment.getWorkflowService().getRawChannel());
    testService.unlockTimeSkipping(UnlockTimeSkippingRequest.getDefaultInstance());
    try {
      environment.sleep(duration);
    } finally {
      testService.lockTimeSkipping(LockTimeSkippingRequest.getDefaultInstance());
    }
  }

  private record StartedWorkflow(EvidenceRoomWorkflow workflow, EvidenceRoomStart start) {}
}
