package com.example.dispute.workflow.room.evidence;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSnapshot;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowReplayTest {

  private static final String CASE_ID = "CASE_P5_SYNTHETIC_REPLAY";
  private static final long EPOCH = 8;
  private static final String INITIATOR = "PARTICIPANT_P5_REPLAY_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P5_REPLAY_RESPONDENT";
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
        targetWorkerFactory = startWorkerFactory(environment.getWorkflowClient(), taskQueue);

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
        targetWorkerFactory = startWorkerFactory(environment.getWorkflowClient(), taskQueue);

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

  private static RegisteredWorkflow register(
      TestWorkflowEnvironment environment, String taskQueue, String workflowId) {
    Worker keepAliveWorker =
        environment.newWorker(
            taskQueue + "-time-skipping-keepalive", IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    keepAliveWorker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    environment.start();
    WorkerFactory targetWorkerFactory =
        startWorkerFactory(environment.getWorkflowClient(), taskQueue);
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

  private static WorkerFactory startWorkerFactory(WorkflowClient client, String taskQueue) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue, IMMEDIATE_STICKY_FALLBACK_WORKER_OPTIONS);
    worker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    factory.start();
    return factory;
  }

  private static void shutdownAndAwait(WorkerFactory factory) {
    factory.shutdown();
    factory.awaitTermination(5, TimeUnit.SECONDS);
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

  private static EvidenceRoomSignal signal(
      String participantId, String completionRequestId, int digit) {
    return new EvidenceRoomSignal(
        "evidence-room-party-completion.v1",
        participantId,
        completionRequestId,
        EvidenceOperationKeys.partyComplete(
            CASE_ID, EPOCH, participantId, completionRequestId),
        Integer.toString(digit).repeat(64));
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
}
