package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class IntakeRoomWorkflowWorkerRecoveryTest {

  private static final String TENANT = "tenant-p4-b2-worker-recovery";
  private static final String CASE_ID = "CASE_P4_B2_WORKER_RECOVERY";
  private static final long EPOCH = 5;
  private static final long FENCE = 23;
  private static final String INITIATOR_SCOPE = "d".repeat(64);
  private static final String RESPONDENT_SCOPE = "e".repeat(64);
  private static final String THREAD_ID = "grt.v1." + "f".repeat(32);
  private static final String AGENT_SESSION = "AGENT_SESSION_P4_B2_RECOVERY";

  @Test
  void replacementWorkerReplaysCommittedStagesWithoutRepeatingTheirActivities() {
    List<WorkerFactory> factories = new ArrayList<>();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      try {
        environment.start();
        IntakeRoomWorkflowActivityTest.FakeActivities activities =
            new IntakeRoomWorkflowActivityTest.FakeActivities();
        String workflowQueue = "phase4-intake-worker-recovery";
        WorkerFactory first = startWorkers(environment, workflowQueue, activities, "first");
        factories.add(first);

        IntakeRoomWorkflow workflow =
            environment
                .getWorkflowClient()
                .newWorkflowStub(
                    IntakeRoomWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId("intake-room:" + CASE_ID + ":" + EPOCH)
                        .setTaskQueue(workflowQueue)
                        .build());
        WorkflowClient.start(workflow::run, start());
        workflow.commandAccepted(
            command(1, "CMD_BEFORE_WORKER_KILL", IntakeCommandType.INTAKE_MESSAGE, null));
        awaitState(workflow, state -> state.nextEventSequence() == 2);

        first.shutdownNow();
        first.awaitTermination(10, TimeUnit.SECONDS);
        WorkerFactory replacement =
            startWorkers(environment, workflowQueue, activities, "replacement");
        factories.add(replacement);
        environment.sleep(Duration.ofMillis(250));

        IntakeRoomSnapshot replayed =
            awaitState(
                workflow,
                state ->
                    state.nextEventSequence() == 2
                        && state.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);
        assertThat(replayed.processedCommandCount()).isEqualTo(1);
        assertThat(activities.snapshotRequests).hasSize(1);
        assertThat(activities.graphRequests).hasSize(1);
        assertThat(activities.finalizationRequests).hasSize(1);

        workflow.commandAccepted(
            command(
                2,
                "CMD_AFTER_WORKER_KILL",
                IntakeCommandType.INTAKE_CONFIRM,
                BranchOperation.INITIATOR_ACCEPT));
        IntakeRoomSnapshot after = awaitState(workflow, state -> state.respondentUnlocked());
        assertThat(after.roomPhase()).isEqualTo(IntakeRoomPhase.WAITING_PARTY);
        assertThat(activities.snapshotRequests).hasSize(1);
        assertThat(activities.graphRequests).hasSize(1);
        assertThat(activities.finalizationRequests).hasSize(1);
        assertThat(activities.acceptRequests).hasSize(1);
      } finally {
        shutdownFactories(factories);
      }
    }
  }

  private static void shutdownFactories(List<WorkerFactory> factories) {
    for (WorkerFactory factory : factories) {
      if (!factory.isShutdown()) {
        factory.shutdownNow();
      }
    }
    for (WorkerFactory factory : factories) {
      factory.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private static WorkerFactory startWorkers(
      TestWorkflowEnvironment environment,
      String workflowQueue,
      IntakeRoomWorkflowActivityTest.FakeActivities activities,
      String identity) {
    WorkflowClient workerClient =
        WorkflowClient.newInstance(
            environment.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder()
                .setNamespace("default")
                .setIdentity("intake-worker-recovery-" + identity)
                .build());
    WorkerFactory factory =
        WorkerFactory.newInstance(
            workerClient,
            WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build());
    Worker workflowWorker = factory.newWorker(workflowQueue);
    workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
    Worker activityWorker = factory.newWorker(AGENT_EXECUTION);
    activityWorker.registerActivitiesImplementations(activities);
    factory.start();
    return factory;
  }

  private static IntakeRoomSnapshot awaitState(
      IntakeRoomWorkflow workflow, Predicate<IntakeRoomSnapshot> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    IntakeRoomSnapshot last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = workflow.state();
        if (predicate.test(last)) {
          return last;
        }
      } catch (RuntimeException ignored) {
        // Worker replacement can briefly leave the task queue without a poller.
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    throw new AssertionError("Intake state did not converge; last=" + last);
  }

  private static IntakeRoomStart start() {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        0,
        0,
        1,
        1,
        "intake-workflow.synthetic.v1",
        "2.0.0",
        "intake-checkpoint.v2",
        "intake-prompt.v2",
        "intake-model.synthetic.v1",
        "intake-turn-proposal.v2",
        "intake-policy.v2",
        "intake-guardrail.v2",
        "no-tools.v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static IntakeWorkflowCommand command(
      long sequence, String commandId, IntakeCommandType type, BranchOperation branchOperation) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        sequence,
        type,
        IntakeParty.INITIATOR,
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash(sequence + 1),
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v1",
            THREAD_ID,
            AGENT_SESSION,
            Long.MAX_VALUE,
            new RetryBudget("intake-retry-budget.v1", 2, 2, 1),
            branchOperation));
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }
}
