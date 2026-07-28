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
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.grpc.StatusRuntimeException;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
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
import java.util.concurrent.TimeUnit;
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
  private static final Duration TIMER_BOUNDARY_GUARD = Duration.ofSeconds(5);
  private static final Duration TIMER_BOUNDARY_SETTLE = Duration.ofSeconds(1);
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
  void completedTargetAgentRunReceiptIsLifecycleOnlyAndExactlyIdempotent() {
    StartedWorkflow started = start("agent-run-receipt", Duration.ofHours(2));
    TargetRoomAgentRunFinalizationReceipt receipt =
        agentRunReceipt(started.start(), "CMD_EVIDENCE_AGENT", "a");

    started.workflow().agentRunFinalized(receipt);
    started.workflow().agentRunFinalized(receipt);

    EvidenceRoomSnapshot observed =
        awaitState(
            started.workflow(), state -> state.agentRunFinalizationReceipts().size() == 1);
    assertThat(observed.agentRunFinalizationReceipts()).containsExactly(receipt);
    assertThat(observed.duplicateSignalCount()).isEqualTo(1);
    assertThat(observed.orderedOperationKeys()).isEmpty();
    assertThat(observed.processRevision()).isEqualTo(started.start().initialProcessRevision() + 1);
    assertThat(observed.roomRevision()).isEqualTo(started.start().initialRoomRevision() + 1);

    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_AGENT_RECEIPT_I", 1));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_AGENT_RECEIPT_R", 2));
    assertThat(result(started.workflow()).terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
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
    EvidenceTimerPlan timerPlan = EvidenceTimerPlan.from(started.start());
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 1);
    advanceTimeTo(timerPlan.warningAt().minus(TIMER_BOUNDARY_GUARD));
    stopWorkflowProcessing();
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_WARNING_I", 1));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_WARNING_R", 2));
    advanceTimeTo(timerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
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
    EvidenceTimerPlan timerPlan = EvidenceTimerPlan.from(started.start());
    advanceTimeTo(timerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
    assertThat(started.workflow().state().warningSent()).isTrue();
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 2);
    advanceTimeTo(timerPlan.deadlineAt().minus(TIMER_BOUNDARY_GUARD));
    stopWorkflowProcessing();
    started.workflow().partyCompleted(signal(INITIATOR, "COMPLETE_BATCHED_DEADLINE_I", 3));
    started.workflow().partyCompleted(signal(RESPONDENT, "COMPLETE_BATCHED_DEADLINE_R", 4));
    advanceTimeTo(timerPlan.deadlineAt().plus(TIMER_BOUNDARY_SETTLE));
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
    EvidenceTimerPlan timerPlan = EvidenceTimerPlan.from(started.start());
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 1);
    stopWorkflowProcessing();
    advanceTimeTo(timerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 1);
    started
        .workflow()
        .partyCompleted(
            signal(
                INITIATOR,
                "COMPLETE_AFTER_WARNING_I",
                5,
                timerPlan.warningAt().plusMillis(1)));
    started
        .workflow()
        .partyCompleted(
            signal(
                RESPONDENT,
                "COMPLETE_AFTER_WARNING_R",
                6,
                timerPlan.warningAt().plusMillis(1)));
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
  void completionAcceptedAfterDeadlineCannotBypassTheWarningToDeadlineTransition() {
    String suffix = "same-task-warning-late-completion";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    EvidenceTimerPlan timerPlan = EvidenceTimerPlan.from(started.start());
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 1);
    stopWorkflowProcessing();
    advanceTimeTo(timerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 1);
    Instant acceptedAfterDeadline = timerPlan.deadlineAt().plusMillis(1);
    started
        .workflow()
        .partyCompleted(
            signal(INITIATOR, "COMPLETE_AFTER_WINDOW_I", 7, acceptedAfterDeadline));
    started
        .workflow()
        .partyCompleted(
            signal(RESPONDENT, "COMPLETE_AFTER_WINDOW_R", 8, acceptedAfterDeadline));
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
  void deadlineRecordedBeforeCompletionSignalsWinsWhenHistoryIsDeliveredInOneWorkflowTask() {
    String suffix = "same-task-deadline-first";
    String workflowId = workflowId(suffix);
    StartedWorkflow started = start(suffix, Duration.ofHours(2));
    EvidenceTimerPlan timerPlan = EvidenceTimerPlan.from(started.start());
    advanceTimeTo(timerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
    assertThat(started.workflow().state().warningSent()).isTrue();
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_STARTED, 2);
    stopWorkflowProcessing();
    advanceTimeTo(timerPlan.deadlineAt().plus(TIMER_BOUNDARY_SETTLE));
    awaitTimerCount(workflowId, EVENT_TYPE_TIMER_FIRED, 2);
    started
        .workflow()
        .partyCompleted(
            signal(
                INITIATOR,
                "COMPLETE_AFTER_DEADLINE_I",
                7,
                timerPlan.deadlineAt().plusMillis(1)));
    started
        .workflow()
        .partyCompleted(
            signal(
                RESPONDENT,
                "COMPLETE_AFTER_DEADLINE_R",
                8,
                timerPlan.deadlineAt().plusMillis(1)));
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
        EvidenceRoomStart legacyStart = startAt(openedAt, openedAt.plus(Duration.ofHours(2)));
        EvidenceTimerPlan legacyTimerPlan = EvidenceTimerPlan.from(legacyStart);
        WorkflowClient.start(legacyWorkflow::run, legacyStart);
        awaitTimerCount(legacyClient, workflowId, EVENT_TYPE_TIMER_STARTED, 1);
        advanceTimeTo(
            legacyEnvironment,
            legacyTimerPlan.warningAt().minus(TIMER_BOUNDARY_GUARD));

        shutdownAndAwait(legacyTargetWorkerFactory);
        legacyTargetWorkerFactory = null;
        legacyWorkflow.partyCompleted(signal(INITIATOR, "LEGACY_COMPLETE_I", 9));
        legacyWorkflow.partyCompleted(signal(RESPONDENT, "LEGACY_COMPLETE_R", 0));
        advanceTimeTo(
            legacyEnvironment,
            legacyTimerPlan.warningAt().plus(TIMER_BOUNDARY_SETTLE));
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
  void completionSignalV2RequiresAcceptedAtWhileV1RemainsReplayDecodable() {
    String operationKey =
        EvidenceOperationKeys.partyComplete(CASE_ID, EPOCH, INITIATOR, "COMPLETE_SCHEMA");
    EvidenceRoomSignal legacy =
        new EvidenceRoomSignal(
            "evidence-room-party-completion.v1",
            INITIATOR,
            "COMPLETE_SCHEMA",
            operationKey,
            hash(7),
            null);

    assertThat(legacy.acceptedAt()).isNull();
    assertThatThrownBy(
            () ->
                new EvidenceRoomSignal(
                    "evidence-room-party-completion.v2",
                    INITIATOR,
                    "COMPLETE_SCHEMA",
                    operationKey,
                    hash(7),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("acceptedAt must not be null for v2");
  }

  @Test
  void wrongSemanticOperationKeyIsRejectedWithoutAdvancingPartyState() {
    StartedWorkflow started = start("reject", Duration.ofHours(2));
    EvidenceRoomSignal malformed =
        new EvidenceRoomSignal(
            "evidence-room-party-completion.v2",
            INITIATOR,
            "COMPLETE_WRONG_KEY",
            EvidenceOperationKeys.partyComplete(
                CASE_ID, EPOCH, RESPONDENT, "COMPLETE_WRONG_KEY"),
            hash(8),
            Instant.now());
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
    return signal(participantId, completionRequestId, hashDigit, Instant.now());
  }

  private static TargetRoomAgentRunFinalizationReceipt agentRunReceipt(
      EvidenceRoomStart start, String commandId, String resultHashDigit) {
    return new TargetRoomAgentRunFinalizationReceipt(
        TargetRoomAgentRunFinalizationReceipt.SCHEMA_VERSION,
        start.tenantSurrogate(),
        start.caseId(),
        RoomType.EVIDENCE,
        start.roomEpoch(),
        start.fencingToken(),
        start.initialProcessRevision(),
        start.initialRoomRevision(),
        0,
        commandId,
        "RUN_EVIDENCE_AGENT",
        "ATTEMPT_EVIDENCE_AGENT",
        1,
        resultHashDigit.repeat(64));
  }

  private static EvidenceRoomSignal signal(
      String participantId, String completionRequestId, int hashDigit, Instant acceptedAt) {
    return new EvidenceRoomSignal(
        "evidence-room-party-completion.v2",
        participantId,
        completionRequestId,
        EvidenceOperationKeys.partyComplete(
            CASE_ID, EPOCH, participantId, completionRequestId),
        hash(hashDigit),
        acceptedAt);
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

  private void advanceTimeTo(Instant target) {
    advanceTimeTo(environment, target);
  }

  private static void advanceTimeTo(TestWorkflowEnvironment testEnvironment, Instant target) {
    Instant current = Instant.ofEpochMilli(testEnvironment.currentTimeMillis());
    Duration delay = Duration.between(current, target);
    assertThat(delay).as("Temporal test-time delay to %s", target).isPositive();
    advanceTestTime(testEnvironment, delay);
  }

  private static void advanceTestTime(
      TestWorkflowEnvironment testEnvironment, Duration duration) {
    var testService =
        TestServiceGrpc.newBlockingStub(testEnvironment.getWorkflowService().getRawChannel());
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
          "failed advancing Temporal test time\n" + testEnvironment.getDiagnostics(), exception);
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
    public void agentRunFinalized(TargetRoomAgentRunFinalizationReceipt receipt) {
      // The legacy replay fixture intentionally has no target AgentRun lifecycle state.
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
          protocolErrorCode,
          List.of());
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
