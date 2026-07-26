package com.example.dispute.workflow.room.evidence;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED;
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
import io.grpc.StatusRuntimeException;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase5-evidence-room-workflow-test";
  private static final String CASE_ID = "CASE_P5_SYNTHETIC_TIMER";
  private static final long EPOCH = 5;
  private static final String INITIATOR = "PARTICIPANT_P5_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P5_RESPONDENT";
  private static final String KEEP_ALIVE_TASK_QUEUE = TASK_QUEUE + "-time-skipping-keepalive";
  private static final long INFRASTRUCTURE_TIMEOUT_SECONDS = 30;
  private static final WorkerOptions IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS =
      WorkerOptions.newBuilder().setStickyQueueScheduleToStartTimeout(Duration.ZERO).build();

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private WorkerFactory targetWorkerFactory;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker keepAliveWorker =
        environment.newWorker(KEEP_ALIVE_TASK_QUEUE, IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    keepAliveWorker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
    targetWorkerFactory =
        startWorkerFactory(client, TASK_QUEUE, EvidenceRoomWorkflowImpl.class);
  }

  @AfterEach
  void tearDown() {
    try {
      if (targetWorkerFactory != null) {
        shutdownAndAwait(targetWorkerFactory);
      }
    } finally {
      environment.close();
    }
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
    EvidenceRoomSnapshot warned = awaitState(started.workflow(), EvidenceRoomSnapshot::warningSent);
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
    stopWorkflowProcessing();
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_WARNING_I", 1));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_WARNING_R", 2));
    forceTimeSkippingAcrossPendingWorkflowTask(Duration.ofMinutes(90).plusSeconds(1));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 1);
    assertSignalsPrecedeLastTimerFired(workflowId);
    restartWorkflowProcessing();

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
    stopWorkflowProcessing();
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_DEADLINE_I", 3));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_DEADLINE_R", 4));
    forceTimeSkippingAcrossPendingWorkflowTask(Duration.ofMinutes(30).plusSeconds(1));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 2);
    assertSignalsPrecedeLastTimerFired(workflowId);
    restartWorkflowProcessing();

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
  void warningRecordedBeforeCompletionSignalsWinsWhenHistoryIsDeliveredInOneWorkflowTask() {
    String suffix = "same-task-warning-first";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 1);
    stopWorkflowProcessing();
    forceTimeSkippingToTimerBoundary(workflowId, 1, Duration.ofMinutes(90));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 1);
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_AFTER_WARNING_I", 5));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_AFTER_WARNING_R", 6));
    awaitTimerCount(workflowId, EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, 2);
    assertLastTimerFiredPrecedesSignals(workflowId);
    restartWorkflowProcessing();

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
    assertThat(result.warningSent()).isTrue();
    assertThat(result.deadlineExpired()).isFalse();
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, INITIATOR, "COMPLETE_AFTER_WARNING_I"),
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_AFTER_WARNING_R"));
  }

  @Test
  void deadlineRecordedBeforeCompletionSignalsWinsWhenHistoryIsDeliveredInOneWorkflowTask() {
    String suffix = "same-task-deadline-first";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    environment.sleep(Duration.ofMinutes(90).plusSeconds(1));
    assertThat(started.workflow().state().warningSent()).isTrue();
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 2);
    stopWorkflowProcessing();
    forceTimeSkippingToTimerBoundary(workflowId, 2, Duration.ofMinutes(30));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 2);
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_AFTER_DEADLINE_I", 7));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_AFTER_DEADLINE_R", 8));
    awaitTimerCount(workflowId, EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, 2);
    assertLastTimerFiredPrecedesSignals(workflowId);
    restartWorkflowProcessing();

    EvidenceRoomSnapshot result = result(started.workflow());
    assertThat(result.terminalReason()).isEqualTo("DEADLINE_EXPIRED");
    assertThat(result.warningSent()).isTrue();
    assertThat(result.deadlineExpired()).isTrue();
    assertThat(result.initiatorCompleted()).isFalse();
    assertThat(result.respondentCompleted()).isFalse();
    assertThat(result.orderedOperationKeys())
        .containsExactly(
            EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1),
            EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
  }

  @Test
  void unversionedLegacyCoalescedHistoryReplaysThroughDefaultVersion() throws Exception {
    String taskQueue = "phase5-evidence-room-legacy-replay";
    String workflowId = workflowId("legacy-unversioned-race");
    WorkflowExecutionHistory history;

    try (TestWorkflowEnvironment legacyEnvironment = TestWorkflowEnvironment.newInstance()) {
      Worker legacyKeepAliveWorker =
          legacyEnvironment.newWorker(
              taskQueue + "-time-skipping-keepalive", IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
      legacyKeepAliveWorker.registerWorkflowImplementationTypes(
          LegacyEvidenceRoomWorkflowImpl.class);
      legacyEnvironment.start();
      WorkflowClient legacyClient = legacyEnvironment.getWorkflowClient();
      WorkerFactory legacyTargetWorkerFactory = null;
      try {
        legacyTargetWorkerFactory =
            startWorkerFactory(legacyClient, taskQueue, LegacyEvidenceRoomWorkflowImpl.class);
        EvidenceRoomWorkflow legacyWorkflow =
            legacyClient.newWorkflowStub(
                EvidenceRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue)
                    .build());
        Instant openedAt = Instant.ofEpochMilli(legacyEnvironment.currentTimeMillis());
        WorkflowClient.start(
            legacyWorkflow::run, startAt(openedAt, openedAt.plus(Duration.ofHours(2))));
        awaitTimerCount(legacyClient, workflowId, EVENT_TYPE_TIMER_STARTED, 1);

        shutdownAndAwait(legacyTargetWorkerFactory);
        legacyTargetWorkerFactory = null;
        legacyWorkflow.partyCompleted(signal(INITIATOR, "LEGACY_COMPLETE_I", 9));
        legacyWorkflow.partyCompleted(signal(RESPONDENT, "LEGACY_COMPLETE_R", 0));
        forceTimeSkippingAcrossPendingWorkflowTask(
            legacyEnvironment, Duration.ofMinutes(90).plusSeconds(1));
        awaitTimerCount(legacyClient, workflowId, EVENT_TYPE_TIMER_FIRED, 1);
        assertSignalsPrecedeLastTimerFired(legacyClient, workflowId);
        legacyTargetWorkerFactory =
            startWorkerFactory(legacyClient, taskQueue, LegacyEvidenceRoomWorkflowImpl.class);

        EvidenceRoomSnapshot legacyResult = result(legacyWorkflow);
        assertThat(legacyResult.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(legacyResult.orderedOperationKeys())
            .contains(
                EvidenceOperationKeys.partyComplete(
                    CASE_ID, EPOCH, INITIATOR, "LEGACY_COMPLETE_I"),
                EvidenceOperationKeys.partyComplete(
                    CASE_ID, EPOCH, RESPONDENT, "LEGACY_COMPLETE_R"));
        assertThat(
                legacyResult.orderedOperationKeys().stream()
                    .filter(EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, 1)::equals)
                    .count())
            .isEqualTo(legacyResult.warningSent() ? 1 : 0);
        history = legacyClient.fetchHistory(workflowId);
      } finally {
        if (legacyTargetWorkerFactory != null) {
          shutdownAndAwait(legacyTargetWorkerFactory);
        }
      }
    }

    assertThat(history.getEvents())
        .noneMatch(event -> event.getEventType() == EVENT_TYPE_MARKER_RECORDED);
    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
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

  private void stopWorkflowProcessing() {
    shutdownAndAwait(targetWorkerFactory);
    targetWorkerFactory = null;
  }

  private void restartWorkflowProcessing() {
    targetWorkerFactory = startWorkerFactory(client, TASK_QUEUE, EvidenceRoomWorkflowImpl.class);
  }

  private static WorkerFactory startWorkerFactory(
      WorkflowClient workflowClient, String taskQueue, Class<?> workflowImplementation) {
    WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
    Worker replacementWorker =
        factory.newWorker(taskQueue, IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    replacementWorker.registerWorkflowImplementationTypes(workflowImplementation);
    factory.start();
    return factory;
  }

  private static void shutdownAndAwait(WorkerFactory factory) {
    factory.shutdownNow();
    factory.awaitTermination(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long publicationDeadline =
        System.nanoTime()
            + TimeUnit.SECONDS.toNanos(INFRASTRUCTURE_TIMEOUT_SECONDS);
    while (!factory.isTerminated() && System.nanoTime() < publicationDeadline) {
      sleepBriefly();
    }
    assertThat(factory.isTerminated()).as("worker factory terminated").isTrue();
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

  private static EvidenceRoomSnapshot awaitState(
      EvidenceRoomWorkflow workflow, Predicate<EvidenceRoomSnapshot> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    EvidenceRoomSnapshot state = workflow.state();
    while (!predicate.test(state) && System.nanoTime() < deadline) {
      sleepBriefly();
      state = workflow.state();
    }
    assertThat(predicate.test(state)).isTrue();
    return state;
  }

  private static String workflowId(String suffix) {
    return "evidence-room:" + CASE_ID + ":" + EPOCH + ":" + suffix;
  }

  private void awaitTimerCount(
      String workflowId, io.temporal.api.enums.v1.EventType eventType, long expectedCount) {
    awaitTimerCount(client, workflowId, eventType, expectedCount);
  }

  private static void awaitTimerCount(
      WorkflowClient historyClient,
      String workflowId,
      io.temporal.api.enums.v1.EventType eventType,
      long expectedCount) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      long count =
          historyClient.fetchHistory(workflowId).getEvents().stream()
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
    assertSignalsPrecedeLastTimerFired(client, workflowId);
  }

  private static void assertSignalsPrecedeLastTimerFired(
      WorkflowClient historyClient, String workflowId) {
    var events = historyClient.fetchHistory(workflowId).getEvents();
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

  private void assertLastTimerFiredPrecedesSignals(String workflowId) {
    var events = client.fetchHistory(workflowId).getEvents();
    long lastTimerFiredEventId =
        events.stream()
            .filter(event -> event.getEventType() == EVENT_TYPE_TIMER_FIRED)
            .mapToLong(event -> event.getEventId())
            .max()
            .orElseThrow();
    long firstSignalEventId =
        events.stream()
            .filter(event -> event.getEventType() == EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
            .mapToLong(event -> event.getEventId())
            .min()
            .orElseThrow();
    assertThat(lastTimerFiredEventId).isLessThan(firstSignalEventId);
  }

  private void forceTimeSkippingAcrossPendingWorkflowTask(Duration duration) {
    forceTimeSkippingAcrossPendingWorkflowTask(environment, duration);
  }

  private void forceTimeSkippingToTimerBoundary(
      String workflowId, long expectedTimerCount, Duration duration) {
    var testService =
        TestServiceGrpc.newBlockingStub(environment.getWorkflowService().getRawChannel());
    CountDownLatch timeAdvanceCompleted = new CountDownLatch(1);
    CompletableFuture<Void> workflowTaskLockRelease =
        CompletableFuture.runAsync(
            () -> {
              awaitTimerCount(client, workflowId, EVENT_TYPE_TIMER_FIRED, expectedTimerCount);
              testService
                  .withDeadlineAfter(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  .unlockTimeSkipping(UnlockTimeSkippingRequest.getDefaultInstance());
              try {
                if (!timeAdvanceCompleted.await(
                    INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                  throw new AssertionError("Temporal test time advance did not complete");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
              } finally {
                testService
                    .withDeadlineAfter(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .lockTimeSkipping(LockTimeSkippingRequest.getDefaultInstance());
              }
            });

    Throwable advanceFailure = null;
    try {
      forceTimeSkippingAcrossPendingWorkflowTask(environment, duration);
    } catch (RuntimeException | Error exception) {
      advanceFailure = exception;
      throw exception;
    } finally {
      timeAdvanceCompleted.countDown();
      try {
        awaitBackgroundTimeLockRelease(workflowTaskLockRelease);
      } catch (RuntimeException | Error releaseFailure) {
        if (advanceFailure != null) {
          advanceFailure.addSuppressed(releaseFailure);
        } else {
          throw releaseFailure;
        }
      }
    }
  }

  private static void awaitBackgroundTimeLockRelease(CompletableFuture<Void> release) {
    try {
      release.get(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test interrupted", exception);
    } catch (ExecutionException exception) {
      throw new AssertionError("failed to restore Temporal test time lock", exception.getCause());
    } catch (TimeoutException exception) {
      throw new AssertionError("timed out restoring Temporal test time lock", exception);
    }
  }

  private static void forceTimeSkippingAcrossPendingWorkflowTask(
      TestWorkflowEnvironment testEnvironment, Duration duration) {
    var testService =
        TestServiceGrpc.newBlockingStub(testEnvironment.getWorkflowService().getRawChannel());
    testService
        .withDeadlineAfter(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .unlockTimeSkipping(UnlockTimeSkippingRequest.getDefaultInstance());
    try {
      try {
        testService
            .withDeadlineAfter(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .unlockTimeSkippingWithSleep(
                SleepRequest.newBuilder()
                    .setDuration(
                        com.google.protobuf.Duration.newBuilder()
                            .setSeconds(duration.getSeconds())
                            .setNanos(duration.getNano())
                            .build())
                    .build());
      } catch (StatusRuntimeException exception) {
        throw new AssertionError(
            "timed out advancing Temporal test time\n" + testEnvironment.getDiagnostics(),
            exception);
      }
    } finally {
      testService
          .withDeadlineAfter(INFRASTRUCTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .lockTimeSkipping(LockTimeSkippingRequest.getDefaultInstance());
    }
  }

  // Exact pre-version timer kernel used to generate a real history without a Version marker.
  public static final class LegacyEvidenceRoomWorkflowImpl implements EvidenceRoomWorkflow {

    private final ArrayDeque<EvidenceRoomSignal> inbox = new ArrayDeque<>();
    private final Map<String, EvidenceRoomSignal> observedRequests = new LinkedHashMap<>();
    private final List<String> orderedOperationKeys = new ArrayList<>();

    private EvidenceRoomStart start;
    private EvidenceTimerPlan timerPlan;
    private EvidenceRoomPhase roomPhase = EvidenceRoomPhase.OPEN;
    private String terminalReason;
    private boolean warningSent;
    private Instant warningSentAt;
    private boolean deadlineExpired;
    private EvidenceRoomSignal initiatorCompletion;
    private EvidenceRoomSignal respondentCompletion;
    private String pendingOperationKey;
    private long processRevision;
    private long roomRevision;
    private long duplicateSignalCount;
    private long rejectedSignalCount;
    private String protocolErrorCode;

    @Override
    public EvidenceRoomSnapshot run(EvidenceRoomStart start) {
      if (this.start != null) {
        throw new IllegalStateException("Evidence room workflow was initialized more than once");
      }
      this.start = Objects.requireNonNull(start, "start must not be null");
      timerPlan = EvidenceTimerPlan.from(start);
      processRevision = start.initialProcessRevision();
      roomRevision = start.initialRoomRevision();
      roomPhase = EvidenceRoomPhase.WAITING_PARTIES;

      while (roomPhase != EvidenceRoomPhase.COMPLETED) {
        drainInbox();
        if (bothPartiesCompleted()) {
          complete("BOTH_PARTIES_COMPLETED");
          break;
        }

        if (!warningSent) {
          if (awaitInputBefore(timerPlan.warningAt())) {
            continue;
          }
          warningSent = true;
          warningSentAt = timerPlan.warningAt();
          appendOperation(timerPlan.warningOperationKey());
          continue;
        }

        if (awaitInputBefore(timerPlan.deadlineAt())) {
          continue;
        }
        deadlineExpired = true;
        appendOperation(timerPlan.expiryOperationKey());
        complete("DEADLINE_EXPIRED");
      }

      Workflow.await(Workflow::isEveryHandlerFinished);
      return state();
    }

    @Override
    public void partyCompleted(EvidenceRoomSignal signal) {
      inbox.addLast(Objects.requireNonNull(signal, "signal must not be null"));
    }

    @Override
    public EvidenceRoomSnapshot state() {
      return new EvidenceRoomSnapshot(
          "evidence-room-snapshot.v1",
          start == null ? null : start.tenantSurrogate(),
          start == null ? null : start.caseId(),
          start == null ? null : start.roomId(),
          start == null ? 0 : start.roomEpoch(),
          start == null ? 0 : start.fencingToken(),
          roomPhase,
          terminalReason,
          start == null ? null : start.openedAt(),
          start == null ? null : start.originalDeadlineAt(),
          start == null ? 0 : start.deadlineRevision(),
          timerPlan == null ? null : timerPlan.warningAt(),
          warningSent,
          warningSentAt,
          deadlineExpired,
          initiatorCompletion != null,
          respondentCompletion != null,
          initiatorCompletion == null ? null : initiatorCompletion.completionRequestId(),
          respondentCompletion == null ? null : respondentCompletion.completionRequestId(),
          orderedOperationKeys,
          pendingOperationKey,
          processRevision,
          roomRevision,
          duplicateSignalCount,
          rejectedSignalCount,
          protocolErrorCode);
    }

    private void drainInbox() {
      while (!inbox.isEmpty()) {
        processSignal(inbox.removeFirst());
      }
    }

    private void processSignal(EvidenceRoomSignal signal) {
      EvidenceRoomSignal observed = observedRequests.get(signal.completionRequestId());
      if (observed != null) {
        if (observed.equals(signal)) {
          duplicateSignalCount++;
        } else {
          reject("EVIDENCE_COMPLETION_REQUEST_CONFLICT");
        }
        return;
      }
      observedRequests.put(signal.completionRequestId(), signal);

      String expectedKey =
          EvidenceOperationKeys.partyComplete(
              start.caseId(),
              start.roomEpoch(),
              signal.participantId(),
              signal.completionRequestId());
      if (!expectedKey.equals(signal.operationKey())) {
        reject("EVIDENCE_COMPLETION_OPERATION_KEY_MISMATCH");
        return;
      }

      if (start.initiatorParticipantId().equals(signal.participantId())) {
        if (initiatorCompletion != null) {
          duplicateSignalCount++;
          return;
        }
        initiatorCompletion = signal;
      } else if (start.respondentParticipantId().equals(signal.participantId())) {
        if (respondentCompletion != null) {
          duplicateSignalCount++;
          return;
        }
        respondentCompletion = signal;
      } else {
        reject("EVIDENCE_COMPLETION_PARTICIPANT_MISMATCH");
        return;
      }
      appendOperation(signal.operationKey());
    }

    private boolean bothPartiesCompleted() {
      return initiatorCompletion != null && respondentCompletion != null;
    }

    private boolean awaitInputBefore(Instant boundary) {
      long remainingMillis = boundary.toEpochMilli() - Workflow.currentTimeMillis();
      if (remainingMillis <= 0) {
        return false;
      }
      return Workflow.await(Duration.ofMillis(remainingMillis), () -> !inbox.isEmpty());
    }

    private void appendOperation(String operationKey) {
      orderedOperationKeys.add(operationKey);
      pendingOperationKey = operationKey;
    }

    private void reject(String errorCode) {
      rejectedSignalCount++;
      protocolErrorCode = errorCode;
    }

    private void complete(String reason) {
      terminalReason = reason;
      roomPhase = EvidenceRoomPhase.COMPLETED;
      pendingOperationKey = null;
    }
  }

  private record StartedWorkflow(EvidenceRoomWorkflow workflow, EvidenceRoomStart start) {}
}
