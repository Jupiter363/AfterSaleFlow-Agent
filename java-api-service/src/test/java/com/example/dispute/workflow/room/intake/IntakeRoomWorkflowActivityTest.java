package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityTemporalPolicy;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityFailureTypes;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.FormalFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityStage;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandDecision;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomCarryState;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeTerminalReason;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class IntakeRoomWorkflowActivityTest {

  private static final String TENANT = "tenant-p4-b2-workflow";
  private static final String CASE_ID = "CASE_P4_B2_WORKFLOW";
  private static final long EPOCH = 4;
  private static final long FENCE = 17;
  private static final String INITIATOR_SCOPE = "a".repeat(64);
  private static final String RESPONDENT_SCOPE = "b".repeat(64);
  private static final String THREAD_ID = "grt.v1." + "c".repeat(32);
  private static final String AGENT_SESSION = "AGENT_SESSION_P4_B2";

  @Test
  void messageAndAcceptanceUseDistinctStageKeysAndCommitOnlyFromReceipts() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-activity-workflow";
      FakeActivities activities = new FakeActivities();
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

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

      IntakeWorkflowCommand message =
          command(1, "CMD_ACTIVITY_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, null);
      workflow.commandAccepted(message);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot ready = workflow.state();
      assertThat(ready.roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(ready.pendingCommand()).isNull();
      assertThat(ready.currentActivityOperationKey()).isNull();
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(1);
      assertThat(activities.finalizationRequests).hasSize(1);
      assertThat(activities.snapshotRequests.getFirst().operationKey())
          .isEqualTo(
              IntakeOperationKeys.snapshotPublish(CASE_ID, EPOCH, INITIATOR_SCOPE, 0));
      assertThat(activities.graphRequests.getFirst().operationKey())
          .isEqualTo(IntakeOperationKeys.graphExecute(CASE_ID, EPOCH, THREAD_ID, message.commandId()));
      assertThat(activities.finalizationRequests.getFirst().operationKey())
          .isEqualTo(
              IntakeOperationKeys.turnFinalize(
                  CASE_ID, EPOCH, THREAD_ID, message.commandId(), hash(7)));
      assertThat(activities.graphRequests.getFirst().envelope().retryBudget().activityAttemptsRemaining())
          .isEqualTo(1);
      assertThat(activities.graphRequests.getFirst().envelope().invocation().mode())
          .isEqualTo(ActivityInvocationMode.FIRST_EXECUTION);

      IntakeWorkflowCommand accept =
          command(
              2,
              "CMD_ACTIVITY_ACCEPT",
              IntakeCommandType.INTAKE_CONFIRM,
              BranchOperation.INITIATOR_ACCEPT);
      workflow.commandAccepted(accept);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot waitingForRespondent = workflow.state();
      assertThat(waitingForRespondent.roomPhase()).isEqualTo(IntakeRoomPhase.WAITING_PARTY);
      assertThat(waitingForRespondent.respondentUnlocked()).isTrue();
      assertThat(activities.acceptRequests).hasSize(1);
      assertThat(activities.acceptRequests.getFirst().operationKey())
          .isEqualTo(IntakeOperationKeys.initiatorAccept(CASE_ID, EPOCH, accept.commandId()));
      assertThat(activities.snapshotRequests).hasSize(1);
    }
  }

  @Test
  void policyCapsAndPropagatesTheFrozenActivityRetryBudget() {
    ActivityOptions options =
        IntakeActivityTemporalPolicy.options(
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 2, 2, 1));
    assertThat(options.getTaskQueue()).isEqualTo(AGENT_EXECUTION);
    assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(1);

    ActivityOptions reconciliationOnly =
        IntakeActivityTemporalPolicy.options(
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 0, 0, 0));
    assertThat(reconciliationOnly.getRetryOptions().getMaximumAttempts()).isEqualTo(1);

    ActivityOptions deadlineBounded =
        IntakeActivityTemporalPolicy.options(
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 2, 3, 1),
            Duration.ofSeconds(2));
    assertThat(deadlineBounded.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(deadlineBounded.getScheduleToCloseTimeout()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void nonRetryableActivityFailureLeavesTheExactStagePending() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-activity-failure";
      FakeActivities activities = new FakeActivities(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:" + CASE_ID + ":" + EPOCH + ":failure")
                      .setTaskQueue(workflowQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_ACTIVITY_REJECTED", IntakeCommandType.INTAKE_MESSAGE, null));
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot state = workflow.state();
      assertThat(state.roomPhase()).isEqualTo(IntakeRoomPhase.AGENT_RUNNING);
      assertThat(state.pendingCommandId()).isEqualTo("CMD_ACTIVITY_REJECTED");
      assertThat(state.activityExecution().stage()).isEqualTo(IntakeActivityStage.GRAPH_EXECUTION);
      assertThat(state.activityExecution().terminalFailure().failureType())
          .isEqualTo(IntakeActivityFailureTypes.SCHEMA);
      assertThat(state.protocolErrorCode()).isEqualTo(IntakeActivityFailureTypes.SCHEMA);
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(1);
      assertThat(activities.finalizationRequests).isEmpty();
    }
  }

  @Test
  void terminalFinalizationFailureReplaysWithoutActivityAndCommittedEventCanAdvance() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-terminal-finalization";
      FakeActivities activities = new FakeActivities();
      activities.failFinalizationAfterCommitNonRetryable.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "terminal-finalization");
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand command =
          command(1, "CMD_TERMINAL_FINALIZATION", IntakeCommandType.INTAKE_MESSAGE, null);
      workflow.commandAccepted(command);
      IntakeRoomSnapshot failed =
          awaitState(
              workflow,
              state -> IntakeActivityFailureTypes.SCHEMA.equals(state.protocolErrorCode()));

      assertThat(failed.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.TURN_FINALIZATION);
      assertThat(failed.activityExecution().terminalFailure().failureType())
          .isEqualTo(IntakeActivityFailureTypes.SCHEMA);
      assertThat(activities.finalizationRequests).hasSize(1);

      workflow.commandAccepted(command);
      IntakeCommandDecision duplicateDecision =
          awaitDecision(workflow, decision -> "DUPLICATE".equals(decision.status()));
      IntakeRoomSnapshot duplicate = workflow.state();
      assertThat(duplicateDecision.reasonCode()).isNull();
      assertThat(duplicate.protocolErrorCode()).isEqualTo(IntakeActivityFailureTypes.SCHEMA);
      assertThat(duplicate.pendingCommandId()).isEqualTo(command.commandId());
      assertThat(activities.finalizationRequests).hasSize(1);

      workflow.requestContinueAsNew();
      environment.sleep(Duration.ofSeconds(1));
      assertThat(workflow.state().runGeneration()).isZero();

      TurnFinalizationReceipt committed =
          activities.finalizationReceipts.values().iterator().next();
      workflow.domainEventCommitted(committed.committedEvent());
      IntakeRoomSnapshot advanced =
          awaitState(workflow, state -> state.pendingCommand() == null);
      assertThat(advanced.roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(advanced.activityExecution()).isNull();
      assertThat(activities.finalizationRequests).hasSize(1);
    }
  }

  @Test
  void sharedInfrastructureRetryPoolDoesNotChargeTheFirstExecutionOfANewStage() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-shared-retry-pool";
      FakeActivities activities = new FakeActivities();
      activities.snapshotInfrastructureFailures.set(1);
      activities.graphInfrastructureFailures.set(1);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "shared-retry-pool");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_SHARED_RETRY_POOL", IntakeCommandType.INTAKE_MESSAGE, null, 2));
      IntakeRoomSnapshot exhausted =
          awaitState(
              workflow,
              state ->
                  IntakeActivityFailureTypes.RETRY_BUDGET_EXHAUSTED.equals(
                      state.protocolErrorCode()));

      assertThat(exhausted.pendingCommandId()).isEqualTo("CMD_SHARED_RETRY_POOL");
      assertThat(exhausted.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.GRAPH_EXECUTION);
      assertThat(exhausted.activityExecution().invocation().mode())
          .isEqualTo(ActivityInvocationMode.RECONCILE_ONLY);
      assertThat(
              activities.snapshotRequests.stream()
                  .map(request -> request.envelope().invocation().mode()))
          .containsExactly(
              ActivityInvocationMode.FIRST_EXECUTION,
              ActivityInvocationMode.INFRASTRUCTURE_RETRY);
      assertThat(
              activities.graphRequests.stream()
                  .map(request -> request.envelope().invocation().mode()))
          .containsExactly(
              ActivityInvocationMode.FIRST_EXECUTION,
              ActivityInvocationMode.RECONCILE_ONLY);
      assertThat(activities.finalizationRequests).isEmpty();
    }
  }

  @Test
  void nestedTemporalTimeoutsConsumeTheSharedPoolBeforeReconciliation() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-timeout-retry-pool";
      FakeActivities activities = new FakeActivities();
      activities.graphTimeoutFailures.add(TimeoutType.TIMEOUT_TYPE_HEARTBEAT);
      activities.graphTimeoutFailures.add(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE);
      activities.graphTimeoutFailures.add(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "timeout-retry-pool");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_TIMEOUT_RETRY_POOL", IntakeCommandType.INTAKE_MESSAGE, null, 3));
      IntakeRoomSnapshot exhausted =
          awaitState(
              workflow,
              state ->
                  IntakeActivityFailureTypes.RETRY_BUDGET_EXHAUSTED.equals(
                      state.protocolErrorCode()));

      assertThat(exhausted.activityExecution().terminalFailure()).isNull();
      assertThat(exhausted.activityExecution().invocation().mode())
          .isEqualTo(ActivityInvocationMode.RECONCILE_ONLY);
      assertThat(
              activities.graphRequests.stream()
                  .map(request -> request.envelope().invocation().mode()))
          .containsExactly(
              ActivityInvocationMode.FIRST_EXECUTION,
              ActivityInvocationMode.INFRASTRUCTURE_RETRY,
              ActivityInvocationMode.INFRASTRUCTURE_RETRY,
              ActivityInvocationMode.RECONCILE_ONLY);
      assertThat(activities.finalizationRequests).isEmpty();
    }
  }

  @Test
  void finalizerCompletionLossRetriesOnlyTheIdempotentStage() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-finalizer-completion-loss";
      FakeActivities activities = new FakeActivities(false, true, false);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow =
          workflow(environment, workflowQueue, "completion-loss");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_COMPLETION_LOSS", IntakeCommandType.INTAKE_MESSAGE, null, 1));
      environment.sleep(Duration.ofSeconds(2));

      assertThat(workflow.state().roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(1);
      assertThat(activities.finalizationRequests).hasSize(2);
      assertThat(activities.finalizationCommits).hasSize(1);
      assertThat(
              activities.finalizationRequests.stream()
                  .map(request -> request.envelope().invocation().mode()))
          .containsExactly(
              ActivityInvocationMode.FIRST_EXECUTION,
              ActivityInvocationMode.RECONCILE_ONLY);
    }
  }

  @Test
  void committedFinalizerCompletionSettlesBeforeTheNextCommandStarts() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-finalizer-settlement";
      FakeActivities activities = new FakeActivities();
      activities.blockFinalizationAfterCommit.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "finalizer-settlement");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_VISIBLE_FINALIZER", IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(activities.finalizationCommitVisible, "Finalizer commit was not made visible");

      TurnFinalizationReceipt committed =
          activities.finalizationReceipts.values().iterator().next();
      workflow.domainEventCommitted(wrongStageEvent(committed.committedEvent()));
      awaitState(
          workflow,
          state -> "EVENT_ACTIVITY_STAGE_MISMATCH".equals(state.protocolErrorCode()));

      workflow.commandAccepted(
          command(
              2,
              "CMD_AFTER_VISIBLE_FINALIZER",
              IntakeCommandType.INTAKE_CONFIRM,
              BranchOperation.INITIATOR_ACCEPT));
      activities.releaseFinalizationAfterCommit.set(true);
      IntakeRoomSnapshot accepted = awaitState(workflow, IntakeRoomSnapshot::respondentUnlocked);

      assertThat(accepted.roomPhase()).isEqualTo(IntakeRoomPhase.WAITING_PARTY);
      assertThat(accepted.protocolErrorCode()).isNull();
      assertThat(accepted.nextCommandSequence()).isEqualTo(3);
      assertThat(accepted.processedCommandCount()).isEqualTo(2);
      assertThat(activities.finalizationRequests).hasSize(1);
      assertThat(activities.finalizationCancellations).hasValue(0);
      assertThat(activities.acceptRequests).hasSize(1);
    }
  }

  @Test
  void branchReceiptMustMatchTheExactRequestAuthority() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-branch-receipt-authority";
      FakeActivities activities = new FakeActivities();
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "branch-authority");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_READY_FOR_BAD_BRANCH", IntakeCommandType.INTAKE_MESSAGE, null));
      awaitState(workflow, state -> state.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);

      activities.corruptAcceptanceAuthority.set(true);
      workflow.commandAccepted(
          command(
              2,
              "CMD_BAD_BRANCH_AUTHORITY",
              IntakeCommandType.INTAKE_CONFIRM,
              BranchOperation.INITIATOR_ACCEPT));
      IntakeRoomSnapshot rejected =
          awaitState(
              workflow,
              state -> "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(state.protocolErrorCode()));

      assertThat(rejected.pendingCommandId()).isEqualTo("CMD_BAD_BRANCH_AUTHORITY");
      assertThat(rejected.respondentUnlocked()).isFalse();
      assertThat(rejected.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.INITIATOR_ACCEPTANCE);
    }
  }

  @Test
  void staleFinalizationReceiptCannotAdvanceThePendingStage() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-stale-finalization";
      FakeActivities activities = new FakeActivities(false, false, true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "stale-finalization");
      WorkflowClient.start(workflow::run, start(5));
      workflow.commandAccepted(
          command(1, "CMD_STALE_FINALIZATION", IntakeCommandType.INTAKE_MESSAGE, null));
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot state = workflow.state();
      assertThat(state.protocolErrorCode()).isEqualTo("INTAKE_ACTIVITY_RECEIPT_INVALID");
      assertThat(state.processRevision()).isEqualTo(5);
      assertThat(state.pendingCommandId()).isEqualTo("CMD_STALE_FINALIZATION");
      assertThat(state.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.TURN_FINALIZATION);
      assertThat(activities.finalizationCommits).hasSize(1);
    }
  }

  @Test
  void finalizationReceiptWithAnotherCheckpointFailsBeforeEventApplication() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-finalization-authority";
      FakeActivities activities = new FakeActivities();
      activities.corruptFinalizationGraphBinding.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "finalization-authority");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_BAD_FINALIZATION_AUTHORITY", IntakeCommandType.INTAKE_MESSAGE, null));
      IntakeRoomSnapshot rejected =
          awaitState(
              workflow,
              state -> "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(state.protocolErrorCode()));

      assertThat(rejected.pendingCommandId()).isEqualTo("CMD_BAD_FINALIZATION_AUTHORITY");
      assertThat(rejected.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.TURN_FINALIZATION);
      assertThat(rejected.nextEventSequence()).isEqualTo(1);
      assertThat(activities.finalizationRequests).hasSize(1);
    }
  }

  @Test
  void initiatorCancellationStopsTheRunningGraphBeforeItsTerminalCommit() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-running-cancellation";
      FakeActivities activities = new FakeActivities(false, false, false, true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "running-cancellation");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_RUNNING_GRAPH", IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(activities.graphStarted, "Graph Activity did not start");

      workflow.commandAccepted(
          command(
              2,
              "CMD_CANCEL_RUNNING_GRAPH",
              IntakeCommandType.INTAKE_CANCEL,
              BranchOperation.CANCEL));
      IntakeRoomSnapshot terminal =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);

      assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
      assertThat(terminal.nextCommandSequence()).isEqualTo(3);
      assertThat(terminal.nextEventSequence()).isEqualTo(2);
      assertThat(activities.graphCancellations).hasValue(1);
      assertThat(activities.finalizationRequests).isEmpty();
      assertThat(activities.cancelRequests).hasSize(1);
    }
  }

  @Test
  void committedTurnEventWinsWhenQueuedBeforeCancellation() {
    assertCommittedTurnEventWinsCancellationRace(true);
  }

  @Test
  void committedTurnEventWinsWhenQueuedAfterCancellationBeforeCompletion() {
    assertCommittedTurnEventWinsCancellationRace(false);
  }

  @Test
  void lateCommittedTurnIsReconciledBeforeCancellationStarts() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-late-finalization-cancel";
      FakeActivities activities = new FakeActivities();
      activities.blockFinalizationAfterCommit.set(true);
      activities.blockFinalizationCancellationCompletion.set(true);
      activities.blockFinalizationReconciliation.set(true);
      activities.blockCancellation.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow =
          workflow(environment, workflowQueue, "late-finalization-cancel");
      WorkflowClient.start(workflow::run, start());
      String commandId = "CMD_LATE_FINALIZATION_CANCEL";
      workflow.commandAccepted(
          command(1, commandId, IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(activities.finalizationCommitVisible, "Finalizer commit was not made visible");
      TurnFinalizationReceipt committed =
          activities.finalizationReceipts.values().iterator().next();

      workflow.commandAccepted(
          command(
              2,
              "CMD_CANCEL_AFTER_LATE_FINALIZATION",
              IntakeCommandType.INTAKE_CANCEL,
              BranchOperation.CANCEL));
      awaitCount(
          activities.finalizationCancellations,
          1,
          "Cancellation did not reach the committed Finalizer completion");
      assertThat(activities.finalizationReconciliationRequests).hasValue(0);

      activities.releaseFinalizationCancellationCompletion.set(true);
      awaitTrue(
          activities.finalizationReconciliationStarted,
          "Finalizer reconciliation did not start after cancellation completed");
      assertThat(activities.cancelRequests).isEmpty();
      assertThat(activities.finalizationReconciliationRequests).hasValue(1);
      assertThat(
              activities.finalizationRequests.stream()
                  .map(request -> request.envelope().invocation().mode()))
          .containsExactly(
              ActivityInvocationMode.FIRST_EXECUTION,
              ActivityInvocationMode.RECONCILE_ONLY);
      TurnFinalizationRequest firstFinalization = activities.finalizationRequests.getFirst();
      TurnFinalizationRequest reconciliation = activities.finalizationRequests.getLast();
      assertThat(reconciliation.operationKey()).isEqualTo(firstFinalization.operationKey());
      assertThat(reconciliation.requestHash()).isEqualTo(firstFinalization.requestHash());
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(1);

      activities.releaseFinalizationReconciliation.set(true);
      awaitTrue(
          activities.cancellationStarted,
          "Cancellation Activity did not start after committed receipt reconciliation");
      IntakeRoomSnapshot cancellationActive =
          awaitState(
              workflow,
              state ->
                  state.processedEventCount() == 1
                      && "CMD_CANCEL_AFTER_LATE_FINALIZATION".equals(
                          state.pendingCommandId()));
      assertThat(cancellationActive.lastEventId()).isEqualTo(committed.committedEvent().eventId());
      workflow.domainEventCommitted(committed.committedEvent());
      activities.releaseCancellation.set(true);

      IntakeRoomSnapshot terminal =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
      assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
      assertThat(terminal.processedEventCount()).isEqualTo(2);
      assertThat(terminal.nextEventSequence()).isEqualTo(3);
      assertThat(activities.finalizationCommits).hasSize(1);
      assertThat(activities.finalizationReceipts).hasSize(1);
      assertThat(activities.cancelRequests).hasSize(1);
    }
  }

  @Test
  void definitiveAbsentFinalizationAllowsCancellationOnlyAfterReconciliation() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-absent-finalization-cancel";
      FakeActivities activities = new FakeActivities();
      activities.blockFinalizationBeforeCommit.set(true);
      activities.blockFinalizationReconciliation.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow =
          workflow(environment, workflowQueue, "absent-finalization-cancel");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_ABSENT_FINALIZATION", IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(
          activities.finalizationExecutionStarted,
          "Finalizer did not start before the cancellation signal");

      workflow.commandAccepted(
          command(
              2,
              "CMD_CANCEL_ABSENT_FINALIZATION",
              IntakeCommandType.INTAKE_CANCEL,
              BranchOperation.CANCEL));
      awaitCount(
          activities.finalizationCancellations,
          1,
          "Cancellation did not stop the uncommitted Finalizer");
      awaitTrue(
          activities.finalizationReconciliationStarted,
          "Finalizer reconciliation did not start after cancellation completed");
      assertThat(activities.finalizationReceipts).isEmpty();
      assertThat(activities.cancelRequests).isEmpty();

      activities.releaseFinalizationReconciliation.set(true);
      awaitTrue(
          activities.cancellationStarted,
          "Cancellation Activity did not start after definitive absence");
      IntakeRoomSnapshot terminal =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);

      assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
      assertThat(terminal.processedEventCount()).isEqualTo(1);
      assertThat(terminal.nextEventSequence()).isEqualTo(2);
      assertThat(activities.finalizationReconciliationRequests).hasValue(1);
      assertThat(activities.cancelRequests).hasSize(1);
    }
  }

  @Test
  void finalizationReconciliationInfrastructureFailureKeepsCancellationFailClosed() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-unresolved-finalization-cancel";
      FakeActivities activities = new FakeActivities();
      activities.blockFinalizationBeforeCommit.set(true);
      activities.finalizationReconciliationInfrastructureFailures.set(1);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow =
          workflow(environment, workflowQueue, "unresolved-finalization-cancel");
      WorkflowClient.start(workflow::run, start());
      String originalCommandId = "CMD_UNRESOLVED_FINALIZATION";
      workflow.commandAccepted(
          command(1, originalCommandId, IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(
          activities.finalizationExecutionStarted,
          "Finalizer did not start before the cancellation signal");

      workflow.commandAccepted(
          command(
              2,
              "CMD_CANCEL_UNRESOLVED_FINALIZATION",
              IntakeCommandType.INTAKE_CANCEL,
              BranchOperation.CANCEL));
      awaitCount(
          activities.finalizationCancellations,
          1,
          "Cancellation did not stop the uncommitted Finalizer");
      IntakeRoomSnapshot unresolved =
          awaitState(
              workflow,
              state ->
                  IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE.equals(
                      state.protocolErrorCode()));

      assertThat(unresolved.pendingCommandId()).isEqualTo(originalCommandId);
      assertThat(unresolved.activityExecution().stage())
          .isEqualTo(IntakeActivityStage.TURN_FINALIZATION);
      assertThat(unresolved.activityExecution().invocation().mode())
          .isEqualTo(ActivityInvocationMode.RECONCILE_ONLY);
      assertThat(unresolved.nextCommandSequence()).isEqualTo(2);
      assertThat(unresolved.processedCommandCount()).isEqualTo(1);
      assertThat(activities.finalizationReconciliationRequests).hasValue(1);
      assertThat(activities.cancelRequests).isEmpty();

      TurnFinalizationRequest canceledRequest = activities.finalizationRequests.getFirst();
      GraphExecutionReceipt graph = canceledRequest.graphExecution();
      long revision =
          Math.max(
              canceledRequest.envelope().processRevision(),
              canceledRequest.envelope().commandSequence());
      IntakeDomainEventRef lateCommitted =
          FakeActivities.event(
              "EVENT_" + originalCommandId,
              canceledRequest.envelope(),
              canceledRequest.envelope().commandSequence(),
              IntakeDomainEventType.TURN_READY_TO_CONFIRM,
              canceledRequest.operationKey(),
              graph.operation().resultHash(),
              revision,
              graph.agentRunRef(),
              graph.graphExecutionRef());
      activities.blockFinalizationBeforeCommit.set(false);
      workflow.domainEventCommitted(lateCommitted);

      awaitTrue(
          activities.cancellationStarted,
          "Deferred cancellation was not retained after reconciliation failure");
      IntakeRoomSnapshot terminal =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
      assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
      assertThat(terminal.processedEventCount()).isEqualTo(2);
      assertThat(activities.cancelRequests).hasSize(1);
    }
  }

  @Test
  void continueAsNewCarriesSequencesDedupeAndOneTimeSnapshotInitialization() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-continue-as-new";
      FakeActivities activities = new FakeActivities();
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "continue-as-new");
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_BEFORE_ROLLOVER", IntakeCommandType.INTAKE_MESSAGE, null));
      awaitState(workflow, state -> state.nextEventSequence() == 2);

      workflow.requestContinueAsNew();
      IntakeRoomSnapshot carried = awaitState(workflow, state -> state.runGeneration() == 1);
      assertThat(carried.nextCommandSequence()).isEqualTo(2);
      assertThat(carried.nextEventSequence()).isEqualTo(2);

      workflow.commandAccepted(
          command(2, "CMD_AFTER_ROLLOVER", IntakeCommandType.INTAKE_MESSAGE, null));
      IntakeRoomSnapshot after = awaitState(workflow, state -> state.nextEventSequence() == 3);
      assertThat(after.runGeneration()).isEqualTo(1);
      assertThat(after.processedCommandCount()).isEqualTo(2);
      assertThat(after.processedEventCount()).isEqualTo(2);
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(2);
      assertThat(activities.finalizationRequests).hasSize(2);
    }
  }

  @Test
  void initialRunRejectsCallerSuppliedCarryState() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String workflowQueue = "phase4-intake-untrusted-carry";
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(
          WorkflowImplementationOptions.newBuilder()
              .setFailWorkflowExceptionTypes(IllegalStateException.class)
              .build(),
          IntakeRoomWorkflowImpl.class);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, "untrusted-carry");
      WorkflowClient.start(workflow::run, start().withCarryState(IntakeRoomCarryState.initial()));

      assertThatThrownBy(
              () ->
                  WorkflowStub.fromTyped(workflow)
                      .getResult(IntakeRoomSnapshot.class))
          .isInstanceOf(WorkflowFailedException.class);
    }
  }

  private static void assertCommittedTurnEventWinsCancellationRace(
      boolean eventBeforeCancellation) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String order = eventBeforeCancellation ? "event-first" : "cancel-first";
      String workflowQueue = "phase4-intake-finalization-cancel-race-" + order;
      FakeActivities activities = new FakeActivities();
      activities.blockFinalizationAfterCommit.set(true);
      activities.blockFinalizationCancellationCompletion.set(true);
      Worker workflowWorker = environment.newWorker(workflowQueue);
      workflowWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker activityWorker = environment.newWorker(AGENT_EXECUTION);
      activityWorker.registerActivitiesImplementations(activities);
      environment.start();

      IntakeRoomWorkflow workflow = workflow(environment, workflowQueue, order);
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(
          command(1, "CMD_COMMITTED_RACE_" + order, IntakeCommandType.INTAKE_MESSAGE, null));
      awaitTrue(activities.finalizationCommitVisible, "Finalizer commit was not made visible");
      TurnFinalizationReceipt committed =
          activities.finalizationReceipts.values().iterator().next();
      IntakeWorkflowCommand cancellation =
          command(
              2,
              "CMD_CANCEL_COMMITTED_RACE_" + order,
              IntakeCommandType.INTAKE_CANCEL,
              BranchOperation.CANCEL);

      if (eventBeforeCancellation) {
        workflow.domainEventCommitted(committed.committedEvent());
        awaitCount(
            activities.finalizationCancellations,
            1,
            "Committed event did not cancel the outstanding completion");
        workflow.commandAccepted(cancellation);
      } else {
        workflow.commandAccepted(cancellation);
        awaitCount(
            activities.finalizationCancellations,
            1,
            "Cancellation did not reach the committed Finalizer completion");
        workflow.domainEventCommitted(committed.committedEvent());
      }
      activities.releaseFinalizationCancellationCompletion.set(true);

      IntakeRoomSnapshot terminal =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
      assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
      assertThat(terminal.nextCommandSequence()).isEqualTo(3);
      assertThat(terminal.nextEventSequence()).isEqualTo(3);
      assertThat(terminal.processedEventCount()).isEqualTo(2);
      assertThat(terminal.protocolErrorCode()).isNull();
      assertThat(activities.finalizationRequests).hasSize(1);
      assertThat(activities.cancelRequests).hasSize(1);
    }
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
        // A Continue-As-New run can briefly move between executions while the query retries.
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

  private static IntakeCommandDecision awaitDecision(
      IntakeRoomWorkflow workflow, Predicate<IntakeCommandDecision> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    IntakeCommandDecision last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = workflow.lastCommandDecision();
        if (last != null && predicate.test(last)) {
          return last;
        }
      } catch (RuntimeException ignored) {
        // A Continue-As-New run can briefly move between executions while the query retries.
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    throw new AssertionError("Intake decision did not converge; last=" + last);
  }

  private static void awaitTrue(AtomicBoolean value, String failureMessage) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!value.get() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    if (!value.get()) {
      throw new AssertionError(failureMessage);
    }
  }

  private static void awaitCount(
      AtomicInteger value, int expected, String failureMessage) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (value.get() < expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    if (value.get() < expected) {
      throw new AssertionError(failureMessage);
    }
  }

  private static IntakeRoomWorkflow workflow(
      TestWorkflowEnvironment environment, String taskQueue, String suffix) {
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            IntakeRoomWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId("intake-room:" + CASE_ID + ":" + EPOCH + ":" + suffix)
                .setTaskQueue(taskQueue)
                .build());
  }

  private static IntakeRoomStart start() {
    return start(0);
  }

  private static IntakeRoomStart start(long initialRevision) {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        initialRevision,
        initialRevision,
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
    return command(sequence, commandId, type, branchOperation, 2);
  }

  private static IntakeWorkflowCommand command(
      long sequence,
      String commandId,
      IntakeCommandType type,
      BranchOperation branchOperation,
      int activityAttemptsRemaining) {
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
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 2, activityAttemptsRemaining, 1),
            branchOperation));
  }

  private static IntakeDomainEventRef wrongStageEvent(IntakeDomainEventRef committed) {
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        committed.eventId() + "_WRONG_STAGE",
        committed.eventRef() + ":wrong-stage",
        hash(4),
        committed.eventSequence(),
        IntakeDomainEventType.INITIATOR_ACCEPTED,
        committed.party(),
        committed.commandId(),
        committed.tenantSurrogate(),
        committed.caseId(),
        committed.roomEpoch(),
        committed.fencingToken(),
        committed.actorScopeHash(),
        committed.operationKey(),
        committed.requestHash(),
        committed.resultHash(),
        committed.processRevision(),
        committed.roomRevision(),
        null,
        null);
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }

  static final class FakeActivities implements IntakeRoomActivities {

    final boolean failGraph;
    final boolean loseFirstFinalizationCompletion;
    final boolean staleFinalization;
    final boolean blockGraph;
    final AtomicInteger snapshotInfrastructureFailures = new AtomicInteger();
    final AtomicInteger graphInfrastructureFailures = new AtomicInteger();
    final Queue<TimeoutType> graphTimeoutFailures = new ConcurrentLinkedQueue<>();
    final AtomicBoolean releaseGraph = new AtomicBoolean();
    final AtomicBoolean blockFinalizationBeforeCommit = new AtomicBoolean();
    final AtomicBoolean blockFinalizationAfterCommit = new AtomicBoolean();
    final AtomicBoolean releaseFinalizationAfterCommit = new AtomicBoolean();
    final AtomicBoolean blockFinalizationCancellationCompletion = new AtomicBoolean();
    final AtomicBoolean releaseFinalizationCancellationCompletion = new AtomicBoolean();
    final AtomicBoolean blockFinalizationReconciliation = new AtomicBoolean();
    final AtomicBoolean releaseFinalizationReconciliation = new AtomicBoolean();
    final AtomicBoolean finalizationExecutionStarted = new AtomicBoolean();
    final AtomicBoolean finalizationReconciliationStarted = new AtomicBoolean();
    final AtomicBoolean finalizationCommitVisible = new AtomicBoolean();
    final AtomicBoolean blockCancellation = new AtomicBoolean();
    final AtomicBoolean releaseCancellation = new AtomicBoolean();
    final AtomicBoolean cancellationStarted = new AtomicBoolean();
    final AtomicBoolean corruptAcceptanceAuthority = new AtomicBoolean();
    final AtomicBoolean corruptFinalizationGraphBinding = new AtomicBoolean();
    final AtomicBoolean failFinalizationAfterCommitNonRetryable = new AtomicBoolean();
    final AtomicInteger finalizationReconciliationInfrastructureFailures = new AtomicInteger();
    final AtomicInteger finalizationReconciliationRequests = new AtomicInteger();
    final List<SnapshotPublicationRequest> snapshotRequests = new CopyOnWriteArrayList<>();
    final List<GraphExecutionRequest> graphRequests = new CopyOnWriteArrayList<>();
    final List<TurnFinalizationRequest> finalizationRequests = new CopyOnWriteArrayList<>();
    final List<BranchCommitRequest> acceptRequests = new CopyOnWriteArrayList<>();
    final List<BranchCommitRequest> cancelRequests = new CopyOnWriteArrayList<>();
    final Set<String> finalizationCommits = ConcurrentHashMap.newKeySet();
    final Map<String, SnapshotPublicationReceipt> snapshotReceipts = new ConcurrentHashMap<>();
    final Map<String, GraphExecutionReceipt> graphReceipts = new ConcurrentHashMap<>();
    final Map<String, TurnFinalizationReceipt> finalizationReceipts = new ConcurrentHashMap<>();
    final Map<String, BranchCommitReceipt> branchReceipts = new ConcurrentHashMap<>();
    final AtomicBoolean graphStarted = new AtomicBoolean();
    final AtomicInteger graphCancellations = new AtomicInteger();
    final AtomicInteger finalizationCancellations = new AtomicInteger();

    FakeActivities() {
      this(false, false, false, false);
    }

    FakeActivities(boolean failGraph) {
      this(failGraph, false, false, false);
    }

    FakeActivities(
        boolean failGraph,
        boolean loseFirstFinalizationCompletion,
        boolean staleFinalization) {
      this(failGraph, loseFirstFinalizationCompletion, staleFinalization, false);
    }

    FakeActivities(
        boolean failGraph,
        boolean loseFirstFinalizationCompletion,
        boolean staleFinalization,
        boolean blockGraph) {
      this.failGraph = failGraph;
      this.loseFirstFinalizationCompletion = loseFirstFinalizationCompletion;
      this.staleFinalization = staleFinalization;
      this.blockGraph = blockGraph;
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
      snapshotRequests.add(request);
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return snapshotReceipts.get(request.operationKey());
      }
      if (consumeFailure(snapshotInfrastructureFailures)) {
        throw ApplicationFailure.newFailure(
            "synthetic snapshot infrastructure failure",
            IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
      }
      SnapshotPublicationReceipt produced =
          new SnapshotPublicationReceipt(
              "intake-snapshot-publication-receipt.v1",
              operation(request.operationKey(), request.requestHash(), hash(1), 0, 0),
              new ImmutablePayloadRef(
                  "immutable-payload-ref.v1",
                  "SNAPSHOT_" + request.envelope().commandId(),
                  "INTAKE_SNAPSHOT",
                  "intake-domain-snapshot.v2",
                  "urn:after-sale-flow:intake-snapshot:" + request.envelope().commandId(),
                  "VERSION_SNAPSHOT_" + request.envelope().commandId(),
                  hash(2),
                  1024),
              request.domainRevision());
      SnapshotPublicationReceipt existing =
          snapshotReceipts.putIfAbsent(request.operationKey(), produced);
      return existing == null ? produced : existing;
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
      graphRequests.add(request);
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return graphReceipts.get(request.operationKey());
      }
      if (blockGraph && !releaseGraph.get()) {
        graphStarted.set(true);
        try {
          while (true) {
            Activity.getExecutionContext().heartbeat("waiting-for-cancellation");
            Thread.sleep(10);
          }
        } catch (ActivityCompletionException failure) {
          graphCancellations.incrementAndGet();
          throw failure;
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw ApplicationFailure.newFailure(
              "synthetic Graph worker interrupted",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
      }
      if (failGraph) {
        throw ApplicationFailure.newNonRetryableFailure(
            "synthetic schema rejection", IntakeActivityFailureTypes.SCHEMA);
      }
      if (consumeFailure(graphInfrastructureFailures)) {
        throw ApplicationFailure.newFailure(
            "synthetic Graph infrastructure failure",
            IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
      }
      TimeoutType timeoutType = graphTimeoutFailures.poll();
      if (timeoutType != null) {
        throw ApplicationFailure.newFailureWithCause(
            "synthetic nested Graph timeout",
            "SYNTHETIC_TIMEOUT_WRAPPER",
            new TimeoutFailure("synthetic Graph timeout", null, timeoutType));
      }
      String resultHash = hash(7);
      String proposalHash = hash(8);
      IntakeAgentRunRef run =
          new IntakeAgentRunRef(
              "intake-agent-run-ref.v1",
              "RUN_" + request.envelope().commandId(),
              "ATTEMPT_" + request.envelope().commandId(),
              resultHash);
      IntakeGraphExecutionRef graph =
          new IntakeGraphExecutionRef(
              "intake-graph-execution-ref.v1",
              request.threadId(),
              request.envelope().commandId(),
              "intake.v2",
              request.envelope().pinnedVersions().graphVersion(),
              "CHECKPOINT_" + request.envelope().commandId(),
              "urn:after-sale-flow:graph-result:" + request.envelope().commandId(),
              resultHash,
              "urn:after-sale-flow:intake-proposal:" + request.envelope().commandId(),
              proposalHash);
      GraphExecutionReceipt produced =
          new GraphExecutionReceipt(
              "intake-graph-execution-receipt.v1",
              operation(request.operationKey(), request.requestHash(), resultHash, 0, 0),
              run,
              graph,
              new ImmutablePayloadRef(
                  "immutable-payload-ref.v1",
                  "RESULT_" + request.envelope().commandId(),
                  "GRAPH_RESULT",
                  "room-graph-result.v1",
                  graph.resultRef(),
                  "VERSION_RESULT_" + request.envelope().commandId(),
                  resultHash,
                  1024),
              new ImmutablePayloadRef(
                  "immutable-payload-ref.v1",
                  "PROPOSAL_" + request.envelope().commandId(),
                  "INTAKE_PROPOSAL",
                  "intake-turn-proposal.v2",
                  graph.proposalRef(),
                  "VERSION_PROPOSAL_" + request.envelope().commandId(),
                  proposalHash,
                  1024));
      GraphExecutionReceipt existing = graphReceipts.putIfAbsent(request.operationKey(), produced);
      return existing == null ? produced : existing;
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
      finalizationRequests.add(request);
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        finalizationReconciliationRequests.incrementAndGet();
        finalizationReconciliationStarted.set(true);
        try {
          while (blockFinalizationReconciliation.get()
              && !releaseFinalizationReconciliation.get()) {
            Activity.getExecutionContext().heartbeat("reconciling-finalization-receipt");
            Thread.sleep(10);
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw ApplicationFailure.newFailure(
              "synthetic Finalizer reconciliation interrupted",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
        if (consumeFailure(finalizationReconciliationInfrastructureFailures)) {
          throw ApplicationFailure.newFailure(
              "synthetic Finalizer reconciliation infrastructure failure",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
        return finalizationReceipts.get(request.operationKey());
      }
      finalizationExecutionStarted.set(true);
      if (blockFinalizationBeforeCommit.get()) {
        try {
          while (true) {
            Activity.getExecutionContext().heartbeat("finalization-before-commit");
            Thread.sleep(10);
          }
        } catch (ActivityCompletionException failure) {
          finalizationCancellations.incrementAndGet();
          throw failure;
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw ApplicationFailure.newFailure(
              "synthetic Finalizer worker interrupted before commit",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
      }
      long revision =
          staleFinalization
              ? Math.max(0, request.envelope().processRevision() - 1)
              : Math.max(
                  request.envelope().processRevision(),
                  request.envelope().commandSequence());
      GraphExecutionReceipt graph = request.graphExecution();
      IntakeDomainEventRef event =
          event(
              "EVENT_" + request.envelope().commandId(),
              request.envelope(),
              request.envelope().commandSequence(),
              IntakeDomainEventType.TURN_READY_TO_CONFIRM,
              request.operationKey(),
              graph.operation().resultHash(),
              revision,
              graph.agentRunRef(),
              graph.graphExecutionRef());
      if (corruptFinalizationGraphBinding.get()) {
        event = withCheckpoint(event, "CHECKPOINT_CORRUPTED");
      }
      TurnFinalizationReceipt produced =
          new TurnFinalizationReceipt(
              "intake-turn-finalization-activity-receipt.v1",
              operation(
                  request.operationKey(),
                  request.requestHash(),
                  graph.operation().resultHash(),
                  revision,
                  revision),
              formalReceipt(request, event, revision),
              event);
      TurnFinalizationReceipt existing =
          finalizationReceipts.putIfAbsent(request.operationKey(), produced);
      TurnFinalizationReceipt committed = existing == null ? produced : existing;
      boolean firstCommit = finalizationCommits.add(request.operationKey());
      if (firstCommit) {
        finalizationCommitVisible.set(true);
        if (failFinalizationAfterCommitNonRetryable.get()) {
          throw ApplicationFailure.newNonRetryableFailure(
              "synthetic terminal Finalizer failure", IntakeActivityFailureTypes.SCHEMA);
        }
        if (blockFinalizationAfterCommit.get()) {
          try {
            while (!releaseFinalizationAfterCommit.get()) {
              Activity.getExecutionContext().heartbeat("committed-awaiting-completion");
              Thread.sleep(10);
            }
          } catch (ActivityCompletionException failure) {
            finalizationCancellations.incrementAndGet();
            while (blockFinalizationCancellationCompletion.get()
                && !releaseFinalizationCancellationCompletion.get()) {
              try {
                Thread.sleep(10);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
              }
            }
            throw failure;
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ApplicationFailure.newFailure(
                "synthetic Finalizer worker interrupted",
                IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
          }
        }
        if (loseFirstFinalizationCompletion) {
          throw ApplicationFailure.newFailure(
              "completion lost after idempotent finalization commit",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
      }
      return committed;
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return branchReceipts.get(request.operationKey());
      }
      acceptRequests.add(request);
      BranchCommitReceipt receipt =
          branchReceipt(request, IntakeDomainEventType.INITIATOR_ACCEPTED);
      if (!corruptAcceptanceAuthority.get()) {
        return receipt;
      }
      IntakeDomainEventRef event = receipt.committedEvent();
      IntakeDomainEventRef corrupted =
          new IntakeDomainEventRef(
              event.schemaVersion(),
              event.eventId(),
              event.eventRef(),
              event.eventHash(),
              event.eventSequence(),
              event.eventType(),
              event.party(),
              event.commandId(),
              "tenant-corrupted",
              event.caseId(),
              event.roomEpoch(),
              event.fencingToken(),
              event.actorScopeHash(),
              event.operationKey(),
              event.requestHash(),
              event.resultHash(),
              event.processRevision(),
              event.roomRevision(),
              event.agentRunRef(),
              event.graphExecutionRef());
      return new BranchCommitReceipt(
          receipt.schemaVersion(), receipt.branchOperation(), receipt.operation(), corrupted);
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return branchReceipts.get(request.operationKey());
      }
      return branchReceipt(request, IntakeDomainEventType.NOT_ADMISSIBLE);
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return branchReceipts.get(request.operationKey());
      }
      cancelRequests.add(request);
      cancellationStarted.set(true);
      if (blockCancellation.get()) {
        try {
          while (!releaseCancellation.get()) {
            Activity.getExecutionContext().heartbeat("cancellation-active");
            Thread.sleep(10);
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw ApplicationFailure.newFailure(
              "synthetic cancellation worker interrupted",
              IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
        }
      }
      return branchReceipt(request, IntakeDomainEventType.CANCELLED);
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
      if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return branchReceipts.get(request.operationKey());
      }
      return branchReceipt(request, IntakeDomainEventType.RESPONDENT_CONFIRMED);
    }

    private BranchCommitReceipt branchReceipt(
        BranchCommitRequest request, IntakeDomainEventType eventType) {
      long revision = request.envelope().commandSequence();
      long eventSequence =
          (blockGraph || blockFinalizationBeforeCommit.get())
                  && request.operation() == BranchOperation.CANCEL
              ? 1
              : request.envelope().commandSequence();
      String resultHash = hash(9);
      IntakeDomainEventRef event =
          event(
              "EVENT_" + request.envelope().commandId(),
              request.envelope(),
              eventSequence,
              eventType,
              request.operationKey(),
              resultHash,
              revision,
              null,
              null);
      BranchCommitReceipt produced =
          new BranchCommitReceipt(
              "intake-branch-commit-receipt.v1",
              request.operation(),
              operation(
                  request.operationKey(), request.requestHash(), resultHash, revision, revision),
              event);
      BranchCommitReceipt existing = branchReceipts.putIfAbsent(request.operationKey(), produced);
      return existing == null ? produced : existing;
    }

    private static FormalFinalizationReceipt formalReceipt(
        TurnFinalizationRequest request, IntakeDomainEventRef event, long revision) {
      return new FormalFinalizationReceipt(
          "intake-finalization-receipt.v1",
          request.operationKey(),
          request.envelope().tenantSurrogate(),
          request.envelope().caseId(),
          request.envelope().roomEpoch(),
          request.threadId(),
          request.envelope().actorScopeHash(),
          request.agentSessionId(),
          request.envelope().commandId(),
          request.graphExecution().agentRunRef().logicalRunId(),
          request.graphExecution().agentRunRef().attemptId(),
          request.graphExecution().operation().resultHash(),
          request.graphExecution().graphExecutionRef().proposalHash(),
          revision,
          revision,
          request.envelope().fencingToken(),
          "MESSAGE_" + request.envelope().commandId(),
          1L,
          null,
          List.of(event.eventId()),
          List.of("OUTBOX_" + request.envelope().commandId()),
          "COMMITTED",
          "2026-07-21T08:00:00Z",
          hash(6));
    }

    private static IntakeDomainEventRef event(
        String eventId,
        ActivityEnvelope envelope,
        long eventSequence,
        IntakeDomainEventType eventType,
        String operationKey,
        String resultHash,
        long revision,
        IntakeAgentRunRef run,
        IntakeGraphExecutionRef graph) {
      return new IntakeDomainEventRef(
          "intake-domain-event-ref.v1",
          eventId,
          "urn:after-sale-flow:intake-event:" + eventId,
          hash(5),
          eventSequence,
          eventType,
          envelope.party(),
          envelope.commandId(),
          envelope.tenantSurrogate(),
          envelope.caseId(),
          envelope.roomEpoch(),
          envelope.fencingToken(),
          envelope.actorScopeHash(),
          operationKey,
          hash(envelope.commandSequence() + 1),
          resultHash,
          revision,
          revision,
          run,
          graph);
    }

    private static IntakeDomainEventRef withCheckpoint(
        IntakeDomainEventRef event, String checkpointId) {
      IntakeGraphExecutionRef graph = event.graphExecutionRef();
      IntakeGraphExecutionRef corrupted =
          new IntakeGraphExecutionRef(
              graph.schemaVersion(),
              graph.threadId(),
              graph.graphCommandId(),
              graph.graphKey(),
              graph.graphVersion(),
              checkpointId,
              graph.resultRef(),
              graph.resultHash(),
              graph.proposalRef(),
              graph.proposalHash());
      return new IntakeDomainEventRef(
          event.schemaVersion(),
          event.eventId(),
          event.eventRef(),
          event.eventHash(),
          event.eventSequence(),
          event.eventType(),
          event.party(),
          event.commandId(),
          event.tenantSurrogate(),
          event.caseId(),
          event.roomEpoch(),
          event.fencingToken(),
          event.actorScopeHash(),
          event.operationKey(),
          event.requestHash(),
          event.resultHash(),
          event.processRevision(),
          event.roomRevision(),
          event.agentRunRef(),
          corrupted);
    }

    private static OperationReceipt operation(
        String operationKey,
        String requestHash,
        String resultHash,
        long processRevision,
        long roomRevision) {
      return new OperationReceipt(
          "intake-operation-receipt.v1",
          operationKey,
          requestHash,
          resultHash,
          processRevision,
          roomRevision);
    }

    private static boolean consumeFailure(AtomicInteger failures) {
      while (true) {
        int remaining = failures.get();
        if (remaining == 0) {
          return false;
        }
        if (failures.compareAndSet(remaining, remaining - 1)) {
          return true;
        }
      }
    }
  }
}
