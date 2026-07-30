package com.example.dispute.workflow.room.evidence;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSnapshot;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceTimerPlan;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTerminalActivities;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.ProvisioningCommitment;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowReplayTest {

  private static final String CASE_ID = "CASE_P5_SYNTHETIC_REPLAY";
  private static final long EPOCH = 8;
  private static final String INITIATOR = "PARTICIPANT_P5_REPLAY_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P5_REPLAY_RESPONDENT";
  private static final long INFRASTRUCTURE_TIMEOUT_SECONDS = 30;
  private static final WorkerOptions IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS =
      WorkerOptions.newBuilder().setStickyQueueScheduleToStartTimeout(Duration.ZERO).build();

  @Test
  void warningDuplicateAndCompletionHistoryReplaysDeterministically() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-completion";
        String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":completion";
        RegisteredWorkflow registered = register(environment, taskQueue, workflowId);
        EvidenceRoomWorkflow workflow = registered.workflow();
        targetWorkerFactory = registered.targetWorkerFactory();
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        WorkflowClient.start(workflow::run, start(openedAt, Duration.ofHours(2)));

        EvidenceRoomSignal initiator = signal(INITIATOR, "COMPLETE_REPLAY_INITIATOR", 1);
        awaitTimerCount(
            environment.getWorkflowClient(), workflowId, EVENT_TYPE_TIMER_STARTED, 1);
        shutdownAndAwait(targetWorkerFactory);
        targetWorkerFactory = null;
        workflow.partyCompleted(initiator);
        workflow.partyCompleted(initiator);
        workflow.partyCompleted(signal(RESPONDENT, "COMPLETE_REPLAY_RESPONDENT", 2));
        forceTimeSkippingAcrossPendingWorkflowTask(
            environment, Duration.ofMinutes(90).plusSeconds(1));
        awaitTimerCount(environment.getWorkflowClient(), workflowId, EVENT_TYPE_TIMER_FIRED, 1);
        assertSignalsPrecedeLastTimerFired(environment.getWorkflowClient(), workflowId);
        targetWorkerFactory =
            startWorkerFactory(
                environment.getWorkflowClient(), taskQueue, EvidenceRoomWorkflowImpl.class);

        EvidenceRoomSnapshot result =
            WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
        assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(result.warningSent()).isFalse();
        assertThat(result.duplicateSignalCount()).isEqualTo(1);
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  @Test
  void deadlineExpiryHistoryReplaysDeterministically() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-expiry";
        String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":expiry";
        RegisteredWorkflow registered = register(environment, taskQueue, workflowId);
        EvidenceRoomWorkflow workflow = registered.workflow();
        targetWorkerFactory = registered.targetWorkerFactory();
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        WorkflowClient.start(workflow::run, start(openedAt, Duration.ofHours(2)));
        workflow.partyCompleted(signal(INITIATOR, "COMPLETE_REPLAY_EXPIRY", 3));

        EvidenceRoomSnapshot result =
            WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
        assertThat(result.terminalReason()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.orderedOperationKeys())
            .endsWith(EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  @Test
  void completionSignalsBeforeDeadlineBeatCoalescedDeadlineTimerAndReplay() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-deadline-race";
        String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":deadline-race";
        RegisteredWorkflow registered = register(environment, taskQueue, workflowId);
        EvidenceRoomWorkflow workflow = registered.workflow();
        targetWorkerFactory = registered.targetWorkerFactory();
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        WorkflowClient.start(workflow::run, start(openedAt, Duration.ofHours(2)));
        environment.sleep(Duration.ofMinutes(90).plusSeconds(1));
        assertThat(workflow.state().warningSent()).isTrue();
        awaitTimerCount(
            environment.getWorkflowClient(), workflowId, EVENT_TYPE_TIMER_STARTED, 2);

        shutdownAndAwait(targetWorkerFactory);
        targetWorkerFactory = null;
        workflow.partyCompleted(signal(INITIATOR, "COMPLETE_REPLAY_DEADLINE_I", 4));
        workflow.partyCompleted(signal(RESPONDENT, "COMPLETE_REPLAY_DEADLINE_R", 5));
        forceTimeSkippingAcrossPendingWorkflowTask(
            environment, Duration.ofMinutes(30).plusSeconds(1));
        awaitTimerCount(environment.getWorkflowClient(), workflowId, EVENT_TYPE_TIMER_FIRED, 2);
        assertSignalsPrecedeLastTimerFired(environment.getWorkflowClient(), workflowId);
        targetWorkerFactory =
            startWorkerFactory(
                environment.getWorkflowClient(), taskQueue, EvidenceRoomWorkflowImpl.class);

        EvidenceRoomSnapshot result =
            WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
        assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(result.warningSent()).isTrue();
        assertThat(result.deadlineExpired()).isFalse();
        assertThat(result.orderedOperationKeys())
            .doesNotContain(EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  @Test
  void explicitTargetLaneWithLocalBuildFinalizesSignalsParentAndReplaysExactlyOnce()
      throws Exception {
    WorkflowExecutionHistory history;
    AtomicInteger terminalCalls = new AtomicInteger();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-target-terminal";
        String workflowId =
            CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.EVIDENCE, EPOCH);
        Worker keepAliveWorker =
            environment.newWorker(
                taskQueue + "-time-skipping-keepalive",
                IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
        keepAliveWorker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
        Worker controlWorker =
            environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
        controlWorker.registerActivitiesImplementations(
            (TargetEvidenceTerminalActivities)
                request -> {
                  if (!request.start().targetE2eCandidate()) {
                    throw new IllegalStateException("terminal Activity received a non-target start");
                  }
                  if (!workflowId.equals(request.workflowId())
                      || request.workflowRunId() == null
                      || request.workflowRunId().isBlank()) {
                    throw new IllegalStateException(
                        "terminal Activity did not freeze the exact room Workflow execution");
                  }
                  terminalCalls.incrementAndGet();
                  return new TargetEvidenceTerminalActivities.TerminalResult(
                      new TargetRoomProgressReceipt(
                          RoomType.EVIDENCE,
                          request.start().roomEpoch(),
                          request.start().fencingToken(),
                          Math.incrementExact(request.expectedProcessRevision()),
                          Math.incrementExact(request.expectedRoomRevision()),
                          "target-evidence-terminal-receipt",
                          "d".repeat(64)));
                });
        environment.start();
        targetWorkerFactory =
            startWorkerFactory(
                environment.getWorkflowClient(),
                taskQueue,
                EvidenceRoomWorkflowImpl.class,
                RecordingCaseProcessWorkflow.class);

        CaseProcessWorkflow parent =
            environment
                .getWorkflowClient()
                .newWorkflowStub(
                    CaseProcessWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(
                            CaseProcessWorkflowProtocol.caseWorkflowId(
                                "TENANT_P5_SYNTHETIC_REPLAY", CASE_ID))
                        .setTaskQueue(taskQueue)
                        .build());
        WorkflowStub.fromTyped(parent).start((Object) null);
        EvidenceRoomWorkflow workflow =
            environment
                .getWorkflowClient()
                .newWorkflowStub(
                    EvidenceRoomWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(taskQueue)
                        .build());
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        EvidenceRoomStart targetStart = targetStart(openedAt, Duration.ofHours(2));
        WorkflowClient.start(workflow::run, targetStart);
        workflow.partyCompleted(signal(INITIATOR, "TARGET_TERMINAL_I", 6));
        workflow.partyCompleted(signal(RESPONDENT, "TARGET_TERMINAL_R", 7));

        EvidenceRoomSnapshot result =
            WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
        WorkflowStub.fromTyped(parent).getResult(Void.class);
        assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(result.processRevision()).isEqualTo(5);
        assertThat(result.roomRevision()).isEqualTo(6);
        assertThat(terminalCalls).hasValue(1);
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
    assertThat(terminalCalls).hasValue(1);
  }

  @Test
  void legacyTargetPrefixedHistoryStillFinalizesSignalsParentAndReplaysExactlyOnce()
      throws Exception {
    WorkflowExecutionHistory history;
    AtomicInteger terminalCalls = new AtomicInteger();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-legacy-target-terminal";
        String workflowId =
            CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.EVIDENCE, EPOCH);
        Worker controlWorker =
            environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
        controlWorker.registerActivitiesImplementations(
            (TargetEvidenceTerminalActivities)
                request -> {
                  if (request.carriesWorkflowIdentity()
                      || !request.start().legacyTargetBuildMarker()) {
                    throw new IllegalStateException(
                        "legacy terminal history changed its Activity payload");
                  }
                  terminalCalls.incrementAndGet();
                  return new TargetEvidenceTerminalActivities.TerminalResult(
                      new TargetRoomProgressReceipt(
                          RoomType.EVIDENCE,
                          request.start().roomEpoch(),
                          request.start().fencingToken(),
                          Math.incrementExact(request.expectedProcessRevision()),
                          Math.incrementExact(request.expectedRoomRevision()),
                          "legacy-target-evidence-terminal-receipt",
                          "e".repeat(64)));
                });
        environment.start();
        targetWorkerFactory =
            startWorkerFactory(
                environment.getWorkflowClient(),
                taskQueue,
                LegacyTargetPrefixEvidenceWorkflow.class,
                RecordingCaseProcessWorkflow.class);

        CaseProcessWorkflow parent =
            environment
                .getWorkflowClient()
                .newWorkflowStub(
                    CaseProcessWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(
                            CaseProcessWorkflowProtocol.caseWorkflowId(
                                "TENANT_P5_SYNTHETIC_REPLAY", CASE_ID))
                        .setTaskQueue(taskQueue)
                        .build());
        WorkflowStub.fromTyped(parent).start((Object) null);
        WorkflowStub workflow =
            environment
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    "EvidenceRoomWorkflow",
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(taskQueue)
                        .build());
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        workflow.start(legacyTargetV1StartPayload(openedAt, Duration.ofHours(2)));
        workflow.signal(
            "evidencePartyCompleted", signal(INITIATOR, "LEGACY_TARGET_I", 6));
        workflow.signal(
            "evidencePartyCompleted", signal(RESPONDENT, "LEGACY_TARGET_R", 7));

        EvidenceRoomSnapshot result = workflow.getResult(EvidenceRoomSnapshot.class);
        WorkflowStub.fromTyped(parent).getResult(Void.class);
        assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(result.processRevision()).isEqualTo(5);
        assertThat(result.roomRevision()).isEqualTo(6);
        assertThat(terminalCalls).hasValue(1);
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
    assertThat(terminalCalls).hasValue(1);
  }

  @Test
  void missingExecutionLaneV1InputHistoryStaysLegacyAndReplays() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory targetWorkerFactory = null;
      try {
        String taskQueue = "phase5-evidence-replay-v1-missing-lane";
        String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":v1-missing-lane";
        RegisteredWorkflow registered = register(environment, taskQueue, workflowId);
        targetWorkerFactory = registered.targetWorkerFactory();
        WorkflowStub workflow =
            environment
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    "EvidenceRoomWorkflow",
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(taskQueue)
                        .build());
        Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
        workflow.start(legacyV1StartPayload(openedAt, Duration.ofHours(2)));
        workflow.signal("evidencePartyCompleted", signal(INITIATOR, "LEGACY_V1_I", 8));
        workflow.signal("evidencePartyCompleted", signal(RESPONDENT, "LEGACY_V1_R", 9));

        EvidenceRoomSnapshot result = workflow.getResult(EvidenceRoomSnapshot.class);
        assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        history = environment.getWorkflowClient().fetchHistory(workflowId);
      } finally {
        if (targetWorkerFactory != null) {
          shutdownAndAwait(targetWorkerFactory);
        }
      }
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  private static RegisteredWorkflow register(
      TestWorkflowEnvironment environment, String taskQueue, String workflowId) {
    Worker keepAliveWorker =
        environment.newWorker(
            taskQueue + "-time-skipping-keepalive", IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    keepAliveWorker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    environment.start();
    WorkerFactory targetWorkerFactory =
        startWorkerFactory(
            environment.getWorkflowClient(), taskQueue, EvidenceRoomWorkflowImpl.class);
    EvidenceRoomWorkflow workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                EvidenceRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue)
                    .build());
    return new RegisteredWorkflow(workflow, targetWorkerFactory);
  }

  private static WorkerFactory startWorkerFactory(
      WorkflowClient client, String taskQueue, Class<?>... workflowImplementations) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue, IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    worker.registerWorkflowImplementationTypes(workflowImplementations);
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

  private static EvidenceRoomStart start(Instant openedAt, Duration window) {
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        "TENANT_P5_SYNTHETIC_REPLAY",
        CASE_ID,
        "ROOM_P5_EVIDENCE_REPLAY",
        EPOCH,
        17,
        INITIATOR,
        RESPONDENT,
        openedAt,
        openedAt.plus(window),
        1,
        2,
        3,
        "evidence-workflow.synthetic.v1");
  }

  private static EvidenceRoomStart targetStart(Instant openedAt, Duration window) {
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        "TENANT_P5_SYNTHETIC_REPLAY",
        CASE_ID,
        "ROOM_P5_EVIDENCE_REPLAY",
        EPOCH,
        17,
        INITIATOR,
        RESPONDENT,
        openedAt,
        openedAt.plus(window),
        1,
        2,
        3,
        "local-d96956b7-control",
        ExecutionLane.TARGET_E2E_CANDIDATE);
  }

  private static Map<String, Object> legacyTargetV1StartPayload(
      Instant openedAt, Duration window) {
    Map<String, Object> payload =
        new java.util.LinkedHashMap<>(legacyV1StartPayload(openedAt, window));
    payload.put("workflowBuildId", "target-e2e-control.v0");
    return Map.copyOf(payload);
  }

  private static Map<String, Object> legacyV1StartPayload(
      Instant openedAt, Duration window) {
    return Map.ofEntries(
        Map.entry("schemaVersion", "evidence-room-start.v1"),
        Map.entry("tenantSurrogate", "TENANT_P5_SYNTHETIC_REPLAY"),
        Map.entry("caseId", CASE_ID),
        Map.entry("roomId", "ROOM_P5_EVIDENCE_REPLAY"),
        Map.entry("roomEpoch", EPOCH),
        Map.entry("fencingToken", 17),
        Map.entry("initiatorParticipantId", INITIATOR),
        Map.entry("respondentParticipantId", RESPONDENT),
        Map.entry("openedAt", openedAt),
        Map.entry("originalDeadlineAt", openedAt.plus(window)),
        Map.entry("deadlineRevision", 1),
        Map.entry("initialProcessRevision", 2),
        Map.entry("initialRoomRevision", 3),
        Map.entry("workflowBuildId", "evidence-workflow.synthetic.v1"));
  }

  private static EvidenceRoomSignal signal(
      String participantId, String completionRequestId, int digit) {
    return new EvidenceRoomSignal(
        "evidence-room-party-completion.v2",
        participantId,
        completionRequestId,
        EvidenceOperationKeys.partyComplete(
            CASE_ID, EPOCH, participantId, completionRequestId),
        Integer.toString(digit).repeat(64),
        Instant.now());
  }

  private static void awaitTimerCount(
      WorkflowClient client,
      String workflowId,
      io.temporal.api.enums.v1.EventType eventType,
      long expectedCount) {
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

  private static void assertSignalsPrecedeLastTimerFired(
      WorkflowClient client, String workflowId) {
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

  private static void forceTimeSkippingAcrossPendingWorkflowTask(
      TestWorkflowEnvironment environment, Duration duration) {
    var testService =
        TestServiceGrpc.newBlockingStub(environment.getWorkflowService().getRawChannel());
    testService
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .unlockTimeSkipping(UnlockTimeSkippingRequest.getDefaultInstance());
    try {
      try {
        testService
            .withDeadlineAfter(10, TimeUnit.SECONDS)
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
            "timed out advancing Temporal test time\n" + environment.getDiagnostics(), exception);
      }
    } finally {
      testService
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .lockTimeSkipping(LockTimeSkippingRequest.getDefaultInstance());
    }
  }

  private record RegisteredWorkflow(
      EvidenceRoomWorkflow workflow, WorkerFactory targetWorkerFactory) {}

  /** Exact old target-prefix terminal path, intentionally without the new lane Version marker. */
  public static final class LegacyTargetPrefixEvidenceWorkflow
      implements EvidenceRoomWorkflow {

    private final TargetEvidenceTerminalActivities terminalActivities =
        Workflow.newActivityStub(
            TargetEvidenceTerminalActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                .build());
    private final ArrayDeque<EvidenceRoomSignal> inbox = new ArrayDeque<>();
    private final List<String> operationKeys = new ArrayList<>();

    private EvidenceRoomStart start;
    private EvidenceRoomSignal initiator;
    private EvidenceRoomSignal respondent;
    private EvidenceTimerPlan timerPlan;
    private CancellationScope timerScope;
    private Promise<Void> timer;
    private long processRevision;
    private long roomRevision;

    @Override
    public EvidenceRoomSnapshot run(EvidenceRoomStart start) {
      this.start = start;
      this.timerPlan = EvidenceTimerPlan.from(start);
      this.processRevision = start.initialProcessRevision();
      this.roomRevision = start.initialRoomRevision();
      Workflow.getVersion(
          "evidence-history-ordered-timer-arbitration", Workflow.DEFAULT_VERSION, 2);
      scheduleWarningTimer();
      while (initiator == null || respondent == null) {
        Workflow.await(() -> !inbox.isEmpty());
        while (!inbox.isEmpty()) {
          accept(inbox.removeFirst());
        }
      }

      TargetRoomProgressReceipt progress =
          terminalActivities
              .finalizeTerminal(
                  new TargetEvidenceTerminalActivities.TerminalRequest(
                      start,
                      processRevision,
                      roomRevision,
                      initiator.completionRequestId(),
                      respondent.completionRequestId()))
              .progressReceipt();
      processRevision = progress.processRevision();
      roomRevision = progress.roomRevision();
      CaseProcessWorkflow parent =
          Workflow.newExternalWorkflowStub(
              CaseProcessWorkflow.class,
              CaseProcessWorkflowProtocol.caseWorkflowId(
                  start.tenantSurrogate(), start.caseId()));
      parent.targetRoomProgressed(progress);
      if (timer != null && !timer.isCompleted()) {
        timerScope.cancel();
      }
      Workflow.await(Workflow::isEveryHandlerFinished);
      return snapshot();
    }

    @Override
    public void partyCompleted(EvidenceRoomSignal signal) {
      inbox.addLast(signal);
    }

    @Override
    public void agentRunFinalized(TargetRoomAgentRunFinalizationReceipt receipt) {}

    @Override
    public EvidenceRoomSnapshot state() {
      return snapshot();
    }

    private void accept(EvidenceRoomSignal signal) {
      if (start.initiatorParticipantId().equals(signal.participantId())) {
        initiator = signal;
      } else if (start.respondentParticipantId().equals(signal.participantId())) {
        respondent = signal;
      } else {
        throw new IllegalArgumentException("legacy fixture participant mismatch");
      }
      operationKeys.add(signal.operationKey());
      processRevision = Math.incrementExact(processRevision);
      roomRevision = Math.incrementExact(roomRevision);
    }

    private void scheduleWarningTimer() {
      long delayMillis =
          Math.max(0, timerPlan.warningAt().toEpochMilli() - Workflow.currentTimeMillis());
      timerScope =
          Workflow.newCancellationScope(
              () -> {
                timer = Workflow.newTimer(Duration.ofMillis(delayMillis));
                Async.procedure(
                    () -> {
                      try {
                        timer.get();
                      } catch (CanceledFailure ignored) {
                        // The real old implementation cancels this timer on early completion.
                      }
                    });
              });
      timerScope.run();
    }

    private EvidenceRoomSnapshot snapshot() {
      return new EvidenceRoomSnapshot(
          "evidence-room-snapshot.v1",
          start.tenantSurrogate(),
          start.caseId(),
          start.roomId(),
          start.roomEpoch(),
          start.fencingToken(),
          com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomPhase.COMPLETED,
          "BOTH_PARTIES_COMPLETED",
          start.openedAt(),
          start.originalDeadlineAt(),
          start.deadlineRevision(),
          timerPlan.warningAt(),
          false,
          null,
          false,
          true,
          true,
          initiator.completionRequestId(),
          respondent.completionRequestId(),
          operationKeys,
          null,
          processRevision,
          roomRevision,
          0,
          0,
          null,
          List.of());
    }
  }

  public static final class RecordingCaseProcessWorkflow implements CaseProcessWorkflow {

    private TargetRoomProgressReceipt progress;

    @Override
    public void run(CaseProcessCarryState carryState) {
      Workflow.await(() -> progress != null);
    }

    @Override
    public void acceptCommand(CaseCommandRef command) {}

    @Override
    public void validateAcceptCommand(CaseCommandRef command) {}

    @Override
    public ProvisionRoomEpochReceipt provisionRoomEpoch(ProvisionRoomEpoch request) {
      return null;
    }

    @Override
    public void validateProvisionRoomEpoch(ProvisionRoomEpoch request) {}

    @Override
    public void domainEventCommitted(CaseDomainEventRef event) {}

    @Override
    public void targetRoomProgressed(TargetRoomProgressReceipt receipt) {
      if (progress != null) {
        throw new IllegalStateException("target Evidence progress was signaled more than once");
      }
      progress = receipt;
    }

    @Override
    public void retrySequenceGap() {}

    @Override
    public void requestContinueAsNew() {}

    @Override
    public CaseProcessSnapshot state() {
      return null;
    }

    @Override
    public ProvisionRoomEpochReceipt provisioningReceipt() {
      return null;
    }

    @Override
    public ProvisioningCommitment provisioningCommitment() {
      return null;
    }
  }
}
