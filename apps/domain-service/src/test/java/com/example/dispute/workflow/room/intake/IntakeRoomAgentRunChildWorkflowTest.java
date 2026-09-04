package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.FormalFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunChildIds;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunChildState;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationRecoveryRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationRecoveryResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivitiesAdapter;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult.FinalizationLocator;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult.TerminalNoCommitEvidence;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
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
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTerminalNoCommitRecoveryRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeTerminalNoCommitRecoveryResult;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.google.protobuf.ByteString;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntakeRoomAgentRunChildWorkflowTest {

  private static final String TENANT = "tenant-p9-child";
  private static final String CASE_ID = "CASE_P9_CHILD";
  private static final long EPOCH = 9;
  private static final long FENCE = 41;
  private static final String COMMAND_ID = "CMD_P9_CHILD";
  private static final String THREAD_ID = "grt.v1." + "c".repeat(32);
  private static final String AGENT_SESSION_ID = "AGENT_SESSION_P9_CHILD";
  private static final String LOGICAL_RUN_ID = "RUN_P9_CHILD";
  private static final String ATTEMPT_ID = "ATTEMPT_P9_CHILD_1";
  private static final String ACTIVATION_ID = "p9act.v1." + "a".repeat(32);
  private static final String CONTROL_BUILD = "p9-control-build";
  private static final String GRAPH_VERSION = "p9-graph-v1";
  private static final String CHECKPOINT_SCHEMA = "p9-checkpoint-v1";
  private static final String INITIATOR_SCOPE = "1".repeat(64);
  private static final String RESPONDENT_SCOPE = "2".repeat(64);
  private static final String RESULT_HASH = "7".repeat(64);
  private static final String PROPOSAL_HASH = "8".repeat(64);
  private static final int POST_COMMIT_RECONCILIATION_ATTEMPTS = 5;
  private static final String TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V2_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-parent-convergence-v2";
  private static final String TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V3_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-parent-convergence-v3";
  private static final String GAP_FIRST_COMMAND_ID = "CMD:P9:DEFER:GAP:1";
  private static final String GAP_SECOND_COMMAND_ID = "CMD:P9:DEFER:GAP:2";
  private static final String GAP_BAD_COMMAND_ID = "CMD:P9:DEFER:BAD:2";
  private static final String GAP_CONFIRM_COMMAND_ID = "CMD:P9:DEFER:CONFIRM:2";
  private static final String GAP_BAD_CONFIRM_COMMAND_ID = "CMD:P9:DEFER:CONFIRM:BAD:2";
  private static final String WINNING_FIRST_COMMAND_ID = "CMD:P9:WINNING:GAP:1";
  private static final String WINNING_SECOND_COMMAND_ID = "CMD:P9:WINNING:GAP:2";
  private static final String WINNING_CONFIRM_COMMAND_ID = "CMD:P9:WINNING:CONFIRM:2";
  private static final String WINNING_FORMAL_COMMAND_ID = "CMD:P9:WINNING:FORMAL:2";
  private static final String WINNING_GRAPH_COMMAND_ID = WINNING_FORMAL_COMMAND_ID;
  private static final String WINNING_ATTEMPT_ID = "ATTEMPT:P9:WINNING:2";
  private static final String WINNING_WRONG_COMMAND_ID = "CMD:P9:WINNING:WRONG:COMMAND";
  private static final String WINNING_WRONG_GRAPH_COMMAND_ID = "CMD:P9:WINNING:WRONG:GRAPH";
  private static final String WINNING_RESULT_HASH = "9".repeat(64);
  private static final String WINNING_WRONG_RESULT_HASH = "a".repeat(64);
  private static final String WINNING_PROPOSAL_HASH = "6".repeat(64);
  private static final String LIFECYCLE_FIRST_COMMAND_ID = "CMD:P9:LIFECYCLE:1";
  private static final String LIFECYCLE_SECOND_COMMAND_ID = "CMD:P9:LIFECYCLE:2";
  private static final String THREE_ROUND_FIRST_COMMAND_ID = "CMD:P9:THREE_ROUND:1";
  private static final String THREE_ROUND_SECOND_COMMAND_ID = "CMD:P9:THREE_ROUND:2";
  private static final String THREE_ROUND_THIRD_COMMAND_ID = "CMD:P9:THREE_ROUND:3";
  private static final String TEN_ROUND_COMMAND_PREFIX = "CMD:P9:TEN_ROUND:";
  private static final String FUTURE_CURSOR_FIRST_COMMAND_ID = "CMD:P9:FUTURE_CURSOR:1";
  private static final String FUTURE_CURSOR_SECOND_COMMAND_ID = "CMD:P9:FUTURE_CURSOR:2";

  @Test
  void targetMessageRunsNativeAgentRunChildThenReadsCommittedReceipt() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-room";
      SnapshotOnlyActivities snapshotActivities = new SnapshotOnlyActivities();
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand command = targetCommand();
      workflow.commandAccepted(command);

      IntakeRoomSnapshot state =
          awaitState(workflow, snapshot -> snapshot.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(state.pendingCommand()).isNull();
      assertThat(state.lastAgentRunRef().logicalRunId()).isEqualTo(LOGICAL_RUN_ID);
      assertThat(state.lastGraphExecutionRef().proposalHash()).isEqualTo(PROPOSAL_HASH);
      assertThat(snapshotActivities.snapshotRequests).isEmpty();
      assertThat(snapshotActivities.graphRequests).isEmpty();
      assertThat(snapshotActivities.finalizationRequests).isEmpty();
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      assertThat(RecordingFinalizationReads.requests).hasSize(1);
      assertThat(RecordingFinalizationReads.requests.getFirst().mode())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
      assertThat(IntakeAgentRunChildIds.forCommand(command))
          .isEqualTo("agent-run-v2:" + LOGICAL_RUN_ID);

      IntakeWorkflowCommand second = targetCommand("CMD:P9:CHILD:2", 2, 1);
      workflow.commandAccepted(second);
      IntakeRoomSnapshot continued =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.lastGraphExecutionRef() != null
                      && second.commandId().equals(snapshot.lastGraphExecutionRef().graphCommandId()));
      assertThat(continued.protocolErrorCode()).isNull();
      assertThat(continued.lastGraphExecutionRef().threadId()).isEqualTo(THREAD_ID);
      assertThat(RecordingAgentRunWorkflow.requests)
          .containsExactly(targetRequest(command), targetRequest(second));
      assertThat(RecordingFinalizationReads.requests).hasSize(2);
      assertThat(snapshotActivities.snapshotRequests).isEmpty();
      assertThat(snapshotActivities.graphRequests).isEmpty();
      assertThat(snapshotActivities.finalizationRequests).isEmpty();
    }
  }

  @Test
  void threeTargetTurnsConsumeProjectionAndRoomCursorsBeforeStartingTheThirdChild() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-three-round-global-cursor";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:three-round-cursor:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(THREE_ROUND_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      IntakeRoomSnapshot afterFirst =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 1
                      && snapshot.processedEventCount() == 1
                      && snapshot.pendingCommand() == null);
      assertThat(afterFirst.nextEventSequence()).isEqualTo(2);

      workflow.targetSourceEventObserved(
          cursor(2, "EVENT_P9_THREE_ROUND_READY_2", "INTAKE_PROJECTION_READY"));
      workflow.targetSourceEventObserved(
          cursor(3, "EVENT_P9_THREE_ROUND_ROOM_3", "ROOM_MESSAGE_CREATED"));
      awaitState(workflow, snapshot -> snapshot.nextEventSequence() == 4);

      IntakeWorkflowCommand second = targetCommand(THREE_ROUND_SECOND_COMMAND_ID, 2, 1);
      workflow.commandAccepted(second);
      IntakeRoomSnapshot afterSecond =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null);
      assertThat(afterSecond.nextEventSequence()).isEqualTo(5);

      workflow.targetSourceEventObserved(
          cursor(5, "EVENT_P9_THREE_ROUND_READY_5", "INTAKE_PROJECTION_READY"));
      workflow.targetSourceEventObserved(
          cursor(6, "EVENT_P9_THREE_ROUND_ROOM_6", "ROOM_MESSAGE_CREATED"));
      awaitState(workflow, snapshot -> snapshot.nextEventSequence() == 7);

      IntakeWorkflowCommand third = targetCommand(THREE_ROUND_THIRD_COMMAND_ID, 3, 2);
      workflow.commandAccepted(third);
      IntakeRoomSnapshot completed =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 3
                      && snapshot.processedEventCount() == 3
                      && snapshot.pendingCommand() == null
                      && snapshot.lastGraphExecutionRef() != null
                      && third.commandId().equals(snapshot.lastGraphExecutionRef().graphCommandId()));

      assertThat(completed.nextCommandSequence()).isEqualTo(4);
      assertThat(completed.nextEventSequence()).isEqualTo(8);
      assertThat(completed.processRevision()).isEqualTo(3);
      assertThat(completed.roomRevision()).isEqualTo(3);
      assertThat(completed.protocolErrorCode()).isNull();
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(third.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(RecordingAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first), targetRequest(second), targetRequest(third));
      assertThat(RecordingFinalizationReads.requests).hasSize(3);
    }
  }

  @Test
  void tenTargetAgentRunChildrenConsumeTheCompleteFormalProjectionAndRoomTimeline() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-ten-round-global-cursor";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);
      RecordingFinalizationReads finalizationReads = new RecordingFinalizationReads();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(finalizationReads);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:ten-round-cursor:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      List<IntakeWorkflowCommand> commands = new CopyOnWriteArrayList<>();
      for (int round = 1; round <= 10; round++) {
        int expectedRound = round;
        long formalSequence = 1L + (round - 1L) * 3L;
        IntakeWorkflowCommand command =
            targetCommand(TEN_ROUND_COMMAND_PREFIX + round, round, round - 1L);
        commands.add(command);
        workflow.commandAccepted(command);

        IntakeRoomSnapshot formalized =
            awaitState(
                workflow,
                snapshot ->
                    snapshot.processedCommandCount() == expectedRound
                        && snapshot.processedEventCount() == expectedRound
                        && snapshot.pendingCommand() == null
                        && snapshot.activityExecution() == null
                        && snapshot.lastGraphExecutionRef() != null
                        && command
                            .commandId()
                            .equals(snapshot.lastGraphExecutionRef().graphCommandId()));

        assertThat(formalized.nextCommandSequence()).isEqualTo(round + 1L);
        assertThat(formalized.nextEventSequence()).isEqualTo(formalSequence + 1L);
        assertThat(formalized.processRevision()).isEqualTo(round);
        assertThat(formalized.roomRevision()).isEqualTo(round);
        assertThat(formalized.protocolErrorCode()).isNull();
        assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(command.commandId());
        assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");

        workflow.targetSourceEventObserved(
            cursor(
                formalSequence + 1L,
                "EVENT_P9_TEN_ROUND_READY_" + round,
                "INTAKE_PROJECTION_READY"));
        awaitState(workflow, snapshot -> snapshot.nextEventSequence() == formalSequence + 2L);

        if (round < 10) {
          workflow.targetSourceEventObserved(
              cursor(
                  formalSequence + 2L,
                  "EVENT_P9_TEN_ROUND_ROOM_" + (round + 1),
                  "ROOM_MESSAGE_CREATED"));
          awaitState(workflow, snapshot -> snapshot.nextEventSequence() == formalSequence + 3L);
        }
      }

      IntakeRoomSnapshot completed = workflow.state();
      assertThat(completed.nextCommandSequence()).isEqualTo(11);
      assertThat(completed.nextEventSequence()).isEqualTo(30);
      assertThat(completed.processedCommandCount()).isEqualTo(10);
      assertThat(completed.processedEventCount()).isEqualTo(10);
      assertThat(completed.pendingCommand()).isNull();
      assertThat(completed.activityExecution()).isNull();
      assertThat(completed.processRevision()).isEqualTo(10);
      assertThat(completed.roomRevision()).isEqualTo(10);
      assertThat(completed.protocolErrorCode()).isNull();
      assertThat(RecordingAgentRunWorkflow.requests)
          .containsExactlyElementsOf(commands.stream().map(IntakeRoomAgentRunChildWorkflowTest::targetRequest).toList());
      assertThat(
              RecordingAgentRunWorkflow.requests.stream()
                  .map(request -> request.command().commandId())
                  .distinct()
                  .toList())
          .hasSize(10);
      assertThat(RecordingFinalizationReads.requests).hasSize(10);
      assertThat(finalizationReads.committedEvents.stream()
              .map(IntakeDomainEventRef::eventSequence)
              .toList())
          .containsExactly(1L, 4L, 7L, 10L, 13L, 16L, 19L, 22L, 25L, 28L);
    }
  }

  @Test
  void retainsParentCursorsThatArriveBeforeTheChildFormalReceipt() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-future-source-cursor";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);
      BlockingFinalizationReads.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new BlockingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:future-source-cursor:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(FUTURE_CURSOR_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      assertThat(BlockingFinalizationReads.awaitStarted()).isTrue();
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));

      workflow.targetSourceEventObserved(
          cursor(2, "EVENT_P9_FUTURE_CURSOR_READY_2", "INTAKE_PROJECTION_READY"));
      workflow.targetSourceEventObserved(
          cursor(3, "EVENT_P9_FUTURE_CURSOR_ROOM_3", "ROOM_MESSAGE_CREATED"));
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot held = workflow.state();
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.processedEventCount()).isZero();
      assertThat(held.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(held.protocolErrorCode()).isEqualTo("TARGET_SOURCE_EVENT_SEQUENCE_GAP");
      assertThat(RecordingFinalizationReads.requests).isEmpty();

      BlockingFinalizationReads.release();
      IntakeRoomSnapshot drained =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.nextEventSequence() == 4
                      && snapshot.processedEventCount() == 1
                      && snapshot.pendingCommand() == null);
      assertThat(drained.protocolErrorCode()).isNull();
      assertThat(drained.processRevision()).isEqualTo(1);
      assertThat(drained.roomRevision()).isEqualTo(1);

      IntakeWorkflowCommand second = targetCommand(FUTURE_CURSOR_SECOND_COMMAND_ID, 2, 1);
      workflow.commandAccepted(second);
      IntakeRoomSnapshot completed =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.lastGraphExecutionRef() != null
                      && second.commandId().equals(snapshot.lastGraphExecutionRef().graphCommandId()));

      assertThat(completed.nextEventSequence()).isEqualTo(5);
      assertThat(completed.protocolErrorCode()).isNull();
      assertThat(RecordingAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first), targetRequest(second));
      assertThat(RecordingFinalizationReads.requests).hasSize(2);
    }
  }

  @Test
  void defersNextTargetCommandUntilBufferedCommittedFormalEventGetsSourceCursor() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-source-gap-deferred-command";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:source-gap-deferred:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(GAP_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);

      IntakeRoomSnapshot buffered =
          awaitState(
              workflow,
              snapshot ->
                  "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                      && first.commandId().equals(snapshot.pendingCommandId()));
      assertThat(buffered.nextEventSequence()).isEqualTo(1);
      assertThat(buffered.processedCommandCount()).isEqualTo(1);
      assertThat(buffered.processedEventCount()).isZero();
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests).hasSize(1);
      assertThat(RecordingFinalizationReads.requests.getFirst().mode())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);

      IntakeWorkflowCommand deferred = targetCommand(GAP_SECOND_COMMAND_ID, 2, 1);
      workflow.commandAccepted(deferred);

      // Advance the in-process Temporal server so the deferred signal is consumed before the
      // source cursor signal is delivered.
      environment.sleep(Duration.ofSeconds(1));
      IntakeRoomSnapshot held = workflow.state();
      assertThat(first.commandId()).isEqualTo(held.pendingCommandId());
      assertThat(held.nextCommandSequence()).isEqualTo(2);
      assertThat(RecordingAgentRunWorkflow.requests).hasSize(1);
      assertThat(held.processedCommandCount()).isEqualTo(1);
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());
      assertThat(RecordingFinalizationReads.requests).hasSize(1);

      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_DEFER_SOURCE_1", "INTAKE_PROJECTION_READY"));

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null
                      && RecordingAgentRunWorkflow.requests.size() == 2);
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(4);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(recovered.lastGraphExecutionRef().graphCommandId())
          .isEqualTo(deferred.commandId());
      assertThat(RecordingAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first), targetRequest(deferred));
      assertThat(RecordingFinalizationReads.requests).hasSize(2);
      assertThat(RecordingFinalizationReads.requests.getLast().mode())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
    }
  }

  @Test
  void rejectsMismatchedNextTargetAuthorityInsteadOfDeferringOrStartingAChild() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-source-gap-authority-rejection";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:source-gap-authority-rejection:" + CASE_ID)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(GAP_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      IntakeRoomSnapshot buffered =
          awaitState(
              workflow,
              snapshot ->
                  "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                      && first.commandId().equals(snapshot.pendingCommandId()));

      IntakeWorkflowCommand mismatched = targetCommand(GAP_BAD_COMMAND_ID, 2, 9);
      workflow.commandAccepted(mismatched);

      IntakeRoomSnapshot rejected =
          awaitState(
              workflow,
              snapshot ->
                  "COMMAND_TARGET_AGENT_RUN_AUTHORITY_MISMATCH"
                      .equals(snapshot.protocolErrorCode()));
      assertThat(rejected.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(rejected.nextCommandSequence()).isEqualTo(2);
      assertThat(rejected.processedCommandCount()).isEqualTo(1);
      assertThat(rejected.nextEventSequence()).isEqualTo(buffered.nextEventSequence());
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(mismatched.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");
      assertThat(workflow.lastCommandDecision().reasonCode())
          .isEqualTo("COMMAND_TARGET_AGENT_RUN_AUTHORITY_MISMATCH");
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests).hasSize(1);
    }
  }

  @Test
  void defersPinnedInitiatorConfirmationUntilBufferedReadyEventGetsSourceCursor() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-source-gap-deferred-confirmation";
      SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(1);
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      RecordingFinalizationReads finalizationReads = new RecordingFinalizationReads();
      controlWorker.registerActivitiesImplementations(
          finalizationReads, branchActivities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:source-gap-deferred-confirmation:" + CASE_ID)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(GAP_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      IntakeRoomSnapshot buffered =
          awaitState(
              workflow,
              snapshot ->
                  "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                      && first.commandId().equals(snapshot.pendingCommandId()));
      assertThat(buffered.processedCommandCount()).isEqualTo(1);
      assertThat(buffered.processedEventCount()).isZero();
      assertThat(buffered.nextEventSequence()).isEqualTo(1);
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests).hasSize(1);

      assertThat(finalizationReads.committedEvents).hasSize(1);
      IntakeDomainEventRef bufferedFormal = finalizationReads.committedEvents.getFirst();
      assertThat(bufferedFormal.eventType())
          .isEqualTo(IntakeDomainEventType.TURN_READY_TO_CONFIRM);
      IntakeWorkflowCommand confirmation =
          pinnedInitiatorConfirmation(
              GAP_CONFIRM_COMMAND_ID,
              2,
              bufferedFormal.processRevision(),
              bufferedFormal.roomRevision());
      workflow.commandAccepted(confirmation);

      // Let the workflow consume the confirmation signal before the cursor is delivered. This
      // makes the assertions below prove the command was held, rather than merely not observed.
      environment.sleep(Duration.ofSeconds(1));
      IntakeRoomSnapshot held = workflow.state();
      assertThat(held.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(held.nextCommandSequence()).isEqualTo(2);
      assertThat(held.processedCommandCount()).isEqualTo(1);
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(branchActivities.acceptRequests).isEmpty();
      assertThat(RecordingAgentRunWorkflow.requests).hasSize(1);

      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_DEFER_CONFIRM_SOURCE_1", "INTAKE_PROJECTION_READY"));

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.initiatorComplete()
                      && snapshot.respondentUnlocked()
                      && branchActivities.acceptRequests.size() == 1);
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(4);
      assertThat(recovered.roomPhase()).isEqualTo(IntakeRoomPhase.WAITING_PARTY);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(recovered.lastEventId())
          .isEqualTo("EVENT_" + confirmation.commandId().replace(':', '_'));
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(confirmation.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(branchActivities.acceptRequests.getFirst().operation())
          .isEqualTo(BranchOperation.INITIATOR_ACCEPT);
      assertThat(branchActivities.acceptRequests.getFirst().envelope().processRevision())
          .isEqualTo(1);
      assertThat(branchActivities.acceptRequests.getFirst().envelope().roomRevision())
          .isEqualTo(1);
      assertThat(branchActivities.acceptReceipts.getFirst().committedEvent().eventType())
          .isEqualTo(IntakeDomainEventType.INITIATOR_ACCEPTED);
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));
    }
  }

  @Test
  void rejectsPinnedInitiatorConfirmationWithMismatchedRevisionInsteadOfDeferring() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-source-gap-confirmation-authority-rejection";
      SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(1);
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(
          new RecordingFinalizationReads(), branchActivities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(
                          "intake-room:source-gap-confirmation-authority-rejection:" + CASE_ID)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());

      IntakeWorkflowCommand first = targetCommand(GAP_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      awaitState(
          workflow,
          snapshot ->
              "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                  && first.commandId().equals(snapshot.pendingCommandId()));

      IntakeWorkflowCommand mismatched =
          pinnedInitiatorConfirmation(GAP_BAD_CONFIRM_COMMAND_ID, 2, 9, 1);
      workflow.commandAccepted(mismatched);

      IntakeRoomSnapshot rejected =
          awaitState(
              workflow,
              snapshot -> "INTAKE_OPERATION_PENDING".equals(snapshot.protocolErrorCode()));
      assertThat(rejected.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(rejected.nextCommandSequence()).isEqualTo(2);
      assertThat(rejected.processedCommandCount()).isEqualTo(1);
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(mismatched.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");
      assertThat(workflow.lastCommandDecision().reasonCode())
          .isEqualTo("INTAKE_OPERATION_PENDING");
      assertThat(branchActivities.acceptRequests).isEmpty();
      assertThat(RecordingAgentRunWorkflow.requests).containsExactly(targetRequest(first));
    }
  }

  @Test
  void defersNextTargetCommandUntilWinningAttemptFormalEventGetsProjectionCursor() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WinningAttemptAgentRunWorkflow.reset();
      WinningAttemptFinalizationReads finalizationReads =
          new WinningAttemptFinalizationReads(WinningMismatch.NONE);
      IntakeRoomWorkflow workflow =
          startWinningWorkflow(
              environment,
              "phase9-intake-winning-attempt-deferred-command",
              finalizationReads,
              null);

      IntakeWorkflowCommand first = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);

      IntakeRoomSnapshot buffered =
          awaitState(
              workflow,
              snapshot ->
                  "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                      && first.commandId().equals(snapshot.pendingCommandId()));
      assertWinningAttemptIdentity(first, finalizationReads);
      assertThat(buffered.processedCommandCount()).isEqualTo(1);
      assertThat(buffered.processedEventCount()).isZero();
      assertThat(buffered.nextEventSequence()).isEqualTo(1);
      assertThat(WinningAttemptAgentRunWorkflow.requests).hasSize(1);
      assertThat(finalizationReads.requests).hasSize(1);

      IntakeWorkflowCommand deferred = targetCommand(WINNING_SECOND_COMMAND_ID, 2, 1);
      workflow.commandAccepted(deferred);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot held = workflow.state();
      assertThat(held.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(held.nextCommandSequence()).isEqualTo(2);
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.processedCommandCount()).isEqualTo(1);
      assertThat(held.processedEventCount()).isZero();
      assertThat(held.protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
      assertThat(WinningAttemptAgentRunWorkflow.requests).hasSize(1);
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");

      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_WINNING_SOURCE_1", "INTAKE_PROJECTION_READY"));

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null
                      && WinningAttemptAgentRunWorkflow.requests.size() == 2);
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(4);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(recovered.lastGraphExecutionRef().graphCommandId())
          .isEqualTo(deferred.commandId());
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(deferred.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(finalizationReads.requests).hasSize(2);
      assertThat(finalizationReads.committedEvents).hasSize(2);
      assertThat(finalizationReads.committedEvents.getFirst().eventSequence()).isEqualTo(2);
      assertThat(finalizationReads.committedEvents.getLast().eventSequence()).isEqualTo(3);
      assertThat(WinningAttemptAgentRunWorkflow.results).hasSize(2);
      assertThat(WinningAttemptAgentRunWorkflow.results.getLast().attemptNo()).isEqualTo(1);
    }
  }

  @Test
  void defersPinnedV4ConfirmationUntilWinningAttemptFormalEventGetsProjectionCursor() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      WinningAttemptAgentRunWorkflow.reset();
      WinningAttemptFinalizationReads finalizationReads =
          new WinningAttemptFinalizationReads(WinningMismatch.NONE);
      SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(1);
      IntakeRoomWorkflow workflow =
          startWinningWorkflow(
              environment,
              "phase9-intake-winning-attempt-deferred-confirmation",
              finalizationReads,
              branchActivities);

      IntakeWorkflowCommand first = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      IntakeRoomSnapshot buffered =
          awaitState(
              workflow,
              snapshot ->
                  "EVENT_SEQUENCE_GAP".equals(snapshot.protocolErrorCode())
                      && first.commandId().equals(snapshot.pendingCommandId()));
      assertWinningAttemptIdentity(first, finalizationReads);

      IntakeDomainEventRef winningFormal = finalizationReads.committedEvents.getFirst();
      IntakeWorkflowCommand confirmation =
          pinnedInitiatorConfirmation(
              WINNING_CONFIRM_COMMAND_ID,
              2,
              winningFormal.processRevision(),
              winningFormal.roomRevision());
      workflow.commandAccepted(confirmation);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot held = workflow.state();
      assertThat(held.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(held.nextCommandSequence()).isEqualTo(2);
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.processedCommandCount()).isEqualTo(1);
      assertThat(held.processedEventCount()).isZero();
      assertThat(held.protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
      assertThat(branchActivities.acceptRequests).isEmpty();
      assertThat(WinningAttemptAgentRunWorkflow.requests).hasSize(1);
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());

      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_WINNING_CONFIRM_SOURCE_1", "INTAKE_PROJECTION_READY"));

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.processedEventCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.initiatorComplete()
                      && snapshot.respondentUnlocked()
                      && branchActivities.acceptRequests.size() == 1);
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(4);
      assertThat(recovered.roomPhase()).isEqualTo(IntakeRoomPhase.WAITING_PARTY);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(recovered.lastEventId())
          .isEqualTo("EVENT_" + confirmation.commandId().replace(':', '_'));
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(confirmation.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(branchActivities.acceptRequests).hasSize(1);
      assertThat(branchActivities.acceptRequests.getFirst().operation())
          .isEqualTo(BranchOperation.INITIATOR_ACCEPT);
      assertThat(branchActivities.acceptRequests.getFirst().envelope().processRevision())
          .isEqualTo(winningFormal.processRevision());
      assertThat(branchActivities.acceptRequests.getFirst().envelope().roomRevision())
          .isEqualTo(winningFormal.roomRevision());
      assertThat(branchActivities.acceptReceipts.getFirst().committedEvent().eventType())
          .isEqualTo(IntakeDomainEventType.INITIATOR_ACCEPTED);
      assertThat(WinningAttemptAgentRunWorkflow.requests).hasSize(1);
    }
  }

  @Test
  void rejectsWrongWinningLineageWithoutStartingAnotherChildOrBranch() {
    for (WinningMismatch mismatch : WinningMismatch.rejections()) {
      try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
        WinningAttemptAgentRunWorkflow.reset();
        WinningAttemptFinalizationReads finalizationReads =
            new WinningAttemptFinalizationReads(mismatch);
        SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(1);
        IntakeRoomWorkflow workflow =
            startWinningWorkflow(
                environment,
                "phase9-intake-winning-mismatch-" + mismatch.name().toLowerCase(),
                finalizationReads,
                branchActivities);

        IntakeWorkflowCommand first = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
        workflow.commandAccepted(first);
        awaitState(
            workflow,
            snapshot ->
                first.commandId().equals(snapshot.pendingCommandId())
                    && !finalizationReads.requests.isEmpty());
        environment.sleep(Duration.ofSeconds(1));

        IntakeWorkflowCommand next = targetCommand(WINNING_SECOND_COMMAND_ID, 2, 1);
        workflow.commandAccepted(next);
        environment.sleep(Duration.ofSeconds(1));
        workflow.targetSourceEventObserved(
            cursor(1, "EVENT_P9_WINNING_MISMATCH_SOURCE_1_" + mismatch.name(),
                "INTAKE_PROJECTION_READY"));
        environment.sleep(Duration.ofSeconds(1));

        IntakeRoomSnapshot rejected = workflow.state();
        assertThat(WinningAttemptAgentRunWorkflow.requests)
            .as("winner mismatch %s must not launch the next child", mismatch)
            .hasSize(1);
        assertThat(branchActivities.acceptRequests)
            .as("winner mismatch %s must not launch a branch", mismatch)
            .isEmpty();
        assertThat(rejected.processedCommandCount()).isEqualTo(1);
        assertThat(rejected.nextCommandSequence()).isEqualTo(2);
        if (mismatch == WinningMismatch.REVISION) {
          assertThat(rejected.pendingCommand()).isNull();
          assertThat(rejected.processRevision()).isEqualTo(2);
          assertThat(rejected.roomRevision()).isEqualTo(2);
        } else {
          assertThat(rejected.pendingCommandId()).isEqualTo(first.commandId());
        }
        assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(next.commandId());
        assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");
      }
    }
  }

  @Test
  void reconcilesAnAlreadyCommittedReceiptAfterTheAgentRunChildAcknowledgementIsLost() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-post-commit-immediate-recovery";
      FailsFirstAgentRunWorkflow.requests.clear();
      FailsFirstAgentRunWorkflow.invocations.set(0);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(FailsFirstAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:immediate-recovery:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand command = targetCommand();
      workflow.commandAccepted(command);

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot -> snapshot.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(recovered.pendingCommand()).isNull();
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(FailsFirstAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsExactly(
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
    }
  }

  @Test
  void retriesAPendingReceiptAfterTheAgentRunChildCompletes() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-pending-finalization-recovery";
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(1);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(RecordingAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:pending-finalization:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand command = targetCommand();
      workflow.commandAccepted(command);

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot -> snapshot.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(recovered.pendingCommand()).isNull();
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsExactly(
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
    }
  }

  @Test
  void reconcilesALateCommittedReceiptBeforeAcceptingTheNextTargetCommand() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-post-commit-recovery";
      FailsFirstAgentRunWorkflow.requests.clear();
      FailsFirstAgentRunWorkflow.invocations.set(0);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(POST_COMMIT_RECONCILIATION_ATTEMPTS);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(FailsFirstAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("intake-room:post-commit-recovery:" + CASE_ID + ":" + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand first = targetCommand();
      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(4));

      IntakeRoomSnapshot unresolved =
          awaitState(
              workflow,
              snapshot ->
                  "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(snapshot.protocolErrorCode()));
      assertThat(unresolved.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(unresolved.roomPhase()).isEqualTo(IntakeRoomPhase.AGENT_RUNNING);

      IntakeWorkflowCommand second = targetCommand("CMD:P9:RECOVERED:2", 2, 1);
      workflow.commandAccepted(second);

      IntakeRoomSnapshot recovered =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.lastGraphExecutionRef() != null
                      && second.commandId().equals(snapshot.lastGraphExecutionRef().graphCommandId()));
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(FailsFirstAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first), targetRequest(second));
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsOnly(
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
      assertThat(RecordingFinalizationReads.requests)
          .filteredOn(
              request ->
                  request.mode()
                      == IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION)
          .hasSize(POST_COMMIT_RECONCILIATION_ATTEMPTS + 1);
      assertThat(RecordingFinalizationReads.requests.getLast().mode())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
    }
  }

  @Test
  void reconcilesALateCommittedReceiptBeforeAcceptingConfirmation() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-post-commit-confirmation-recovery";
      FailsFirstAgentRunWorkflow.requests.clear();
      FailsFirstAgentRunWorkflow.invocations.set(0);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(POST_COMMIT_RECONCILIATION_ATTEMPTS);

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(FailsFirstAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(new RecordingFinalizationReads());
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(
                          "intake-room:post-commit-confirmation-recovery:"
                              + CASE_ID
                              + ":"
                              + EPOCH)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, start());
      IntakeWorkflowCommand first = targetCommand();
      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(4));

      IntakeRoomSnapshot unresolved =
          awaitState(
              workflow,
              snapshot ->
                  "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(snapshot.protocolErrorCode()));
      assertThat(unresolved.pendingCommandId()).isEqualTo(first.commandId());

      IntakeWorkflowCommand confirmation = initiatorConfirmation("CMD:P9:CONFIRM:2", 2);
      workflow.commandAccepted(confirmation);

      IntakeRoomSnapshot accepted =
          awaitState(
              workflow,
              snapshot ->
                  confirmation.commandId().equals(snapshot.pendingCommandId())
                      && snapshot.processedCommandCount() == 2);
      assertThat(accepted.roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(accepted.protocolErrorCode()).isNull();
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(FailsFirstAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsOnly(IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
      assertThat(RecordingFinalizationReads.requests)
          .hasSize(POST_COMMIT_RECONCILIATION_ATTEMPTS + 1);
    }
  }

  @Test
  void releasesOriginalCommandAfterNonCompletedChildAndStartsNextTargetCommand() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      LifecycleAgentRunWorkflow.reset(ExecuteAgentRunResult.Outcome.FAILED);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      String roomQueue = "phase9-intake-child-non-completed-release";
      RecordingFinalizationReads finalizationReads =
          new RecordingFinalizationReads(
              IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED);
      IntakeRoomWorkflow workflow =
          startLifecycleWorkflow(environment, roomQueue, finalizationReads);

      IntakeWorkflowCommand first = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      IntakeRoomSnapshot released =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 1
                      && snapshot.pendingCommand() == null
                      && "TARGET_AGENT_RUN_FAILED".equals(snapshot.protocolErrorCode()));
      assertThat(released.pendingCommand()).isNull();
      assertThat(released.roomPhase()).isEqualTo(IntakeRoomPhase.OPEN);
      assertThat(released.initiatorComplete()).isFalse();
      assertThat(released.respondentUnlocked()).isFalse();
      assertThat(released.protocolErrorCode()).isEqualTo("TARGET_AGENT_RUN_FAILED");
      assertThat(workflow.lastCommandDecision().reasonCode())
          .isEqualTo("TARGET_AGENT_RUN_FAILED");
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");
      assertThat(LifecycleAgentRunWorkflow.requests)
          .extracting(request -> request.command().commandId())
          .containsExactly(first.commandId());
      assertThat(RecordingFinalizationReads.requests).isEmpty();

      IntakeWorkflowCommand second = targetCommand(LIFECYCLE_SECOND_COMMAND_ID, 2, 0);
      workflow.commandAccepted(second);
      IntakeRoomSnapshot continued =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.processedCommandCount() == 2
                      && snapshot.pendingCommand() == null
                      && snapshot.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM
                      && snapshot.lastGraphExecutionRef() != null
                      && second.commandId().equals(
                          snapshot.lastGraphExecutionRef().graphCommandId()));
      assertThat(continued.pendingCommand()).isNull();
      assertThat(continued.roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(continued.processedCommandCount()).isEqualTo(2);
      assertThat(continued.protocolErrorCode()).isNull();
      assertThat(continued.lastGraphExecutionRef().graphCommandId())
          .isEqualTo(second.commandId());
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(second.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(LifecycleAgentRunWorkflow.requests)
          .extracting(request -> request.command().commandId())
          .containsExactly(first.commandId(), second.commandId());
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsExactly(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
    }
  }

  @Test
  void releasesOriginalCommandWhenFinalizationIsAbsentTerminal() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      LifecycleAgentRunWorkflow.reset(ExecuteAgentRunResult.Outcome.COMPLETED);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      String roomQueue = "phase9-intake-child-absent-terminal-release";
      RecordingFinalizationReads finalizationReads =
          new RecordingFinalizationReads(
              IntakeAgentRunFinalizationReadResult.Resolution.ABSENT_TERMINAL);
      IntakeRoomWorkflow workflow =
          startLifecycleWorkflow(environment, roomQueue, finalizationReads);

      IntakeWorkflowCommand first = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot released = workflow.state();
      assertThat(released.pendingCommand()).isNull();
      assertThat(released.roomPhase()).isEqualTo(IntakeRoomPhase.OPEN);
      assertThat(released.initiatorComplete()).isFalse();
      assertThat(released.respondentUnlocked()).isFalse();
      assertThat(released.processedCommandCount()).isEqualTo(1);
      assertThat(released.processedEventCount()).isZero();
      assertThat(released.protocolErrorCode()).isEqualTo("TARGET_AGENT_RUN_FINALIZATION_ABSENT");
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");
      assertThat(workflow.lastCommandDecision().reasonCode())
          .isEqualTo("TARGET_AGENT_RUN_FINALIZATION_ABSENT");
      assertThat(LifecycleAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsExactly(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);

      IntakeWorkflowCommand second = targetCommand(LIFECYCLE_SECOND_COMMAND_ID, 2, 0);
      workflow.commandAccepted(second);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot continued = workflow.state();
      assertThat(continued.pendingCommand()).isNull();
      assertThat(continued.roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
      assertThat(continued.processedCommandCount()).isEqualTo(2);
      assertThat(continued.protocolErrorCode()).isNull();
      assertThat(continued.lastGraphExecutionRef().graphCommandId())
          .isEqualTo(second.commandId());
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(second.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(LifecycleAgentRunWorkflow.requests)
          .extracting(request -> request.command().commandId())
          .containsExactly(first.commandId(), second.commandId());
      assertThat(RecordingFinalizationReads.requests).hasSize(2);
    }
  }

  @Test
  void defersCancellationUntilReceiptCommittedFormalGapCursorArrives() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      LifecycleAgentRunWorkflow.reset(ExecuteAgentRunResult.Outcome.COMPLETED);
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(0);

      String roomQueue = "phase9-intake-child-receipt-committed-cancel-gap";
      RecordingFinalizationReads finalizationReads =
          new RecordingFinalizationReads(
              IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED);
      SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(1);
      IntakeRoomWorkflow workflow =
          startLifecycleWorkflow(environment, roomQueue, finalizationReads, branchActivities);

      IntakeWorkflowCommand first = targetCommand(GAP_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(1));

      assertThat(finalizationReads.committedEvents).hasSize(1);
      IntakeDomainEventRef bufferedFormal = finalizationReads.committedEvents.getFirst();
      assertThat(bufferedFormal.eventSequence()).isEqualTo(2);
      assertThat(bufferedFormal.eventType()).isEqualTo(IntakeDomainEventType.TURN_READY_TO_CONFIRM);

      IntakeWorkflowCommand cancellation =
          cancellationCommand(
              "CMD:P9:LIFECYCLE:CANCEL:2",
              2,
              bufferedFormal.processRevision(),
              bufferedFormal.roomRevision());
      workflow.commandAccepted(cancellation);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot held = workflow.state();
      assertThat(held.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(held.nextCommandSequence()).isEqualTo(2);
      assertThat(held.nextEventSequence()).isEqualTo(1);
      assertThat(held.processedCommandCount()).isEqualTo(1);
      assertThat(held.processedEventCount()).isZero();
      assertThat(held.protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(first.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(LifecycleAgentRunWorkflow.requests).containsExactly(targetRequest(first));
      assertThat(branchActivities.cancelRequests).isEmpty();
      assertThat(branchActivities.acceptRequests).isEmpty();

      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_LIFECYCLE_CANCEL_SOURCE_1", "INTAKE_PROJECTION_READY"));
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot recovered = workflow.state();
      assertThat(recovered.pendingCommand()).isNull();
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(4);
      assertThat(recovered.processedCommandCount()).isEqualTo(2);
      assertThat(recovered.processedEventCount()).isEqualTo(2);
      assertThat(recovered.processRevision()).isEqualTo(2);
      assertThat(recovered.roomRevision()).isEqualTo(2);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(cancellation.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(branchActivities.acceptRequests).isEmpty();
      assertThat(branchActivities.cancelRequests).hasSize(1);
      assertThat(branchActivities.cancelRequests.getFirst().envelope().commandSequence())
          .isEqualTo(2);
      assertThat(branchActivities.cancelReceipts.getFirst().committedEvent().eventSequence())
          .isEqualTo(3);
      assertThat(branchActivities.cancelReceipts.getFirst().committedEvent().eventType())
          .isEqualTo(IntakeDomainEventType.CANCELLED);
      assertThat(LifecycleAgentRunWorkflow.requests).containsExactly(targetRequest(first));
    }
  }

  @Test
  void completesDeferredCancellationAfterAbsentTerminalReconciliation() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      BlockingLifecycleAgentRunWorkflow.reset();
      RecordingFinalizationReads.requests.clear();
      RecordingFinalizationReads.pendingResponses.set(1);

      String roomQueue = "phase9-intake-child-absent-terminal-deferred-cancel";
      RecordingFinalizationReads finalizationReads =
          new RecordingFinalizationReads(
              IntakeAgentRunFinalizationReadResult.Resolution.ABSENT_TERMINAL,
              IntakeAgentRunFinalizationReadResult.Resolution.ABSENT_TERMINAL);
      SnapshotOnlyActivities branchActivities = new SnapshotOnlyActivities(-1);
      IntakeRoomWorkflow workflow =
          startBlockingLifecycleWorkflow(
              environment, roomQueue, finalizationReads, branchActivities);

      IntakeWorkflowCommand first = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot active = workflow.state();
      assertThat(active.pendingCommandId()).isEqualTo(first.commandId());
      assertThat(active.roomPhase()).isEqualTo(IntakeRoomPhase.AGENT_RUNNING);
      assertThat(active.nextCommandSequence()).isEqualTo(2);
      assertThat(active.processedCommandCount()).isEqualTo(1);
      assertThat(BlockingLifecycleAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests).isEmpty();
      assertThat(branchActivities.cancelRequests).isEmpty();

      IntakeWorkflowCommand cancellation =
          cancellationCommand("CMD:P9:LIFECYCLE:ABSENT:CANCEL:2", 2, 0, 0);
      workflow.commandAccepted(cancellation);
      environment.sleep(Duration.ofSeconds(5));

      IntakeRoomSnapshot recovered =
          awaitState(workflow, snapshot -> snapshot.pendingCommand() == null);
      assertThat(recovered.pendingCommand()).isNull();
      assertThat(recovered.nextCommandSequence()).isEqualTo(3);
      assertThat(recovered.nextEventSequence()).isEqualTo(2);
      assertThat(recovered.processedCommandCount()).isEqualTo(2);
      assertThat(recovered.processedEventCount()).isEqualTo(1);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(cancellation.commandId());
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(branchActivities.acceptRequests).isEmpty();
      assertThat(branchActivities.cancelRequests).hasSize(1);
      assertThat(branchActivities.cancelReceipts.getFirst().committedEvent().eventSequence())
          .isEqualTo(1);
      assertThat(branchActivities.cancelReceipts.getFirst().committedEvent().eventType())
          .isEqualTo(IntakeDomainEventType.CANCELLED);
      assertThat(BlockingLifecycleAgentRunWorkflow.requests)
          .containsExactly(targetRequest(first));
      assertThat(RecordingFinalizationReads.requests)
          .extracting(IntakeAgentRunFinalizationReadRequest::mode)
          .containsExactly(
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
    }
  }

  @Test
  void versionedFinalizationReadReturnsRecoveredWinningAttemptForWorkflowValidation() {
    IntakeWorkflowCommand command = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
    IntakeAgentRunChildState childState =
        IntakeAgentRunChildState.pending(
                IntakeAgentRunChildIds.forCommand(command),
                command.executionContext().targetAgentRun())
            .resultReady(WINNING_RESULT_HASH);
    IntakeAgentRunFinalizationReadRequest legacyRequest =
        new IntakeAgentRunFinalizationReadRequest(
            "intake-agent-run-finalization-read-request.v1",
            IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
            command,
            childState);
    IntakeAgentRunFinalizationReadResult winningResult =
        WinningAttemptFinalizationReads.finalizationResult(
            legacyRequest, WinningMismatch.NONE, true);
    IntakeAgentRunFinalizationReadActivitiesAdapter adapter =
        new IntakeAgentRunFinalizationReadActivitiesAdapter(ignored -> winningResult);
    IntakeAgentRunFinalizationReadRequest versionedRequest =
        new IntakeAgentRunFinalizationReadRequest(
            "intake-agent-run-finalization-read-request.v2",
            IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
            command,
            childState);

    assertThat(adapter.readFinalization(versionedRequest)).isSameAs(winningResult);
  }

  @Test
  void legacyFinalizationReadRejectsRecoveredWinningAttempt() {
    IntakeWorkflowCommand command = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
    IntakeAgentRunChildState childState =
        IntakeAgentRunChildState.pending(
                IntakeAgentRunChildIds.forCommand(command),
                command.executionContext().targetAgentRun())
            .resultReady(WINNING_RESULT_HASH);
    IntakeAgentRunFinalizationReadRequest legacyRequest =
        new IntakeAgentRunFinalizationReadRequest(
            "intake-agent-run-finalization-read-request.v1",
            IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
            command,
            childState);
    IntakeAgentRunFinalizationReadResult winningResult =
        WinningAttemptFinalizationReads.finalizationResult(
            legacyRequest, WinningMismatch.NONE, true);
    IntakeAgentRunFinalizationReadActivitiesAdapter adapter =
        new IntakeAgentRunFinalizationReadActivitiesAdapter(ignored -> winningResult);

    assertThatThrownBy(() -> adapter.readFinalization(legacyRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "committed AgentRun finalization receipt does not match its exact lookup");
  }

  @Test
  void exposesAcknowledgedProviderFreeTargetFinalizationRecoveryUpdateContract() {
    Method update =
        Arrays.stream(IntakeRoomWorkflow.class.getMethods())
            .filter(method -> method.getName().equals("recoverTargetFinalization"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Intake Room workflow must expose provider-free finalization recovery"));

    assertThat(update.getParameterTypes())
        .extracting(Class::getName)
        .containsExactly(
            "com.example.dispute.workflow.temporal.room.intake."
                + "IntakeAgentRunFinalizationRecoveryRequest");
    assertThat(update.getReturnType().getName())
        .isEqualTo(
            "com.example.dispute.workflow.temporal.room.intake."
                + "IntakeAgentRunFinalizationRecoveryResult");
    assertThat(update.getAnnotation(UpdateMethod.class)).isNotNull();
    assertThat(update.getAnnotation(UpdateMethod.class).name())
        .isEqualTo("intakeRecoverTargetFinalization");
  }

  @Test
  void abortedGraphStreamPublishesOneTerminalNoCommitReceiptAndAdvancesReservedCoordinates() {
    assertLegacyTerminalNoCommitMarkerPath();
    assertV2TerminalNoCommitMarkerPath();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-terminal-no-commit-future";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      AbortedAgentRunWorkflow.reset();
      AcknowledgedTerminalNoCommitActivities.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(AbortedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(
          new AcknowledgedTerminalNoCommitActivities());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String roomRunId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      IntakeWorkflowCommand command = targetCommand();

      workflow.commandAccepted(command);

      IntakeRoomSnapshot state =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() == null
                      && snapshot.processRevision() == 1
                      && snapshot.roomRevision() == 1);
      List<TargetIntakeCommandTerminalNoCommit> receipts = awaitReceipts(sink, 1);
      TargetIntakeCommandTerminalNoCommit receipt = receipts.getFirst();
      assertThat(state.protocolErrorCode()).isEqualTo("GRAPH_STREAM_PROTOCOL_REJECTED");
      assertThat(receipt.commandId()).isEqualTo(command.commandId());
      assertThat(receipt.commandRequestHash()).isEqualTo(command.requestHash());
      assertThat(receipt.logicalRunId()).isEqualTo(LOGICAL_RUN_ID);
      assertThat(receipt.terminalAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
      assertThat(receipt.errorCode()).isEqualTo("GRAPH_STREAM_PROTOCOL_REJECTED");
      assertThat(receipt.roomWorkflowRunId()).isEqualTo(roomRunId);
      assertThat(receipt.expectedProcessRevision()).isZero();
      assertThat(receipt.newProcessRevision()).isEqualTo(1);
      assertThat(receipt.expectedRoomRevision()).isZero();
      assertThat(receipt.newRoomRevision()).isEqualTo(1);
      assertThat(receipt.expectedLastCaseEventSequence()).isZero();
      assertThat(receipt.lastCaseEventSequence()).isZero();
      assertThat(receipt.schemaVersion())
          .isEqualTo(TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION);
      assertThat(receipt.expectedProjectionLastCaseEventSequence()).isZero();
      assertThat(receipt.newProjectionLastCaseEventSequence()).isZero();
      assertThat(receipt.interveningCaseEvents()).isEmpty();
      assertThat(receipt.agentRunExecutionRequestHash())
          .isEqualTo(targetRequest(command).command().requestHash());
      assertThatThrownBy(
              () ->
                  new IntakeTerminalNoCommitRecoveryRequest(
                      IntakeTerminalNoCommitRecoveryRequest.LEGACY_SCHEMA_VERSION,
                      roomWorkflowId,
                      roomRunId,
                      receipt))
          .isInstanceOf(IllegalArgumentException.class);

      workflow.commandAccepted(command);
      environment.sleep(Duration.ofSeconds(1));
      assertThat(sink.receipts()).containsExactly(receipt);
      assertThat(AbortedAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      assertThat(AcknowledgedTerminalNoCommitActivities.resolves).hasValue(1);
      assertThat(AcknowledgedTerminalNoCommitActivities.convergences).hasValue(1);

      IntakeWorkflowCommand subsequent = targetCommand("CMD:P9:TERMINAL:NEXT:2", 2, 1);
      workflow.commandAccepted(subsequent);
      IntakeRoomSnapshot afterSubsequent =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() == null
                      && snapshot.processRevision() == 2
                      && snapshot.roomRevision() == 2);
      List<TargetIntakeCommandTerminalNoCommit> afterSubsequentReceipts =
          awaitReceipts(sink, 2);
      assertThat(afterSubsequent.protocolErrorCode())
          .isEqualTo("GRAPH_STREAM_PROTOCOL_REJECTED");
      assertThat(afterSubsequentReceipts)
          .extracting(TargetIntakeCommandTerminalNoCommit::commandId)
          .containsExactly(command.commandId(), subsequent.commandId());
      assertThat(AbortedAgentRunWorkflow.requests)
          .containsExactly(targetRequest(command), targetRequest(subsequent));
      assertThat(AcknowledgedTerminalNoCommitActivities.resolves).hasValue(2);
      assertThat(AcknowledgedTerminalNoCommitActivities.convergences).hasValue(2);

      workflow.commandAccepted(subsequent);
      environment.sleep(Duration.ofSeconds(1));
      assertThat(sink.receipts()).hasSize(2);
      assertThat(AbortedAgentRunWorkflow.requests).hasSize(2);
      assertThat(AcknowledgedTerminalNoCommitActivities.resolves).hasValue(2);
      assertThat(AcknowledgedTerminalNoCommitActivities.convergences).hasValue(2);
    }
  }

  @Test
  void parentActivityFailureCannotCacheSuccessfulRoomAcknowledgement() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-terminal-no-commit-parent-reject";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      AbortedAgentRunWorkflow.reset();
      AcknowledgedTerminalNoCommitActivities.reset();
      AcknowledgedTerminalNoCommitActivities.rejectConvergence();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(AbortedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(
          new AcknowledgedTerminalNoCommitActivities());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());
      IntakeWorkflowCommand command = targetCommand();

      workflow.commandAccepted(command);
      IntakeRoomSnapshot blocked =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() != null
                      && AcknowledgedTerminalNoCommitActivities.convergences.get() == 1);

      assertThat(blocked.pendingCommand().commandId()).isEqualTo(command.commandId());
      assertThat(blocked.processRevision()).isZero();
      assertThat(blocked.roomRevision()).isZero();
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(sink.receipts()).isEmpty();
      assertThat(AcknowledgedTerminalNoCommitActivities.resolves).hasValue(1);
      assertThat(AcknowledgedTerminalNoCommitActivities.convergences).hasValue(1);
      assertThat(AbortedAgentRunWorkflow.requests).containsExactly(targetRequest(command));
    }
  }

  @Test
  void completedPublicResultRejectedDuringFinalizationConvergesTerminalNoCommitAndAdmitsNextCommandOnce() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-finalization-rejected-terminal-no-commit";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      FinalizationRejectedThenCompletedAgentRunWorkflow.reset();
      FinalizationRejectedReads.reset();
      RecordingFinalizationReads.requests.clear();
      AcknowledgedTerminalNoCommitActivities.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(
          FinalizationRejectedThenCompletedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(new FinalizationRejectedReads());
      controlWorker.registerActivitiesImplementations(
          new AcknowledgedTerminalNoCommitActivities());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());
      IntakeWorkflowCommand first = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);

      workflow.commandAccepted(first);

      IntakeRoomSnapshot released =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() == null
                      && snapshot.processRevision() == 1
                      && snapshot.roomRevision() == 1);
      TargetIntakeCommandTerminalNoCommit receipt = awaitReceipts(sink, 1).getFirst();
      assertThat(released.roomPhase()).isEqualTo(IntakeRoomPhase.OPEN);
      assertThat(released.processedEventCount()).isZero();
      assertThat(released.protocolErrorCode())
          .isEqualTo("INTAKE_RESPONDENT_MATRIX_NOT_READY");
      assertThat(receipt.errorCode()).isEqualTo("INTAKE_RESPONDENT_MATRIX_NOT_READY");
      assertThat(receipt.terminalAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
      assertThat(receipt.lastSequenceNo()).isEqualTo(2);
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("REJECTED");

      workflow.commandAccepted(first);
      environment.sleep(Duration.ofSeconds(1));
      assertThat(sink.receipts()).containsExactly(receipt);
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests).hasSize(1);
      assertThat(FinalizationRejectedReads.requests).hasSize(1);

      IntakeWorkflowCommand second = targetCommand(LIFECYCLE_SECOND_COMMAND_ID, 2, 1);
      workflow.commandAccepted(second);
      IntakeRoomSnapshot continued =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() == null
                      && snapshot.processRevision() == 2
                      && snapshot.roomRevision() == 2
                      && snapshot.lastGraphExecutionRef() != null
                      && second.commandId().equals(
                          snapshot.lastGraphExecutionRef().graphCommandId()));
      assertThat(continued.protocolErrorCode()).isNull();
      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests)
          .extracting(request -> request.command().commandId())
          .containsExactly(first.commandId(), second.commandId());
      assertThat(sink.receipts()).containsExactly(receipt);

      workflow.commandAccepted(second);
      environment.sleep(Duration.ofSeconds(1));
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests).hasSize(2);
      assertThat(RecordingFinalizationReads.requests).hasSize(1);
    }
  }

  @Test
  void pendingFinalizationRejectedRecoveryUpdateConvergesAndCachesAcknowledgedResult() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-pending-finalization-recovery-update";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      FinalizationRejectedThenCompletedAgentRunWorkflow.reset();
      PendingFinalizationRejectedReads.reset();
      AcknowledgedTerminalNoCommitActivities.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(
          FinalizationRejectedThenCompletedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(new PendingFinalizationRejectedReads());
      controlWorker.registerActivitiesImplementations(
          new AcknowledgedTerminalNoCommitActivities());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String roomRunId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      IntakeWorkflowCommand command = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);

      workflow.commandAccepted(command);
      environment.sleep(Duration.ofSeconds(4));
      IntakeRoomSnapshot stranded =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() != null
                      && command.commandId().equals(snapshot.pendingCommand().commandId())
                      && "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(snapshot.protocolErrorCode()));
      int readsBeforeUpdate = PendingFinalizationRejectedReads.requests.size();
      assertThat(readsBeforeUpdate).isPositive();
      assertThat(sink.receipts()).isEmpty();

      IntakeAgentRunChildState pendingChild =
          IntakeAgentRunChildState.pending(
              IntakeAgentRunChildIds.forCommand(command),
              command.executionContext().targetAgentRun());
      IntakeAgentRunFinalizationReadRequest readRequest =
          IntakeAgentRunFinalizationReadRequest.winningAttempt(
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
              command,
              pendingChild);
      IntakeAgentRunFinalizationReadResult expected =
          FinalizationRejectedReads.terminalNoCommitResult(readRequest);
      long sourceLastCaseEventSequence = stranded.nextEventSequence() - 1;
      IntakeAgentRunFinalizationRecoveryRequest request =
          new IntakeAgentRunFinalizationRecoveryRequest(
              IntakeAgentRunFinalizationRecoveryRequest.V2_SCHEMA_VERSION,
              roomWorkflowId,
              roomRunId,
              TENANT,
              CASE_ID,
              EPOCH,
              FENCE,
              command,
              pendingChild,
              expected,
              stranded.processRevision(),
              stranded.roomRevision(),
              sourceLastCaseEventSequence);

      PendingFinalizationRejectedReads.enableTerminalEvidence();
      IntakeAgentRunFinalizationRecoveryResult recovered =
          workflow.recoverTargetFinalization(request);
      TargetIntakeCommandTerminalNoCommit acknowledgedV3 = awaitReceipts(sink, 1).getFirst();

      assertThat(recovered.schemaVersion())
          .isEqualTo(IntakeAgentRunFinalizationRecoveryResult.V2_SCHEMA_VERSION);
      assertThat(recovered.request()).isEqualTo(request);
      assertThat(recovered.finalization()).isEqualTo(expected);
      assertThat(recovered.adoptedChildState()).isEqualTo(request.terminalNoCommitChildState());
      assertThat(recovered.disposition())
          .isEqualTo(
              IntakeAgentRunFinalizationRecoveryResult.Disposition
                  .TERMINAL_NO_COMMIT_CONVERGED);
      assertThat(recovered.terminalNoCommitAuthority().errorCode())
          .isEqualTo("INTAKE_RESPONDENT_MATRIX_NOT_READY");
      assertThat(recovered.terminalNoCommitAuthority().expectedLastCaseEventSequence())
          .isEqualTo(sourceLastCaseEventSequence);
      assertThat(recovered.terminalNoCommitAuthority())
          .isEqualTo(acknowledgedV3.asObservedV2Authority());
      assertThat(PendingFinalizationRejectedReads.requests)
          .hasSize(readsBeforeUpdate + 1);
      assertThat(sink.receipts()).containsExactly(acknowledgedV3);
      IntakeRoomSnapshot released = workflow.state();
      assertThat(released.pendingCommand()).isNull();
      assertThat(released.processRevision()).isEqualTo(stranded.processRevision() + 1);
      assertThat(released.roomRevision()).isEqualTo(stranded.roomRevision() + 1);
      assertThat(released.nextEventSequence()).isEqualTo(stranded.nextEventSequence());

      IntakeAgentRunFinalizationRecoveryResult replay =
          workflow.recoverTargetFinalization(request);
      assertThat(replay).isEqualTo(recovered);
      assertThat(PendingFinalizationRejectedReads.requests)
          .hasSize(readsBeforeUpdate + 1);
      assertThat(sink.receipts()).containsExactly(acknowledgedV3);

      IntakeAgentRunFinalizationRecoveryRequest changed =
          new IntakeAgentRunFinalizationRecoveryRequest(
              IntakeAgentRunFinalizationRecoveryRequest.V2_SCHEMA_VERSION,
              roomWorkflowId,
              roomRunId,
              TENANT,
              CASE_ID,
              EPOCH,
              FENCE,
              command,
              pendingChild,
              expected,
              stranded.processRevision(),
              stranded.roomRevision(),
              sourceLastCaseEventSequence + 1);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(changed))
          .isInstanceOf(RuntimeException.class);
      assertThat(PendingFinalizationRejectedReads.requests)
          .hasSize(readsBeforeUpdate + 1);
      assertThat(sink.receipts()).containsExactly(acknowledgedV3);

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      IntakeRoomStart continuedStart = currentIntakeRoomStart(environment, roomWorkflowId);
      IntakeRoomCarryState continuedCarry = continuedStart.carryState();
      assertThat(continuedCarry).isNotNull();
      assertThat(continuedCarry.schemaVersion()).isEqualTo("intake-room-carry-state.v6");
      assertThat(continuedCarry.completedTerminalNoCommitRecovery().schemaVersion())
          .isEqualTo(IntakeTerminalNoCommitRecoveryResult.V2_SCHEMA_VERSION);
      assertThat(
              continuedCarry
                  .completedTerminalNoCommitRecovery()
                  .resolvedAuthority()
                  .authority())
          .isEqualTo(acknowledgedV3);
      assertThat(continuedCarry.completedTargetFinalizationRecoveryRequest())
          .isEqualTo(request);
      assertThat(continuedCarry.completedTargetFinalizationRecoveryResult())
          .isEqualTo(recovered);
      assertThat(PendingFinalizationRejectedReads.requests)
          .hasSize(readsBeforeUpdate + 1);
      assertThat(sink.receipts()).containsExactly(acknowledgedV3);
      assertThat(workflow.recoverTargetFinalization(request)).isEqualTo(recovered);
      assertThat(PendingFinalizationRejectedReads.requests)
          .hasSize(readsBeforeUpdate + 1);
      assertThat(sink.receipts()).containsExactly(acknowledgedV3);
    }
  }

  @Test
  void historicalRejectedCommandRecoveryReadsOnceEmitsOnceAndSurvivesContinueAsNew() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-terminal-no-commit-history";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      RecoveryAuthorityReads.reset();
      IntakeWorkflowCommand command = targetCommand();
      IntakeRoomCarryState carry =
          historicalTerminalNoCommitCarry(command, "TARGET_AGENT_RUN_FAILED");

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(
          LegacyTerminalNoCommitBootstrapWorkflowImpl.class, IntakeRoomWorkflowImpl.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(new RecoveryAuthorityReads());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      LegacyTerminalNoCommitBootstrapWorkflow bootstrap =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  LegacyTerminalNoCommitBootstrapWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String firstRunId =
          WorkflowClient.start(bootstrap::run, targetStart().withCarryState(carry)).getRunId();
      IntakeRoomWorkflow workflow =
          environment.getWorkflowClient().newWorkflowStub(IntakeRoomWorkflow.class, roomWorkflowId);
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      String currentRunId = currentRunId(environment, roomWorkflowId);
      TargetIntakeCommandTerminalNoCommit authority =
          terminalRecoveryAuthority(
              command,
              roomWorkflowId,
              firstRunId,
              "GRAPH_STREAM_PROTOCOL_REJECTED");
      assertThat(authority.terminalAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
      assertThat(authority.agentRunOutcome()).isEqualTo(ExecuteAgentRunResult.Outcome.FAILED);
      assertThat(carry.observedCommands())
          .singleElement()
          .extracting(observed -> observed.decision().reasonCode())
          .isEqualTo("TARGET_AGENT_RUN_FAILED");
      RecoveryAuthorityReads.expected = authority;
      IntakeTerminalNoCommitRecoveryRequest request =
          new IntakeTerminalNoCommitRecoveryRequest(
              IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION,
              roomWorkflowId,
              currentRunId,
              authority);
      IntakeTerminalNoCommitRecoveryRequest drifted =
          new IntakeTerminalNoCommitRecoveryRequest(
              request.schemaVersion(),
              request.workflowId(),
              request.workflowRunId(),
              terminalRecoveryAuthority(
                  command, roomWorkflowId, firstRunId, "OTHER_TERMINAL_ERROR"));
      TargetIntakeCommandTerminalNoCommit legacyAuthority =
          legacyTerminalRecoveryAuthority(
              command,
              roomWorkflowId,
              firstRunId,
              "GRAPH_STREAM_PROTOCOL_REJECTED");

      assertThat(authority.schemaVersion())
          .isEqualTo("target-intake-command-terminal-no-commit.v2");
      assertThat(authority.agentRunExecutionRequestHash())
          .isEqualTo(command.executionContext().targetAgentRun().request().command().requestHash());
      assertThat(authority.agentRunExecutionRequestHash())
          .isNotEqualTo(authority.commandEnvelopeHash());
      assertThat(authority.expectedLastCaseEventSequence()).isEqualTo(13);
      assertThat(authority.lastCaseEventSequence()).isEqualTo(15);
      assertThatThrownBy(
              () ->
                  new IntakeTerminalNoCommitRecoveryRequest(
                      request.schemaVersion(),
                      request.workflowId(),
                      request.workflowRunId(),
                      legacyAuthority))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(RecoveryAuthorityReads.invocations.get()).isZero();
      assertThat(sink.receipts()).isEmpty();
      RecoveryAuthorityReads.rejectNext();
      IntakeRoomSnapshot beforeRejectedResolve = workflow.state();
      assertThatThrownBy(() -> workflow.recoverTerminalNoCommit(request))
          .isInstanceOf(RuntimeException.class);
      IntakeRoomSnapshot afterRejectedResolve = workflow.state();
      assertThat(afterRejectedResolve.processRevision())
          .isEqualTo(beforeRejectedResolve.processRevision());
      assertThat(afterRejectedResolve.roomRevision())
          .isEqualTo(beforeRejectedResolve.roomRevision());
      assertThat(afterRejectedResolve.nextEventSequence())
          .isEqualTo(beforeRejectedResolve.nextEventSequence());
      assertThat(afterRejectedResolve.protocolErrorCode())
          .isEqualTo(beforeRejectedResolve.protocolErrorCode());
      assertThat(RecoveryAuthorityReads.invocations.get()).isEqualTo(1);
      assertThat(sink.receipts()).isEmpty();

      IntakeTerminalNoCommitRecoveryResult first = workflow.recoverTerminalNoCommit(request);
      IntakeTerminalNoCommitRecoveryResult replay = workflow.recoverTerminalNoCommit(request);

      assertThat(replay).isEqualTo(first);
      assertThat(first.disposition())
          .isEqualTo(IntakeTerminalNoCommitRecoveryResult.Disposition.EMITTED);
      assertThat(RecoveryAuthorityReads.invocations.get()).isEqualTo(2);
      assertThat(awaitReceipts(sink, 1)).containsExactly(authority);
      IntakeRoomSnapshot recovered = workflow.state();
      assertThat(recovered.processRevision()).isEqualTo(1);
      assertThat(recovered.roomRevision()).isEqualTo(1);
      assertThat(recovered.protocolErrorCode()).isNull();

      assertThatThrownBy(() -> workflow.recoverTerminalNoCommit(drifted))
          .isInstanceOf(RuntimeException.class);
      assertThat(RecoveryAuthorityReads.invocations.get()).isEqualTo(2);
      assertThat(sink.receipts()).containsExactly(authority);

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 2);
      assertThat(workflow.recoverTerminalNoCommit(request)).isEqualTo(first);
      assertThat(RecoveryAuthorityReads.invocations.get()).isEqualTo(2);
      assertThat(sink.receipts()).containsExactly(authority);
    }
  }

  @Test
  void historicalRejectedCommandAcknowledgedRecoveryConvergesAndCachesAcrossContinueAsNew() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-terminal-no-commit-acknowledged-history";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      RecoveryAuthorityReads.reset();
      IntakeWorkflowCommand command = targetCommand();
      IntakeRoomCarryState carry =
          historicalTerminalNoCommitCarryWithLineage(command, "TARGET_AGENT_RUN_FAILED");

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(
          LegacyTerminalNoCommitBootstrapWorkflowImpl.class, IntakeRoomWorkflowImpl.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(new RecoveryAuthorityReads());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      LegacyTerminalNoCommitBootstrapWorkflow bootstrap =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  LegacyTerminalNoCommitBootstrapWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String firstRunId =
          WorkflowClient.start(bootstrap::run, targetStart().withCarryState(carry)).getRunId();
      IntakeRoomWorkflow workflow =
          environment.getWorkflowClient().newWorkflowStub(IntakeRoomWorkflow.class, roomWorkflowId);
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      String currentRunId = currentRunId(environment, roomWorkflowId);
      TargetIntakeCommandTerminalNoCommit authority =
          terminalRecoveryAuthority(
              command,
              roomWorkflowId,
              firstRunId,
              "GRAPH_STREAM_PROTOCOL_REJECTED");
      RecoveryAuthorityReads.expected = authority;
      IntakeTerminalNoCommitRecoveryRequest unacknowledgedRequest =
          new IntakeTerminalNoCommitRecoveryRequest(
              IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION,
              roomWorkflowId,
              currentRunId,
              authority);
      IntakeTerminalNoCommitRecoveryResult unacknowledged =
          workflow.recoverTerminalNoCommit(unacknowledgedRequest);
      assertThat(unacknowledged.schemaVersion())
          .isEqualTo(IntakeTerminalNoCommitRecoveryResult.SCHEMA_VERSION);
      assertThat(RecoveryAuthorityReads.invocations).hasValue(1);
      assertThat(RecoveryAuthorityReads.convergences).hasValue(0);
      assertThat(awaitReceipts(sink, 1)).containsExactly(authority);

      IntakeTerminalNoCommitRecoveryRequest acknowledgedRequest =
          new IntakeTerminalNoCommitRecoveryRequest(
              IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION,
              roomWorkflowId,
              currentRunId,
              authority);
      IntakeTerminalNoCommitRecoveryResult acknowledged =
          workflow.recoverTerminalNoCommit(acknowledgedRequest);
      TargetIntakeCommandTerminalNoCommit strictV3 =
          acknowledged.resolvedAuthority().authority();
      assertThat(acknowledged.schemaVersion())
          .isEqualTo(IntakeTerminalNoCommitRecoveryResult.V2_SCHEMA_VERSION);
      assertThat(acknowledged.disposition())
          .isEqualTo(IntakeTerminalNoCommitRecoveryResult.Disposition.PARENT_CONVERGED);
      assertThat(strictV3.asObservedV2Authority()).isEqualTo(authority);
      assertThat(strictV3.expectedProjectionLastCaseEventSequence()).isEqualTo(13);
      assertThat(strictV3.expectedLastCaseEventSequence()).isEqualTo(13);
      assertThat(strictV3.newProjectionLastCaseEventSequence()).isEqualTo(15);
      assertThat(strictV3.interveningCaseEvents())
          .extracting(TargetIntakeSourceEventRef::eventSequence)
          .containsExactly(14L, 15L);
      assertThat(RecoveryAuthorityReads.invocations).hasValue(2);
      assertThat(RecoveryAuthorityReads.convergences).hasValue(1);
      assertThat(awaitReceipts(sink, 1)).containsExactly(strictV3);

      assertThat(workflow.recoverTerminalNoCommit(acknowledgedRequest))
          .isEqualTo(acknowledged);
      assertThat(RecoveryAuthorityReads.invocations).hasValue(2);
      assertThat(RecoveryAuthorityReads.convergences).hasValue(1);
      assertThat(sink.receipts()).containsExactly(strictV3);

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 2);
      IntakeRoomCarryState continued = currentIntakeRoomStart(environment, roomWorkflowId).carryState();
      assertThat(continued.schemaVersion()).isEqualTo("intake-room-carry-state.v6");
      assertThat(continued.completedTerminalNoCommitRecovery()).isEqualTo(acknowledged);
      var converter = environment.getWorkflowClient().getOptions().getDataConverter();
      var carryPayload = converter.toPayload(continued).orElseThrow();
      String alteredCarryJson =
          carryPayload
              .getData()
              .toStringUtf8()
              .replaceFirst(strictV3.receiptSha256(), "0".repeat(64));
      var alteredCarryPayload =
          carryPayload.toBuilder().setData(ByteString.copyFromUtf8(alteredCarryJson)).build();
      assertThatThrownBy(
              () ->
                  converter.fromPayload(
                      alteredCarryPayload,
                      IntakeRoomCarryState.class,
                      IntakeRoomCarryState.class))
          .isInstanceOf(RuntimeException.class);
      assertThat(workflow.recoverTerminalNoCommit(acknowledgedRequest))
          .isEqualTo(acknowledged);
      assertThat(RecoveryAuthorityReads.invocations).hasValue(2);
      assertThat(RecoveryAuthorityReads.convergences).hasValue(1);
      assertThat(sink.receipts()).containsExactly(strictV3);
    }
  }

  @Test
  void historicalRecoveryRejectsForeignRetainedReasonBeforeAuthorityRead() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-terminal-no-commit-foreign-reason";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      TerminalReceiptSinkWorkflowImpl.reset();
      RecoveryAuthorityReads.reset();
      IntakeWorkflowCommand command = targetCommand();
      IntakeRoomCarryState carry =
          historicalTerminalNoCommitCarry(command, "TARGET_AGENT_RUN_FOREIGN");

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(
          LegacyTerminalNoCommitBootstrapWorkflowImpl.class, IntakeRoomWorkflowImpl.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      controlWorker.registerActivitiesImplementations(new RecoveryAuthorityReads());
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      LegacyTerminalNoCommitBootstrapWorkflow bootstrap =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  LegacyTerminalNoCommitBootstrapWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String firstRunId =
          WorkflowClient.start(bootstrap::run, targetStart().withCarryState(carry)).getRunId();
      IntakeRoomWorkflow workflow =
          environment.getWorkflowClient().newWorkflowStub(IntakeRoomWorkflow.class, roomWorkflowId);
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      TargetIntakeCommandTerminalNoCommit authority =
          terminalRecoveryAuthority(
              command,
              roomWorkflowId,
              firstRunId,
              "GRAPH_STREAM_PROTOCOL_REJECTED");
      RecoveryAuthorityReads.expected = authority;
      IntakeTerminalNoCommitRecoveryRequest request =
          new IntakeTerminalNoCommitRecoveryRequest(
              IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION,
              roomWorkflowId,
              currentRunId(environment, roomWorkflowId),
              authority);

      assertThatThrownBy(() -> workflow.recoverTerminalNoCommit(request))
          .isInstanceOf(RuntimeException.class);
      assertThat(RecoveryAuthorityReads.invocations.get()).isZero();
      assertThat(sink.receipts()).isEmpty();
    }
  }

  @Test
  void providerFreeRecoveryAdoptsExpiredWinningReceiptAndReplaysWithoutSideEffects() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-provider-free-finalization-recovery";
      String workflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      WinningAttemptAgentRunWorkflow.reset();
      RecoverableWinningAttemptFinalizationReads finalizationReads =
          new RecoverableWinningAttemptFinalizationReads();
      SnapshotOnlyActivities providerActivities = new SnapshotOnlyActivities();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(WinningAttemptAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(finalizationReads, providerActivities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(workflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String runId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_RECOVERY_SOURCE_1", "INTAKE_PROJECTION_READY"));
      awaitState(workflow, snapshot -> snapshot.nextEventSequence() == 2);

      IntakeWorkflowCommand command = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(command);
      environment.sleep(Duration.ofSeconds(4));
      IntakeRoomSnapshot unresolved =
          awaitState(
              workflow,
              snapshot ->
                  "TARGET_AGENT_RUN_FINALIZATION_UNRESOLVED"
                          .equals(snapshot.protocolErrorCode())
                      && finalizationReads.requests.size()
                          == POST_COMMIT_RECONCILIATION_ATTEMPTS + 1);
      assertThat(unresolved.pendingCommandId()).isEqualTo(command.commandId());
      assertThat(WinningAttemptAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      assertThat(WinningAttemptAgentRunWorkflow.results.getFirst().attemptId())
          .isEqualTo(WINNING_ATTEMPT_ID);
      assertThat(finalizationReads.requests)
          .hasSize(POST_COMMIT_RECONCILIATION_ATTEMPTS + 1);

      IntakeAgentRunFinalizationRecoveryRequest request =
          recoveryRequest(workflowId, runId, command);
      IntakeAgentRunFinalizationRecoveryRequest wrongRun =
          new IntakeAgentRunFinalizationRecoveryRequest(
              request.schemaVersion(),
              request.roomWorkflowId(),
              runId + "-foreign",
              request.tenantSurrogate(),
              request.caseId(),
              request.roomEpoch(),
              request.fencingToken(),
              request.pendingCommand(),
              request.childState(),
              request.expectedFinalization());
      IntakeWorkflowCommand foreignCommand =
          targetCommand(WINNING_SECOND_COMMAND_ID, 1, 0);
      IntakeAgentRunFinalizationRecoveryRequest wrongCommand =
          recoveryRequest(workflowId, runId, foreignCommand);

      finalizationReads.requests.clear();
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongRun))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongCommand))
          .isInstanceOf(RuntimeException.class);
      assertThat(finalizationReads.requests).isEmpty();

      environment.sleep(Duration.ofMinutes(10));
      finalizationReads.allowCommittedReceipt();
      IntakeAgentRunFinalizationReadRequest readRequest =
          IntakeAgentRunFinalizationReadRequest.winningAttempt(
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
              command,
              request.childState());
      IntakeAgentRunFinalizationRecoveryRequest wrongWinningAttempt =
          recoveryRequest(
              workflowId,
              runId,
              command,
              request.childState(),
              WinningAttemptFinalizationReads.finalizationResult(
                  readRequest, WinningMismatch.ATTEMPT, true));
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongWinningAttempt))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining(
              "target finalization recovery read does not match expected authority");
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(workflow.state().pendingCommandId()).isEqualTo(command.commandId());

      finalizationReads.requests.clear();
      IntakeAgentRunFinalizationRecoveryResult recovered =
          workflow.recoverTargetFinalization(request);
      assertThat(recovered.request()).isEqualTo(request);
      assertThat(recovered.adoptedChildState()).isEqualTo(request.committedChildState());
      assertThat(recovered.finalization()).isEqualTo(request.expectedFinalization());
      assertThat(request.sourceProcessRevision()).isNull();
      assertThat(request.sourceRoomRevision()).isNull();
      assertThat(request.sourceLastCaseEventSequence()).isNull();
      assertThat(recovered.disposition()).isNull();
      assertThat(recovered.terminalNoCommitAuthority()).isNull();
      var converter = environment.getWorkflowClient().getOptions().getDataConverter();
      assertThat(converter.toPayload(request).orElseThrow().getData().toStringUtf8())
          .doesNotContain(
              "\"sourceProcessRevision\"",
              "\"sourceRoomRevision\"",
              "\"sourceLastCaseEventSequence\"");
      assertThat(converter.toPayload(recovered).orElseThrow().getData().toStringUtf8())
          .doesNotContain("\"disposition\"", "\"terminalNoCommitAuthority\"");
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(finalizationReads.requests.getFirst().schemaVersion())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.WINNING_ATTEMPT_SCHEMA_VERSION);
      assertThat(finalizationReads.requests.getFirst().mode())
          .isEqualTo(IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);

      IntakeRoomSnapshot adopted = workflow.state();
      assertThat(adopted.pendingCommand()).isNull();
      assertThat(adopted.processedCommandCount()).isEqualTo(1);
      assertThat(adopted.processedEventCount()).isEqualTo(1);
      assertThat(adopted.nextEventSequence()).isEqualTo(3);
      assertThat(adopted.lastEventId())
          .isEqualTo(request.expectedFinalization().receipt().committedEvent().eventId());
      assertThat(adopted.lastAgentRunRef().attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
      assertThat(adopted.protocolErrorCode()).isNull();

      IntakeAgentRunFinalizationRecoveryResult replayed =
          workflow.recoverTargetFinalization(request);
      assertThat(replayed).isEqualTo(recovered);
      assertThat(finalizationReads.requests).hasSize(1);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongRun))
          .isInstanceOf(RuntimeException.class);
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(workflow.state()).isEqualTo(adopted);
      assertThat(WinningAttemptAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      assertThat(providerActivities.snapshotRequests).isEmpty();
      assertThat(providerActivities.graphRequests).isEmpty();
      assertThat(providerActivities.finalizationRequests).isEmpty();
      assertThat(providerActivities.acceptRequests).isEmpty();
      assertThat(providerActivities.cancelRequests).isEmpty();
    }
  }

  @Test
  void pendingChildAdoptsCommittedWinnerAndReplaysAcrossContinueAsNew() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-pending-committed-finalization-recovery";
      String workflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      FinalizationRejectedThenCompletedAgentRunWorkflow.reset();
      RecoverableWinningAttemptFinalizationReads finalizationReads =
          new RecoverableWinningAttemptFinalizationReads();
      SnapshotOnlyActivities providerActivities = new SnapshotOnlyActivities();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(
          FinalizationRejectedThenCompletedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(finalizationReads, providerActivities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(workflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String runId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      workflow.targetSourceEventObserved(
          cursor(1, "EVENT_P9_PENDING_COMMITTED_SOURCE_1", "ROOM_MESSAGE_CREATED"));
      workflow.targetSourceEventObserved(
          cursor(2, "EVENT_P9_PENDING_COMMITTED_SOURCE_2", "INTAKE_PROJECTION_READY"));
      IntakeRoomSnapshot preCommand =
          awaitState(workflow, snapshot -> snapshot.nextEventSequence() == 3);
      assertThat(preCommand.nextEventSequence() - 1).isEqualTo(2);

      IntakeWorkflowCommand command = targetCommand(LIFECYCLE_FIRST_COMMAND_ID, 1, 0);
      workflow.commandAccepted(command);
      environment.sleep(Duration.ofSeconds(4));
      IntakeRoomSnapshot stranded =
          awaitState(
              workflow,
              snapshot ->
                  snapshot.pendingCommand() != null
                      && command.commandId().equals(snapshot.pendingCommand().commandId())
                      && "INTAKE_ACTIVITY_RECEIPT_INVALID".equals(snapshot.protocolErrorCode()));
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests)
          .containsExactly(targetRequest(command));
      workflow.targetSourceEventObserved(
          cursor(3, "EVENT_P9_PENDING_COMMITTED_SOURCE_3", "ROOM_MESSAGE_CREATED"));
      IntakeRoomSnapshot recoveryState =
          awaitState(workflow, snapshot -> snapshot.nextEventSequence() == 4);
      assertThat(recoveryState.nextEventSequence() - 1).isEqualTo(3);

      IntakeAgentRunChildState pendingChild =
          IntakeAgentRunChildState.pending(
              IntakeAgentRunChildIds.forCommand(command),
              command.executionContext().targetAgentRun());
      IntakeAgentRunFinalizationReadRequest readRequest =
          IntakeAgentRunFinalizationReadRequest.winningAttempt(
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
              command,
              pendingChild);
      IntakeAgentRunFinalizationReadResult expected =
          finalizationWithEventCoordinates(
              WinningAttemptFinalizationReads.finalizationResult(
                  readRequest, WinningMismatch.NONE, true),
              4,
              recoveryState.processRevision() + 1,
              recoveryState.roomRevision() + 1);
      long sourceLastCaseEventSequence = recoveryState.nextEventSequence() - 1;
      assertThat(expected.receipt().committedEvent().eventSequence()).isEqualTo(4);
      IntakeAgentRunFinalizationRecoveryRequest request =
          pendingCommittedRecoveryRequest(
              workflowId,
              runId,
              command,
              pendingChild,
              expected,
              recoveryState.processRevision(),
              recoveryState.roomRevision(),
              sourceLastCaseEventSequence);
      IntakeAgentRunFinalizationRecoveryRequest wrongRun =
          pendingCommittedRecoveryRequest(
              workflowId,
              runId + "-foreign",
              command,
              pendingChild,
              expected,
              recoveryState.processRevision(),
              recoveryState.roomRevision(),
              sourceLastCaseEventSequence);
      IntakeWorkflowCommand foreignCommand =
          targetCommand(LIFECYCLE_SECOND_COMMAND_ID, 1, 0);
      IntakeAgentRunChildState foreignPendingChild =
          IntakeAgentRunChildState.pending(
              IntakeAgentRunChildIds.forCommand(foreignCommand),
              foreignCommand.executionContext().targetAgentRun());
      IntakeAgentRunFinalizationReadResult foreignExpected =
          finalizationWithEventCoordinates(
              WinningAttemptFinalizationReads.finalizationResult(
                  IntakeAgentRunFinalizationReadRequest.winningAttempt(
                      IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
                      foreignCommand,
                      foreignPendingChild),
                  WinningMismatch.NONE,
                  true),
              4,
              recoveryState.processRevision() + 1,
              recoveryState.roomRevision() + 1);
      IntakeAgentRunFinalizationRecoveryRequest wrongCommand =
          pendingCommittedRecoveryRequest(
              workflowId,
              runId,
              foreignCommand,
              foreignPendingChild,
              foreignExpected,
              recoveryState.processRevision(),
              recoveryState.roomRevision(),
              sourceLastCaseEventSequence);
      IntakeDomainEventRef committedEvent = expected.receipt().committedEvent();
      IntakeAgentRunFinalizationReadResult wrongSourceExpected =
          finalizationWithEventCoordinates(
              expected,
              committedEvent.eventSequence() + 1,
              committedEvent.processRevision(),
              committedEvent.roomRevision());
      IntakeAgentRunFinalizationRecoveryRequest wrongSourceCursor =
          pendingCommittedRecoveryRequest(
              workflowId,
              runId,
              command,
              pendingChild,
              wrongSourceExpected,
              recoveryState.processRevision(),
              recoveryState.roomRevision(),
              sourceLastCaseEventSequence + 1);

      finalizationReads.requests.clear();
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongRun))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongCommand))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongSourceCursor))
          .isInstanceOf(RuntimeException.class);
      assertThat(finalizationReads.requests).isEmpty();
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild,
                      expected,
                      recoveryState.processRevision() + 1,
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("source revisions conflict");
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild.resultReady(expected.locator().resultHash()),
                      expected,
                      recoveryState.processRevision(),
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pending child");
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild,
                      finalizationWithActivation(expected, "p9act.v1." + "b".repeat(32)),
                      recoveryState.processRevision(),
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild,
                      finalizationWithEventCoordinates(
                          expected,
                          committedEvent.eventSequence() + 1,
                          committedEvent.processRevision(),
                          committedEvent.roomRevision()),
                      recoveryState.processRevision(),
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact successor");
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild,
                      finalizationWithEventCoordinates(
                          expected,
                          committedEvent.eventSequence(),
                          committedEvent.processRevision() + 1,
                          committedEvent.roomRevision()),
                      recoveryState.processRevision(),
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact successor");
      assertThatThrownBy(
              () ->
                  pendingCommittedRecoveryRequest(
                      workflowId,
                      runId,
                      command,
                      pendingChild,
                      finalizationWithEventCoordinates(
                          expected,
                          committedEvent.eventSequence(),
                          committedEvent.processRevision(),
                          committedEvent.roomRevision() + 1),
                      recoveryState.processRevision(),
                      recoveryState.roomRevision(),
                      sourceLastCaseEventSequence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact successor");
      assertThat(finalizationReads.requests).isEmpty();

      finalizationReads.allowCommittedReceipt(expected);
      IntakeAgentRunFinalizationRecoveryResult recovered =
          workflow.recoverTargetFinalization(request);
      assertThat(recovered.schemaVersion())
          .isEqualTo(IntakeAgentRunFinalizationRecoveryResult.V3_SCHEMA_VERSION);
      assertThat(recovered.request()).isEqualTo(request);
      assertThat(recovered.adoptedChildState()).isEqualTo(request.committedChildState());
      assertThat(recovered.finalization()).isEqualTo(expected);
      assertThat(recovered.disposition())
          .isEqualTo(
              IntakeAgentRunFinalizationRecoveryResult.Disposition
                  .PENDING_COMMITTED_RECEIPT_ADOPTED);
      assertThat(recovered.terminalNoCommitAuthority()).isNull();
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(finalizationReads.requests.getFirst()).isEqualTo(readRequest);

      IntakeRoomSnapshot adopted = workflow.state();
      assertThat(adopted.pendingCommand()).isNull();
      assertThat(adopted.processedCommandCount()).isEqualTo(1);
      assertThat(adopted.processedEventCount()).isEqualTo(1);
      assertThat(adopted.processRevision()).isEqualTo(recoveryState.processRevision() + 1);
      assertThat(adopted.roomRevision()).isEqualTo(recoveryState.roomRevision() + 1);
      assertThat(adopted.nextEventSequence()).isEqualTo(5);
      assertThat(adopted.lastEventId()).isEqualTo(expected.receipt().committedEvent().eventId());
      assertThat(adopted.lastAgentRunRef().attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
      assertThat(adopted.protocolErrorCode()).isNull();
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests)
          .containsExactly(targetRequest(command));
      assertThat(providerActivities.snapshotRequests).isEmpty();
      assertThat(providerActivities.graphRequests).isEmpty();
      assertThat(providerActivities.finalizationRequests).isEmpty();
      assertThat(providerActivities.acceptRequests).isEmpty();
      assertThat(providerActivities.cancelRequests).isEmpty();

      assertThat(workflow.recoverTargetFinalization(request)).isEqualTo(recovered);
      assertThat(finalizationReads.requests).hasSize(1);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongSourceCursor))
          .isInstanceOf(RuntimeException.class);
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(workflow.state()).isEqualTo(adopted);

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      IntakeRoomCarryState continuedCarry =
          currentIntakeRoomStart(environment, workflowId).carryState();
      assertThat(continuedCarry).isNotNull();
      assertThat(continuedCarry.schemaVersion()).isEqualTo(IntakeRoomCarryState.V7_SCHEMA_VERSION);
      assertThat(continuedCarry.completedTargetFinalizationRecoveryRequest()).isEqualTo(request);
      assertThat(continuedCarry.completedTargetFinalizationRecoveryResult()).isEqualTo(recovered);
      assertThat(workflow.recoverTargetFinalization(request)).isEqualTo(recovered);
      assertThatThrownBy(() -> workflow.recoverTargetFinalization(wrongSourceCursor))
          .isInstanceOf(RuntimeException.class);
      assertThat(finalizationReads.requests).hasSize(1);
      assertThat(FinalizationRejectedThenCompletedAgentRunWorkflow.requests)
          .containsExactly(targetRequest(command));
    }
  }

  @Test
  void recoveryAuthorityRejectsForeignRoomChildAndReceiptProof() {
    String workflowId =
        CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
    String runId = "RUN_P9_RECOVERY_AUTHORITY";
    IntakeWorkflowCommand command = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
    IntakeAgentRunChildState childState = winningResultReadyChild(command);
    IntakeAgentRunFinalizationReadResult expected = winningFinalization(command, childState);

    assertThatThrownBy(
            () ->
                new IntakeAgentRunFinalizationRecoveryRequest(
                    IntakeAgentRunFinalizationRecoveryRequest.SCHEMA_VERSION,
                    CaseProcessWorkflowProtocol.roomWorkflowId(
                        "CASE_P9_FOREIGN", RoomType.INTAKE, EPOCH),
                    runId,
                    TENANT,
                    CASE_ID,
                    EPOCH,
                    FENCE,
                    command,
                    childState,
                    expected))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IntakeAgentRunFinalizationRecoveryRequest(
                    IntakeAgentRunFinalizationRecoveryRequest.SCHEMA_VERSION,
                    workflowId,
                    runId,
                    "tenant-p9-foreign",
                    CASE_ID,
                    EPOCH,
                    FENCE,
                    command,
                    childState,
                    expected))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IntakeAgentRunFinalizationRecoveryRequest(
                    IntakeAgentRunFinalizationRecoveryRequest.SCHEMA_VERSION,
                    CaseProcessWorkflowProtocol.roomWorkflowId(
                        CASE_ID, RoomType.INTAKE, EPOCH + 1),
                    runId,
                    TENANT,
                    CASE_ID,
                    EPOCH + 1,
                    FENCE,
                    command,
                    childState,
                    expected))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IntakeAgentRunFinalizationRecoveryRequest(
                    IntakeAgentRunFinalizationRecoveryRequest.SCHEMA_VERSION,
                    workflowId,
                    runId,
                    TENANT,
                    CASE_ID,
                    EPOCH,
                    FENCE + 1,
                    command,
                    childState,
                    expected))
        .isInstanceOf(IllegalArgumentException.class);

    IntakeAgentRunChildState pendingChild =
        IntakeAgentRunChildState.pending(
            IntakeAgentRunChildIds.forCommand(command),
            command.executionContext().targetAgentRun());
    assertThatThrownBy(
            () ->
                recoveryRequest(workflowId, runId, command, pendingChild, expected))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                recoveryRequest(
                    workflowId,
                    runId,
                    command,
                    childState,
                    finalizationWithActivation(
                        expected, "p9act.v1." + "b".repeat(32))))
        .isInstanceOf(IllegalArgumentException.class);

    IntakeWorkflowCommand foreignLogicalRunCommand =
        targetCommand(WINNING_SECOND_COMMAND_ID, 1, 0);
    IntakeAgentRunChildState foreignLogicalRunChild =
        winningResultReadyChild(foreignLogicalRunCommand);
    assertThatThrownBy(
            () ->
                recoveryRequest(
                    workflowId,
                    runId,
                    command,
                    childState,
                    winningFinalization(foreignLogicalRunCommand, foreignLogicalRunChild)))
        .isInstanceOf(IllegalArgumentException.class);

    IntakeAgentRunFinalizationReadRequest readRequest =
        IntakeAgentRunFinalizationReadRequest.winningAttempt(
            IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
            command,
            childState);
    for (WinningMismatch mismatch :
        List.of(
            WinningMismatch.COMMAND,
            WinningMismatch.GRAPH,
            WinningMismatch.OPERATION,
            WinningMismatch.RESULT)) {
      IntakeAgentRunFinalizationReadResult mismatched =
          WinningAttemptFinalizationReads.finalizationResult(readRequest, mismatch, true);
      assertThatThrownBy(
              () -> recoveryRequest(workflowId, runId, command, childState, mismatched))
          .as("foreign winning proof %s", mismatch)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void recoveryRejectsMissingPendingAndActiveOrchestrationWithoutAnotherRead() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-recovery-state-guards";
      String workflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      WinningAttemptAgentRunWorkflow.reset();
      BlockingFinalizationReads.reset();
      RecordingFinalizationReads.requests.clear();
      SnapshotOnlyActivities providerActivities = new SnapshotOnlyActivities();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(WinningAttemptAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerActivitiesImplementations(
          new BlockingFinalizationReads(), providerActivities);
      environment.start();

      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(workflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String runId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      IntakeWorkflowCommand command = targetCommand(WINNING_FIRST_COMMAND_ID, 1, 0);
      IntakeAgentRunFinalizationRecoveryRequest request =
          recoveryRequest(workflowId, runId, command);

      assertThatThrownBy(() -> workflow.recoverTargetFinalization(request))
          .isInstanceOf(RuntimeException.class);
      assertThat(RecordingFinalizationReads.requests).isEmpty();
      assertThat(WinningAttemptAgentRunWorkflow.requests).isEmpty();

      try {
        workflow.commandAccepted(command);
        assertThat(BlockingFinalizationReads.awaitStarted()).isTrue();
        assertThatThrownBy(() -> workflow.recoverTargetFinalization(request))
            .isInstanceOf(RuntimeException.class);
        assertThat(RecordingFinalizationReads.requests).isEmpty();
        assertThat(WinningAttemptAgentRunWorkflow.requests)
            .containsExactly(targetRequest(command));
        assertThat(providerActivities.snapshotRequests).isEmpty();
        assertThat(providerActivities.graphRequests).isEmpty();
        assertThat(providerActivities.finalizationRequests).isEmpty();
      } finally {
        BlockingFinalizationReads.release();
      }
    }
  }

  @Test
  void targetChildIdentityUsesCanonicalAgentRunIdAndUnresolvedChildCannotContinueAsNew() {
    IntakeWorkflowCommand command = targetCommand("C".repeat(128));
    assertThat(IntakeAgentRunChildIds.forCommand(command))
        .isEqualTo(
            TemporalAgentRunV2WorkflowLauncher.workflowId(
                command.executionContext().targetAgentRun().request().logicalRunId()));

    IntakeAgentRunChildState pending =
        IntakeAgentRunChildState.pending(
            IntakeAgentRunChildIds.forCommand(command),
            command.executionContext().targetAgentRun());
    assertThatThrownBy(() -> carryState(pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot cross continue-as-new");
  }

  private static IntakeRoomSnapshot awaitState(
      IntakeRoomWorkflow workflow,
      java.util.function.Predicate<IntakeRoomSnapshot> predicate) {
    IntakeRoomSnapshot last = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      last = workflow.state();
      if (predicate.test(last)) {
        return last;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError(
        "workflow did not reach the expected state; phase="
            + last.roomPhase()
            + ", pending="
            + (last.pendingCommand() != null)
            + ", processRevision="
            + last.processRevision()
            + ", roomRevision="
            + last.roomRevision()
            + ", protocolError="
            + last.protocolErrorCode());
  }

  private static String currentRunId(
      TestWorkflowEnvironment environment, String workflowId) {
    return environment
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(environment.getWorkflowClient().getOptions().getNamespace())
                .setExecution(
                    io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                        .setWorkflowId(workflowId)
                        .build())
                .build())
        .getWorkflowExecutionInfo()
        .getExecution()
        .getRunId();
  }

  private static IntakeRoomStart currentIntakeRoomStart(
      TestWorkflowEnvironment environment, String workflowId) {
    String runId = currentRunId(environment, workflowId);
    var history =
        environment
            .getWorkflowServiceStubs()
            .blockingStub()
            .getWorkflowExecutionHistory(
                io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(environment.getWorkflowClient().getOptions().getNamespace())
                    .setExecution(
                        io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .setRunId(runId)
                            .build())
                    .build())
            .getHistory();
    var input = history.getEvents(0).getWorkflowExecutionStartedEventAttributes().getInput();
    return environment
        .getWorkflowClient()
        .getOptions()
        .getDataConverter()
        .fromPayload(input.getPayloads(0), IntakeRoomStart.class, IntakeRoomStart.class);
  }

  private static List<TargetIntakeCommandTerminalNoCommit> awaitReceipts(
      TerminalReceiptSinkWorkflow workflow, int expectedCount) {
    for (int attempt = 0; attempt < 20; attempt++) {
      List<TargetIntakeCommandTerminalNoCommit> receipts = workflow.receipts();
      if (receipts.size() == expectedCount) {
        return receipts;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError("terminal-no-commit receipt count did not converge");
  }

  private static void assertLegacyTerminalNoCommitMarkerPath() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(new LegacyTerminalNoCommitVersionInterceptor())
                    .build())
            .build();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance(options)) {
      String roomQueue = "phase9-intake-terminal-no-commit-legacy-marker";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      AbortedAgentRunWorkflow.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(AbortedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      String roomRunId = WorkflowClient.start(workflow::run, targetStart()).getRunId();
      IntakeWorkflowCommand command = targetCommand();

      workflow.commandAccepted(command);

      TargetIntakeCommandTerminalNoCommit legacy = awaitReceipts(sink, 1).getFirst();
      assertThat(legacy.schemaVersion())
          .isEqualTo(TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION);
      assertThat(legacy.agentRunExecutionRequestHash()).isNull();
      assertThat(legacy.expectedLastCaseEventSequence()).isNull();
      assertThat(legacy.lastCaseEventSequence()).isZero();
      assertThat(legacy.commandId()).isEqualTo(command.commandId());
      assertThat(AbortedAgentRunWorkflow.requests).containsExactly(targetRequest(command));
      IntakeTerminalNoCommitRecoveryRequest expectedLegacyRequest =
          new IntakeTerminalNoCommitRecoveryRequest(
              IntakeTerminalNoCommitRecoveryRequest.LEGACY_SCHEMA_VERSION,
              roomWorkflowId,
              roomRunId,
              legacy);
      assertThatThrownBy(
              () ->
                  new IntakeTerminalNoCommitRecoveryRequest(
                      IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION,
                      roomWorkflowId,
                      roomRunId,
                      legacy))
          .isInstanceOf(IllegalArgumentException.class);

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      IntakeRoomStart continuedStart = currentIntakeRoomStart(environment, roomWorkflowId);
      IntakeRoomCarryState continuedCarry = continuedStart.carryState();
      assertThat(continuedCarry).isNotNull();
      assertThat(continuedCarry.schemaVersion()).isEqualTo("intake-room-carry-state.v4");
      IntakeTerminalNoCommitRecoveryResult carriedResult =
          continuedCarry.completedTerminalNoCommitRecovery();
      assertThat(carriedResult).isNotNull();
      assertThat(carriedResult.request()).isEqualTo(expectedLegacyRequest);
      assertThat(carriedResult.request().schemaVersion())
          .isEqualTo(IntakeTerminalNoCommitRecoveryRequest.LEGACY_SCHEMA_VERSION);
      assertThat(carriedResult.request().authority()).isEqualTo(legacy);
      assertThat(carriedResult.request().authority().schemaVersion())
          .isEqualTo(TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION);
      var converter = environment.getWorkflowClient().getOptions().getDataConverter();
      String legacyAuthorityJson =
          converter.toPayload(legacy).orElseThrow().getData().toStringUtf8();
      String exactLegacyRequestJson =
          "{\"schemaVersion\":\"intake-terminal-no-commit-recovery-request.v1\""
              + ",\"workflowId\":\""
              + roomWorkflowId
              + "\",\"workflowRunId\":\""
              + roomRunId
              + "\",\"authority\":"
              + legacyAuthorityJson
              + "}";
      String carriedRequestJson =
          converter
              .toPayload(carriedResult.request())
              .orElseThrow()
              .getData()
              .toStringUtf8();
      assertThat(carriedRequestJson).isEqualTo(exactLegacyRequestJson);
      assertThat(
              converter
                  .toPayload(expectedLegacyRequest)
                  .orElseThrow()
                  .getData()
                  .toStringUtf8())
          .isEqualTo(exactLegacyRequestJson);
      assertThatThrownBy(
              () -> workflow.recoverTerminalNoCommit(carriedResult.request()))
          .isInstanceOf(RuntimeException.class);
      assertThat(AbortedAgentRunWorkflow.requests).containsExactly(targetRequest(command));
    }
  }

  private static void assertV2TerminalNoCommitMarkerPath() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(new V2TerminalNoCommitVersionInterceptor())
                    .build())
            .build();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance(options)) {
      String roomQueue = "phase9-intake-terminal-no-commit-v2-marker";
      String roomWorkflowId =
          CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH);
      String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
      AbortedAgentRunWorkflow.reset();

      Worker roomWorker = environment.newWorker(roomQueue);
      roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
      agentWorker.registerWorkflowImplementationTypes(AbortedAgentRunWorkflow.class);
      Worker controlWorker = environment.newWorker(CASE_CONTROL);
      controlWorker.registerWorkflowImplementationTypes(TerminalReceiptSinkWorkflowImpl.class);
      environment.start();

      TerminalReceiptSinkWorkflow sink =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  TerminalReceiptSinkWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(caseWorkflowId)
                      .setTaskQueue(CASE_CONTROL)
                      .build());
      WorkflowClient.start(sink::run);
      IntakeRoomWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  IntakeRoomWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(roomWorkflowId)
                      .setTaskQueue(roomQueue)
                      .build());
      WorkflowClient.start(workflow::run, targetStart());
      IntakeWorkflowCommand command = targetCommand();

      workflow.commandAccepted(command);
      TargetIntakeCommandTerminalNoCommit v2 = awaitReceipts(sink, 1).getFirst();
      assertThat(v2.schemaVersion())
          .isEqualTo(TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION);
      assertThat(v2.expectedProjectionLastCaseEventSequence()).isNull();
      assertThat(v2.newProjectionLastCaseEventSequence()).isNull();
      assertThat(v2.interveningCaseEvents()).isNull();
      String v2Json =
          environment
              .getWorkflowClient()
              .getOptions()
              .getDataConverter()
              .toPayload(v2)
              .orElseThrow()
              .getData()
              .toStringUtf8();
      assertThat(v2Json)
          .doesNotContain(
              "expectedProjectionLastCaseEventSequence",
              "newProjectionLastCaseEventSequence",
              "interveningCaseEvents");

      workflow.requestContinueAsNew();
      awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
      IntakeRoomCarryState continued = currentIntakeRoomStart(environment, roomWorkflowId).carryState();
      assertThat(continued.schemaVersion()).isEqualTo("intake-room-carry-state.v4");
      assertThat(continued.completedTerminalNoCommitRecovery().request().authority())
          .isEqualTo(v2);
      assertThat(continued.completedTerminalNoCommitRecovery().request().schemaVersion())
          .isEqualTo(IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION);
      assertThat(AbortedAgentRunWorkflow.requests).containsExactly(targetRequest(command));
    }
  }

  private static IntakeRoomWorkflow startWinningWorkflow(
      TestWorkflowEnvironment environment,
      String roomQueue,
      WinningAttemptFinalizationReads finalizationReads,
      SnapshotOnlyActivities branchActivities) {
    Worker roomWorker = environment.newWorker(roomQueue);
    roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
    Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
    agentWorker.registerWorkflowImplementationTypes(WinningAttemptAgentRunWorkflow.class);
    Worker controlWorker = environment.newWorker(CASE_CONTROL);
    if (branchActivities == null) {
      controlWorker.registerActivitiesImplementations(finalizationReads);
    } else {
      controlWorker.registerActivitiesImplementations(finalizationReads, branchActivities);
    }
    environment.start();

    IntakeRoomWorkflow workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                IntakeRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("intake-room:" + roomQueue + ":" + CASE_ID + ":" + EPOCH)
                    .setTaskQueue(roomQueue)
                    .build());
    WorkflowClient.start(workflow::run, targetStart());
    return workflow;
  }

  private static IntakeRoomWorkflow startLifecycleWorkflow(
      TestWorkflowEnvironment environment,
      String roomQueue,
      RecordingFinalizationReads finalizationReads) {
    return startLifecycleWorkflow(environment, roomQueue, finalizationReads, null);
  }

  private static IntakeRoomWorkflow startLifecycleWorkflow(
      TestWorkflowEnvironment environment,
      String roomQueue,
      RecordingFinalizationReads finalizationReads,
      SnapshotOnlyActivities branchActivities) {
    return startLifecycleWorkflow(
        environment,
        roomQueue,
        finalizationReads,
        branchActivities,
        LifecycleAgentRunWorkflow.class);
  }

  private static IntakeRoomWorkflow startBlockingLifecycleWorkflow(
      TestWorkflowEnvironment environment,
      String roomQueue,
      RecordingFinalizationReads finalizationReads,
      SnapshotOnlyActivities branchActivities) {
    return startLifecycleWorkflow(
        environment,
        roomQueue,
        finalizationReads,
        branchActivities,
        BlockingLifecycleAgentRunWorkflow.class);
  }

  private static IntakeRoomWorkflow startLifecycleWorkflow(
      TestWorkflowEnvironment environment,
      String roomQueue,
      RecordingFinalizationReads finalizationReads,
      SnapshotOnlyActivities branchActivities,
      Class<?> agentRunWorkflowImplementation) {
    Worker roomWorker = environment.newWorker(roomQueue);
    roomWorker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
    Worker agentWorker = environment.newWorker(AGENT_EXECUTION);
    agentWorker.registerWorkflowImplementationTypes(agentRunWorkflowImplementation);
    Worker controlWorker = environment.newWorker(CASE_CONTROL);
    if (branchActivities == null) {
      controlWorker.registerActivitiesImplementations(finalizationReads);
    } else {
      controlWorker.registerActivitiesImplementations(finalizationReads, branchActivities);
    }
    environment.start();

    IntakeRoomWorkflow workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                IntakeRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("intake-room:" + roomQueue + ":" + CASE_ID + ":" + EPOCH)
                    .setTaskQueue(roomQueue)
                    .build());
    WorkflowClient.start(workflow::run, targetStart());
    return workflow;
  }

  private static void assertWinningAttemptIdentity(
      IntakeWorkflowCommand initialCommand, WinningAttemptFinalizationReads finalizationReads) {
    assertThat(WinningAttemptAgentRunWorkflow.results).hasSize(1);
    ExecuteAgentRunResult winningResult = WinningAttemptAgentRunWorkflow.results.getFirst();
    assertThat(winningResult.attemptNo()).isEqualTo(2);
    assertThat(winningResult.attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
    assertThat(winningResult.attemptId())
        .isNotEqualTo(initialCommand.executionContext().targetAgentRun().request().attemptId());
    assertThat(winningResult.graphResult().commandId()).isEqualTo(WINNING_GRAPH_COMMAND_ID);
    assertThat(winningResult.graphResult().commandId()).isNotEqualTo(initialCommand.commandId());
    assertThat(finalizationReads.committedEvents).isNotEmpty();
    IntakeDomainEventRef winningEvent = finalizationReads.committedEvents.getFirst();
    assertThat(winningEvent.commandId()).isEqualTo(WINNING_FORMAL_COMMAND_ID);
    assertThat(winningEvent.commandId()).isNotEqualTo(initialCommand.commandId());
    assertThat(winningEvent.agentRunRef().attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
    assertThat(winningEvent.graphExecutionRef().graphCommandId())
        .isEqualTo(WINNING_GRAPH_COMMAND_ID);
    assertThat(winningEvent.graphExecutionRef().graphCommandId())
        .isNotEqualTo(initialCommand.commandId());
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
        CONTROL_BUILD,
        GRAPH_VERSION,
        CHECKPOINT_SCHEMA,
        "p9-prompt-v1",
        "p9-model-v1",
        "production-runtime-intake-output.v1",
        "p9-policy-v1",
        "p9-guardrail-v1",
        "p9-tools-v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static IntakeRoomStart targetStart() {
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
        CONTROL_BUILD,
        GRAPH_VERSION,
        CHECKPOINT_SCHEMA,
        "all-rooms-prompt.production-runtime.v2",
        "production-runtime.contract-blocked",
        "production-runtime-intake-output.v1",
        "all-rooms-policy.production-runtime.v1",
        "all-rooms-guardrail.production-runtime.v1",
        "tools.none.v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static IntakeWorkflowCommand targetCommand() {
    return targetCommand(COMMAND_ID, 1, 0);
  }

  private static IntakeWorkflowCommand targetCommand(String commandId) {
    return targetCommand(commandId, 1, 0);
  }

  private static IntakeWorkflowCommand targetCommand(
      String commandId, long sequence, long expectedRevision) {
    long deadline = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
    String payloadRef = "urn:after-sale-flow:intake-command:" + commandId;
    String payloadHash = hash(3);
    String commandRequestHash = hash(4);
    IntakeWorkflowCommand shell =
        new IntakeWorkflowCommand(
            "intake-workflow-command.v1",
            commandId,
            TENANT,
            CASE_ID,
            EPOCH,
            FENCE,
            sequence,
            IntakeCommandType.INTAKE_MESSAGE,
            IntakeParty.INITIATOR,
            INITIATOR_SCOPE,
            payloadRef,
            payloadHash,
            "intake.operation:" + CASE_ID + ":" + commandId,
            commandRequestHash);
    ExecuteAgentRunRequest request = targetRequest(shell, deadline, expectedRevision);
    IntakeTargetAgentRunContext target =
        new IntakeTargetAgentRunContext(
            "intake-target-agent-run-context.v1",
            IntakeTargetAgentRunContext.TARGET_LANE,
            ACTIVATION_ID,
            hash(5),
            FENCE,
            expectedRevision,
            expectedRevision,
            "p9-case-build",
            CONTROL_BUILD,
            "p9-agent-build",
            hash(6),
            "p9-graph-build",
            hash(7),
            hash(8),
            request);
    return new IntakeWorkflowCommand(
        shell.schemaVersion(),
        shell.commandId(),
        shell.tenantSurrogate(),
        shell.caseId(),
        shell.roomEpoch(),
        shell.fencingToken(),
        shell.sequence(),
        shell.commandType(),
        shell.party(),
        shell.actorScopeHash(),
        shell.payloadRef(),
        shell.payloadHash(),
        shell.operationKey(),
        shell.requestHash(),
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v2",
            THREAD_ID,
            AGENT_SESSION_ID,
            deadline,
            new RetryBudget("intake-retry-budget.v1", 2, 2, 1),
            null,
            target));
  }

  private static TargetIntakeSourceEventRef cursor(
      long sequence, String eventId, String eventType) {
    return new TargetIntakeSourceEventRef(
        TargetIntakeSourceEventRef.SCHEMA_VERSION,
        eventId,
        sequence,
        eventType,
        TENANT,
        CASE_ID,
        RoomType.INTAKE,
        EPOCH,
        FENCE,
        hash(sequence));
  }

  private static IntakeWorkflowCommand initiatorConfirmation(String commandId, long sequence) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        sequence,
        IntakeCommandType.INTAKE_CONFIRM,
        IntakeParty.INITIATOR,
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(3),
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash(4));
  }

  private static IntakeWorkflowCommand pinnedInitiatorConfirmation(
      String commandId,
      long sequence,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        sequence,
        IntakeCommandType.INTAKE_CONFIRM,
        IntakeParty.INITIATOR,
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash(sequence + 1),
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v4",
            THREAD_ID,
            AGENT_SESSION_ID,
            Long.MAX_VALUE,
            new RetryBudget("intake-retry-budget.v1", 0, 2, 0),
            BranchOperation.INITIATOR_ACCEPT,
            null,
            expectedProcessRevision,
            expectedRoomRevision));
  }

  private static IntakeWorkflowCommand cancellationCommand(
      String commandId,
      long sequence,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        sequence,
        IntakeCommandType.INTAKE_CANCEL,
        IntakeParty.INITIATOR,
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash(sequence + 1),
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v4",
            THREAD_ID,
            AGENT_SESSION_ID,
            Long.MAX_VALUE,
            new RetryBudget("intake-retry-budget.v1", 0, 2, 0),
            BranchOperation.CANCEL,
            null,
            expectedProcessRevision,
            expectedRoomRevision));
  }

  private static ExecuteAgentRunRequest targetRequest(IntakeWorkflowCommand command) {
    return command.executionContext().targetAgentRun().request();
  }

  private static IntakeAgentRunChildState winningResultReadyChild(
      IntakeWorkflowCommand command) {
    return IntakeAgentRunChildState.pending(
            IntakeAgentRunChildIds.forCommand(command),
            command.executionContext().targetAgentRun())
        .resultReady(WINNING_RESULT_HASH);
  }

  private static IntakeAgentRunFinalizationReadResult winningFinalization(
      IntakeWorkflowCommand command, IntakeAgentRunChildState childState) {
    IntakeAgentRunFinalizationReadRequest readRequest =
        IntakeAgentRunFinalizationReadRequest.winningAttempt(
            IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
            command,
            childState);
    return WinningAttemptFinalizationReads.finalizationResult(
        readRequest, WinningMismatch.NONE, true);
  }

  private static IntakeAgentRunFinalizationReadResult finalizationWithActivation(
      IntakeAgentRunFinalizationReadResult finalization, String activationId) {
    FinalizationLocator locator = finalization.locator();
    return new IntakeAgentRunFinalizationReadResult(
        finalization.schemaVersion(),
        finalization.resolution(),
        new FinalizationLocator(
            locator.schemaVersion(),
            locator.executionLane(),
            activationId,
            locator.activationManifestHash(),
            locator.roomFencingToken(),
            locator.logicalRunId(),
            locator.attemptId(),
            locator.resultHash(),
            locator.proposalHash(),
            locator.checkpointId(),
            locator.operationKey(),
            locator.agentRunManifestId(),
            locator.agentRunManifestHash(),
            locator.isolatedDomainDbBindingHash(),
            locator.receiptHash()),
        finalization.receipt());
  }

  private static IntakeAgentRunFinalizationReadResult finalizationWithEventCoordinates(
      IntakeAgentRunFinalizationReadResult finalization,
      long eventSequence,
      long processRevision,
      long roomRevision) {
    IntakeDomainEventRef event = finalization.receipt().committedEvent();
    IntakeDomainEventRef changedEvent =
        new IntakeDomainEventRef(
            event.schemaVersion(),
            event.eventId(),
            event.eventRef(),
            event.eventHash(),
            eventSequence,
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
            processRevision,
            roomRevision,
            event.agentRunRef(),
            event.graphExecutionRef());
    TurnFinalizationReceipt receipt = finalization.receipt();
    OperationReceipt operation = receipt.operation();
    OperationReceipt changedOperation =
        new OperationReceipt(
            operation.schemaVersion(),
            operation.operationKey(),
            operation.requestHash(),
            operation.resultHash(),
            processRevision,
            roomRevision);
    FormalFinalizationReceipt formal = receipt.formalReceipt();
    FormalFinalizationReceipt changedFormal =
        new FormalFinalizationReceipt(
            formal.schemaVersion(),
            formal.operationKey(),
            formal.tenantSurrogate(),
            formal.caseId(),
            formal.roomEpoch(),
            formal.threadId(),
            formal.actorScopeHash(),
            formal.agentSessionId(),
            formal.commandId(),
            formal.logicalRunId(),
            formal.attemptId(),
            formal.resultHash(),
            formal.proposalHash(),
            processRevision,
            roomRevision,
            formal.fencingToken(),
            formal.formalMessageId(),
            formal.dossierVersion(),
            formal.matrixVersion(),
            formal.domainEventIds(),
            formal.outboxIds(),
            formal.status(),
            formal.committedAt(),
            formal.receiptHash());
    return new IntakeAgentRunFinalizationReadResult(
        finalization.schemaVersion(),
        finalization.resolution(),
        finalization.locator(),
        new TurnFinalizationReceipt(
            receipt.schemaVersion(), changedOperation, changedFormal, changedEvent));
  }

  private static IntakeAgentRunFinalizationRecoveryRequest recoveryRequest(
      String workflowId, String runId, IntakeWorkflowCommand command) {
    IntakeAgentRunChildState childState = winningResultReadyChild(command);
    return recoveryRequest(
        workflowId, runId, command, childState, winningFinalization(command, childState));
  }

  private static IntakeAgentRunFinalizationRecoveryRequest recoveryRequest(
      String workflowId,
      String runId,
      IntakeWorkflowCommand command,
      IntakeAgentRunChildState childState,
      IntakeAgentRunFinalizationReadResult expectedFinalization) {
    return new IntakeAgentRunFinalizationRecoveryRequest(
        IntakeAgentRunFinalizationRecoveryRequest.SCHEMA_VERSION,
        workflowId,
        runId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        command,
        childState,
        expectedFinalization);
  }

  private static IntakeAgentRunFinalizationRecoveryRequest pendingCommittedRecoveryRequest(
      String workflowId,
      String runId,
      IntakeWorkflowCommand command,
      IntakeAgentRunChildState childState,
      IntakeAgentRunFinalizationReadResult expectedFinalization,
      long sourceProcessRevision,
      long sourceRoomRevision,
      long sourceLastCaseEventSequence) {
    return new IntakeAgentRunFinalizationRecoveryRequest(
        IntakeAgentRunFinalizationRecoveryRequest.V3_SCHEMA_VERSION,
        workflowId,
        runId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        command,
        childState,
        expectedFinalization,
        sourceProcessRevision,
        sourceRoomRevision,
        sourceLastCaseEventSequence);
  }

  private static ExecuteAgentRunRequest targetRequest(
      IntakeWorkflowCommand command, long deadlineEpochMillis) {
    return targetRequest(command, deadlineEpochMillis, 0);
  }

  private static ExecuteAgentRunRequest targetRequest(
      IntakeWorkflowCommand command, long deadlineEpochMillis, long expectedRevision) {
    String logicalRunId =
        COMMAND_ID.equals(command.commandId())
            ? LOGICAL_RUN_ID
            : "RUN:P9:" + command.commandId().replace(':', '_');
    String attemptId =
        COMMAND_ID.equals(command.commandId())
            ? ATTEMPT_ID
            : "ATTEMPT:P9:" + command.commandId().replace(':', '_') + ":1";
    RoomGraphCommand graphCommand =
        new RoomGraphCommand(
            "room-graph-command.v1",
            command.commandId(),
            logicalRunId,
            attemptId,
            TENANT,
            CASE_ID,
            RoomType.INTAKE,
            EPOCH,
            "intake.v2",
            GRAPH_VERSION,
            CHECKPOINT_SCHEMA,
            THREAD_ID,
            new RoomGraphCommand.ActorScope(
                "USER_P9_CHILD", ActorRole.USER, Audience.USER, List.of("INTAKE_MESSAGE")),
            expectedRevision,
            "INTAKE_MESSAGE",
            1,
            new RoomGraphCommand.SnapshotRef(
                "SNAPSHOT_P9_CHILD",
                "intake-domain-snapshot.v2",
                "urn:after-sale-flow:intake-snapshot:p9-child",
                hash(9),
                1024),
            new RoomGraphCommand.SnapshotRef(
                "EVENT_" + command.commandId(),
                "production-runtime-intake-message.v1",
                command.payloadRef(),
                command.payloadHash(),
                128),
            new RoomGraphCommand.InvocationContext(
                "p9-intake-agent",
                "p9-prompt-v1",
                "p9-model-v1",
                "production-runtime-intake-output.v1",
                "p9-policy-v1",
                "p9-guardrail-v1",
                List.of(),
                "p9-envelope-key",
                "p9-envelope-nonce"),
            new RoomGraphCommand.RetryBudget(2, 2, 1),
            Instant.ofEpochMilli(deadlineEpochMillis),
            "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01",
            hash(4));
    return new ExecuteAgentRunRequest(
        ExecuteAgentRunRequest.SCHEMA_VERSION,
        logicalRunId,
        1,
        2,
        "agent-stream.v3",
        hash(6),
        null,
        false,
        0,
        graphCommand);
  }

  private static IntakeRoomCarryState carryState(IntakeAgentRunChildState child) {
    return new IntakeRoomCarryState(
        "intake-room-carry-state.v2",
        IntakeRoomPhase.AGENT_RUNNING,
        IntakeParty.INITIATOR,
        2,
        1,
        1,
        0,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        0,
        null,
        List.of(),
        List.of(),
        List.of(),
        child);
  }

  private static IntakeRoomCarryState historicalTerminalNoCommitCarry(
      IntakeWorkflowCommand command, String retainedReasonCode) {
    IntakeCommandDecision rejected =
        new IntakeCommandDecision(
            "intake-command-decision.v1",
            command.commandId(),
            command.sequence(),
            "REJECTED",
            retainedReasonCode,
            IntakeRoomPhase.OPEN,
            command.requestHash());
    return new IntakeRoomCarryState(
        "intake-room-carry-state.v1",
        IntakeRoomPhase.OPEN,
        IntakeParty.INITIATOR,
        command.sequence() + 1,
        16,
        1,
        0,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        retainedReasonCode,
        1,
        rejected,
        List.of(
            new IntakeRoomCarryState.ObservedCommand(
                "intake-observed-command.v1", command, rejected)),
        List.of(),
        List.of());
  }

  private static IntakeRoomCarryState historicalTerminalNoCommitCarryWithLineage(
      IntakeWorkflowCommand command, String retainedReasonCode) {
    IntakeCommandDecision rejected =
        new IntakeCommandDecision(
            "intake-command-decision.v1",
            command.commandId(),
            command.sequence(),
            "REJECTED",
            retainedReasonCode,
            IntakeRoomPhase.OPEN,
            command.requestHash());
    List<IntakeRoomCarryState.ObservedTargetSourceEvent> lineage =
        List.of(
            new IntakeRoomCarryState.ObservedTargetSourceEvent(
                "intake-observed-target-source-event.v1",
                new TargetIntakeSourceEventRef(
                    TargetIntakeSourceEventRef.SCHEMA_VERSION,
                    "EVENT_PROJECTION_READY_14",
                    14,
                    TargetIntakeSourceEventRef.INTAKE_PROJECTION_READY,
                    TENANT,
                    CASE_ID,
                    RoomType.INTAKE,
                    EPOCH,
                    FENCE,
                    "e".repeat(64))),
            new IntakeRoomCarryState.ObservedTargetSourceEvent(
                "intake-observed-target-source-event.v1",
                new TargetIntakeSourceEventRef(
                    TargetIntakeSourceEventRef.SCHEMA_VERSION,
                    "EVENT_ROOM_MESSAGE_15",
                    15,
                    TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED,
                    TENANT,
                    CASE_ID,
                    RoomType.INTAKE,
                    EPOCH,
                    FENCE,
                    "f".repeat(64))));
    return new IntakeRoomCarryState(
        "intake-room-carry-state.v3",
        IntakeRoomPhase.OPEN,
        IntakeParty.INITIATOR,
        command.sequence() + 1,
        16,
        1,
        0,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        retainedReasonCode,
        1,
        rejected,
        List.of(
            new IntakeRoomCarryState.ObservedCommand(
                "intake-observed-command.v1", command, rejected)),
        List.of(),
        List.of(),
        null,
        lineage);
  }

  private static TargetIntakeCommandTerminalNoCommit terminalRecoveryAuthority(
      IntakeWorkflowCommand command,
      String roomWorkflowId,
      String firstRunId,
      String errorCode) {
    return terminalRecoveryAuthority(
        TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION,
        command,
        roomWorkflowId,
        firstRunId,
        errorCode);
  }

  private static TargetIntakeCommandTerminalNoCommit legacyTerminalRecoveryAuthority(
      IntakeWorkflowCommand command,
      String roomWorkflowId,
      String firstRunId,
      String errorCode) {
    return terminalRecoveryAuthority(
        TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION,
        command,
        roomWorkflowId,
        firstRunId,
        errorCode);
  }

  private static TargetIntakeCommandTerminalNoCommit terminalRecoveryAuthority(
      String schemaVersion,
      IntakeWorkflowCommand command,
      String roomWorkflowId,
      String firstRunId,
      String errorCode) {
    IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
    ExecuteAgentRunRequest request = target.request();
    boolean useV2Authority = TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(schemaVersion);
    return new TargetIntakeCommandTerminalNoCommit(
        schemaVersion,
        TENANT,
        CASE_ID,
        RoomType.INTAKE,
        EPOCH,
        FENCE,
        roomWorkflowId,
        firstRunId,
        CONTROL_BUILD,
        target.activationId(),
        target.activationManifestHash(),
        target.caseBuildId(),
        target.controlBuildId(),
        target.agentBuildId(),
        target.graphBindingHash(),
        target.graphCodeBuildId(),
        target.commandHash(),
        target.commandEnvelopeHash(),
        request.logicalInputHash(),
        useV2Authority ? request.command().requestHash() : null,
        command.commandId(),
        command.sequence(),
        command.requestHash(),
        request.command().eventRef().artifactId(),
        command.payloadRef(),
        command.payloadHash(),
        target.expectedProcessRevision(),
        target.expectedProcessRevision() + 1,
        target.expectedRoomRevision(),
        target.expectedRoomRevision() + 1,
        useV2Authority ? 13L : null,
        15,
        request.logicalRunId(),
        request.attemptId(),
        request.attemptId(),
        request.attemptNo(),
        AgentRunAttemptStatus.ABORTED,
        ExecuteAgentRunResult.Outcome.FAILED,
        errorCode,
        false,
        AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
        1,
        true,
        Instant.parse("2026-08-10T00:00:00Z"));
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }

  private static long formalEventSequence(IntakeWorkflowCommand command) {
    return switch (command.commandId()) {
      case GAP_FIRST_COMMAND_ID -> 2;
      case GAP_SECOND_COMMAND_ID -> 3;
      case LIFECYCLE_SECOND_COMMAND_ID -> 1;
      case THREE_ROUND_FIRST_COMMAND_ID -> 1;
      case THREE_ROUND_SECOND_COMMAND_ID -> 4;
      case THREE_ROUND_THIRD_COMMAND_ID -> 7;
      case FUTURE_CURSOR_FIRST_COMMAND_ID -> 1;
      case FUTURE_CURSOR_SECOND_COMMAND_ID -> 4;
      default -> {
        if (command.commandId().startsWith(TEN_ROUND_COMMAND_PREFIX)) {
          long round = Long.parseLong(command.commandId().substring(TEN_ROUND_COMMAND_PREFIX.length()));
          yield 1L + (round - 1L) * 3L;
        }
        yield command.sequence();
      }
    };
  }

  private enum WinningMismatch {
    NONE,
    ATTEMPT,
    COMMAND,
    GRAPH,
    OPERATION,
    RESULT,
    REVISION;

    private static WinningMismatch[] rejections() {
      return new WinningMismatch[] {ATTEMPT, COMMAND, GRAPH, OPERATION, RESULT, REVISION};
    }
  }

  private static final class LegacyTerminalNoCommitVersionInterceptor
      extends WorkerInterceptorBase {

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(
        WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          super.init(
              new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                @Override
                public int getVersion(String changeId, int minSupported, int maxSupported) {
                  if (TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V2_CHANGE_ID.equals(changeId)) {
                    return Workflow.DEFAULT_VERSION;
                  }
                  return super.getVersion(changeId, minSupported, maxSupported);
                }
              });
        }
      };
    }
  }

  private static final class V2TerminalNoCommitVersionInterceptor
      extends WorkerInterceptorBase {

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(
        WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          super.init(
              new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                @Override
                public int getVersion(String changeId, int minSupported, int maxSupported) {
                  if (TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V3_CHANGE_ID.equals(changeId)) {
                    return Workflow.DEFAULT_VERSION;
                  }
                  return super.getVersion(changeId, minSupported, maxSupported);
                }
              });
        }
      };
    }
  }

  @WorkflowInterface
  public interface TerminalReceiptSinkWorkflow {

    @WorkflowMethod(name = "Phase9TerminalReceiptSink")
    void run();

    @SignalMethod(name = CaseProcessWorkflowProtocol.TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL)
    void terminalNoCommit(TargetIntakeCommandTerminalNoCommit authority);

    @QueryMethod(name = "terminalNoCommitReceipts")
    List<TargetIntakeCommandTerminalNoCommit> receipts();
  }

  public static final class TerminalReceiptSinkWorkflowImpl
      implements TerminalReceiptSinkWorkflow {
    private final List<TargetIntakeCommandTerminalNoCommit> receipts = new ArrayList<>();

    private static void reset() {}

    @Override
    public void run() {
      Workflow.await(() -> false);
    }

    @Override
    public void terminalNoCommit(TargetIntakeCommandTerminalNoCommit authority) {
      TargetIntakeCommandTerminalNoCommit sameCommand =
          receipts.stream()
              .filter(existing -> existing.commandId().equals(authority.commandId()))
              .findFirst()
              .orElse(null);
      if (sameCommand == null) {
        receipts.add(authority);
        return;
      }
      if (TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
              sameCommand.schemaVersion())
          && TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
              authority.schemaVersion())
          && sameCommand.equals(authority.asObservedV2Authority())) {
        receipts.set(receipts.indexOf(sameCommand), authority);
        return;
      }
      if (!sameCommand.equals(authority)) {
        throw ApplicationFailure.newNonRetryableFailure(
            "terminal-no-commit signal authority changed", "TerminalNoCommitSignalConflict");
      }
    }

    @Override
    public List<TargetIntakeCommandTerminalNoCommit> receipts() {
      return List.copyOf(receipts);
    }
  }

  @WorkflowInterface
  public interface LegacyTerminalNoCommitBootstrapWorkflow {

    @WorkflowMethod(name = "Phase9LegacyTerminalNoCommitBootstrap")
    void run(IntakeRoomStart start);
  }

  public static final class LegacyTerminalNoCommitBootstrapWorkflowImpl
      implements LegacyTerminalNoCommitBootstrapWorkflow {

    @Override
    public void run(IntakeRoomStart start) {
      IntakeRoomWorkflow continued = Workflow.newContinueAsNewStub(IntakeRoomWorkflow.class);
      continued.run(start);
    }
  }

  public static final class AbortedAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();

    private static void reset() {
      requests.clear();
    }

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          ExecuteAgentRunResult.Outcome.FAILED,
          null,
          null,
          1,
          true,
          "GRAPH_STREAM_PROTOCOL_REJECTED",
          false,
          AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}
  }

  public static final class FinalizationRejectedThenCompletedAgentRunWorkflow
      implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();

    private static void reset() {
      requests.clear();
    }

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      if (LIFECYCLE_FIRST_COMMAND_ID.equals(request.command().commandId())) {
        throw ApplicationFailure.newNonRetryableFailure(
            "READY_TO_CONFIRM cannot freeze an unresolved respondent placeholder",
            "INTAKE_RESPONDENT_MATRIX_NOT_READY");
      }
      RoomGraphResult graph = RecordingAgentRunWorkflow.graphResult(request);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          ExecuteAgentRunResult.Outcome.COMPLETED,
          graph,
          graph.outputHash(),
          1,
          true,
          null,
          false,
          null,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}
  }

  private static final class AcknowledgedTerminalNoCommitActivities
      implements CaseCommandLifecycleActivities {
    private static final AtomicInteger resolves = new AtomicInteger();
    private static final AtomicInteger convergences = new AtomicInteger();
    private static volatile boolean rejectConvergence;

    private static void reset() {
      resolves.set(0);
      convergences.set(0);
      rejectConvergence = false;
    }

    private static void rejectConvergence() {
      rejectConvergence = true;
    }

    @Override
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
      throw new AssertionError("terminal-no-commit acknowledgement must not expire a command");
    }

    @Override
    public RecordCaseCommandRoutedResult recordCaseCommandRouted(
        RecordCaseCommandRouted request) {
      throw new AssertionError("terminal-no-commit acknowledgement must not route a command");
    }

    @Override
    public RecordCaseCommandRoutedResult completeCaseCommandRouting(
        RecordCaseCommandRouted request) {
      throw new AssertionError(
          "terminal-no-commit acknowledgement must not complete command routing");
    }

    @Override
    public CaseCommandLifecycleActivities.ConvergeTargetEvidenceTerminalNoCommitResult
        convergeTargetEvidenceTerminalNoCommit(
            CaseCommandLifecycleActivities.ConvergeTargetEvidenceTerminalNoCommit request) {
      throw new AssertionError(
          "Intake terminal-no-commit acknowledgement must not converge Evidence");
    }

    @Override
    public CaseCommandLifecycleActivities.ResolveTargetEvidenceTerminalNoCommitResult
        resolveTargetEvidenceTerminalNoCommit(
            CaseCommandLifecycleActivities.ResolveTargetEvidenceTerminalNoCommit request) {
      throw new AssertionError(
          "Intake terminal-no-commit acknowledgement must not resolve Evidence");
    }

    @Override
    public CaseCommandLifecycleActivities.RecoverExpiredTargetEvidenceTerminalNoCommitResult
        recoverExpiredTargetEvidenceTerminalNoCommit(
            CaseCommandLifecycleActivities.RecoverExpiredTargetEvidenceTerminalNoCommit request) {
      throw new AssertionError(
          "Intake terminal-no-commit acknowledgement must not recover Evidence");
    }

    @Override
    public ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
        ResolveTargetIntakeTerminalNoCommit request) {
      resolves.incrementAndGet();
      if (!ResolveTargetIntakeTerminalNoCommit.V2_SCHEMA_VERSION.equals(
          request.schemaVersion())) {
        throw ApplicationFailure.newNonRetryableFailure(
            "strict v3 resolution is required", "TerminalNoCommitResolveVersionMismatch");
      }
      TargetIntakeCommandTerminalNoCommit observed = request.authority();
      List<TargetIntakeSourceEventRef> observations = request.observedCaseEvents();
      long projectionPreCursor =
          observations.isEmpty()
              ? observed.expectedLastCaseEventSequence()
              : observations.getFirst().eventSequence() - 1L;
      List<TargetIntakeSourceEventRef> lineage =
          observations.stream()
              .filter(event -> event.eventSequence() > projectionPreCursor)
              .toList();
      TargetIntakeCommandTerminalNoCommit resolved =
          observed.withProjectionLineage(
              projectionPreCursor, observed.lastCaseEventSequence(), lineage);
      return new ResolveTargetIntakeTerminalNoCommitResult(
          ResolveTargetIntakeTerminalNoCommitResult.V2_SCHEMA_VERSION,
          resolved,
          resolved.receiptUri(),
          resolved.receiptSha256(),
          CaseProcessWorkflowProtocol.caseWorkflowId(
              resolved.tenantSurrogate(), resolved.caseId()),
          "case-run-acknowledged-v3",
          resolved.caseBuildId());
    }

    @Override
    public ConvergeTargetIntakeTerminalNoCommitResult convergeTargetIntakeTerminalNoCommit(
        ConvergeTargetIntakeTerminalNoCommit request) {
      convergences.incrementAndGet();
      if (rejectConvergence) {
        throw ApplicationFailure.newNonRetryableFailure(
            "locked projection cursor drifted",
            "TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE");
      }
      TargetIntakeCommandTerminalNoCommit authority = request.authority();
      return new ConvergeTargetIntakeTerminalNoCommitResult(
          "converge-target-intake-terminal-no-commit-result.v1",
          TerminalNoCommitOutcome.TERMINALIZED,
          authority,
          authority.receiptUri(),
          authority.receiptSha256(),
          authority.newProcessRevision(),
          authority.newRoomRevision(),
          authority.caseCommandSequence(),
          authority.newProjectionLastCaseEventSequence());
    }
  }

  private static final class RecoveryAuthorityReads implements CaseCommandLifecycleActivities {
    private static final AtomicInteger invocations = new AtomicInteger();
    private static final AtomicInteger convergences = new AtomicInteger();
    private static volatile TargetIntakeCommandTerminalNoCommit expected;
    private static volatile boolean rejectNext;

    private static void reset() {
      invocations.set(0);
      convergences.set(0);
      expected = null;
      rejectNext = false;
    }

    private static void rejectNext() {
      rejectNext = true;
    }

    @Override
    public CaseCommandLifecycleActivities.ExpireCaseCommandResult expireCaseCommand(
        CaseCommandLifecycleActivities.ExpireCaseCommand request) {
      throw new AssertionError("terminal-no-commit recovery must not expire a command");
    }

    @Override
    public CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult recordCaseCommandRouted(
        CaseCommandLifecycleActivities.RecordCaseCommandRouted request) {
      throw new AssertionError("terminal-no-commit recovery must not route a command");
    }

    @Override
    public CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult completeCaseCommandRouting(
        CaseCommandLifecycleActivities.RecordCaseCommandRouted request) {
      throw new AssertionError("terminal-no-commit recovery must not complete routing");
    }

    @Override
    public CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult
        convergeTargetIntakeTerminalNoCommit(
            CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit request) {
      TargetIntakeCommandTerminalNoCommit authority = request.authority();
      if (expected == null
          || !TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
              authority.schemaVersion())
          || !expected.equals(authority.asObservedV2Authority())) {
        throw new IllegalArgumentException("terminal-no-commit convergence authority drifted");
      }
      convergences.incrementAndGet();
      return new ConvergeTargetIntakeTerminalNoCommitResult(
          "converge-target-intake-terminal-no-commit-result.v1",
          TerminalNoCommitOutcome.TERMINALIZED,
          authority,
          authority.receiptUri(),
          authority.receiptSha256(),
          authority.newProcessRevision(),
          authority.newRoomRevision(),
          authority.caseCommandSequence(),
          authority.newProjectionLastCaseEventSequence());
    }

    @Override
    public ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
        ResolveTargetIntakeTerminalNoCommit request) {
      invocations.incrementAndGet();
      if (expected == null || !expected.equals(request.authority())) {
        throw new IllegalArgumentException("terminal-no-commit recovery authority drifted");
      }
      if (rejectNext) {
        rejectNext = false;
        throw ApplicationFailure.newNonRetryableFailure(
            "terminal-no-commit source coordinates drifted",
            "TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE");
      }
      if (ResolveTargetIntakeTerminalNoCommit.V2_SCHEMA_VERSION.equals(
          request.schemaVersion())) {
        List<TargetIntakeSourceEventRef> observations = request.observedCaseEvents();
        long projectionPreCursor = observations.getFirst().eventSequence() - 1L;
        TargetIntakeCommandTerminalNoCommit resolved =
            expected.withProjectionLineage(
                projectionPreCursor,
                expected.lastCaseEventSequence(),
                observations.stream()
                    .filter(event -> event.eventSequence() > projectionPreCursor)
                    .toList());
        return new ResolveTargetIntakeTerminalNoCommitResult(
            ResolveTargetIntakeTerminalNoCommitResult.V2_SCHEMA_VERSION,
            resolved,
            resolved.receiptUri(),
            resolved.receiptSha256(),
            CaseProcessWorkflowProtocol.caseWorkflowId(
                resolved.tenantSurrogate(), resolved.caseId()),
            "case-run-historical-recovery-v3",
            resolved.caseBuildId());
      }
      return new ResolveTargetIntakeTerminalNoCommitResult(
          "resolve-target-intake-terminal-no-commit-result.v1",
          expected,
          expected.receiptUri(),
          expected.receiptSha256());
    }
  }

  public static final class RecordingAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      RoomGraphResult graph = graphResult(request);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          ExecuteAgentRunResult.Outcome.COMPLETED,
          graph,
          RESULT_HASH,
          1,
          true,
          null,
          false,
          null,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}

    private static RoomGraphResult graphResult(ExecuteAgentRunRequest request) {
      return new RoomGraphResult(
          "room-graph-result.v1",
          request.command().commandId(),
          request.logicalRunId(),
          request.attemptId(),
          request.command().graphKey(),
          request.command().graphVersion(),
          "CHECKPOINT_P9_CHILD",
          1,
          GraphStatus.COMPLETED,
          List.of(),
          List.of(),
          null,
          null,
          null,
          RESULT_HASH,
          new Usage(10, 5, 15),
          new RoomGraphResult.ExecutionMetadata(
              "p9-prompt-v1",
              "p9-model-v1",
              "production-runtime-intake-output.v1",
              "p9-policy-v1",
              "p9-guardrail-v1"));
    }
  }

  public static final class WinningAttemptAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();
    private static final List<ExecuteAgentRunResult> results = new CopyOnWriteArrayList<>();
    private static final AtomicInteger invocations = new AtomicInteger();

    private static void reset() {
      requests.clear();
      results.clear();
      invocations.set(0);
    }

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      ExecuteAgentRunResult result =
          invocations.getAndIncrement() == 0 ? winningResult(request) : normalResult(request);
      results.add(result);
      return result;
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}

    private static ExecuteAgentRunResult winningResult(ExecuteAgentRunRequest request) {
      RoomGraphResult graph =
          graphResult(request, WINNING_GRAPH_COMMAND_ID, WINNING_ATTEMPT_ID, WINNING_RESULT_HASH);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          WINNING_ATTEMPT_ID,
          2,
          ExecuteAgentRunResult.Outcome.COMPLETED,
          graph,
          WINNING_RESULT_HASH,
          1,
          true,
          null,
          false,
          null,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    private static ExecuteAgentRunResult normalResult(ExecuteAgentRunRequest request) {
      RoomGraphResult graph = RecordingAgentRunWorkflow.graphResult(request);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          ExecuteAgentRunResult.Outcome.COMPLETED,
          graph,
          RESULT_HASH,
          1,
          true,
          null,
          false,
          null,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    private static RoomGraphResult graphResult(
        ExecuteAgentRunRequest request,
        String graphCommandId,
        String attemptId,
        String resultHash) {
      return new RoomGraphResult(
          "room-graph-result.v1",
          graphCommandId,
          request.logicalRunId(),
          attemptId,
          request.command().graphKey(),
          request.command().graphVersion(),
          "CHECKPOINT_P9_WINNING",
          1,
          GraphStatus.COMPLETED,
          List.of(),
          List.of(),
          null,
          null,
          null,
          resultHash,
          new Usage(10, 5, 15),
          new RoomGraphResult.ExecutionMetadata(
              "p9-prompt-v1",
              "p9-model-v1",
              "production-runtime-intake-output.v1",
              "p9-policy-v1",
              "p9-guardrail-v1"));
    }
  }

  public static final class FailsFirstAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();
    private static final AtomicInteger invocations = new AtomicInteger();

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      if (invocations.incrementAndGet() == 1) {
        throw ApplicationFailure.newNonRetryableFailure(
            "synthetic post-commit acknowledgement loss", "SyntheticPostCommitLoss");
      }
      RoomGraphResult graph = RecordingAgentRunWorkflow.graphResult(request);
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          ExecuteAgentRunResult.Outcome.COMPLETED,
          graph,
          RESULT_HASH,
          1,
          true,
          null,
          false,
          null,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}
  }

  public static final class LifecycleAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();
    private static final List<ExecuteAgentRunResult> results = new CopyOnWriteArrayList<>();
    private static final AtomicInteger invocations = new AtomicInteger();
    private static volatile ExecuteAgentRunResult.Outcome firstOutcome;

    private static void reset(ExecuteAgentRunResult.Outcome outcome) {
      requests.clear();
      results.clear();
      invocations.set(0);
      firstOutcome = outcome;
    }

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      boolean firstCommand =
          requests.isEmpty()
              || requests.getFirst().command().commandId().equals(request.command().commandId());
      invocations.incrementAndGet();
      ExecuteAgentRunResult.Outcome outcome =
          firstCommand ? firstOutcome : ExecuteAgentRunResult.Outcome.COMPLETED;
      ExecuteAgentRunResult result = result(request, outcome);
      results.add(result);
      return result;
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}

    private static ExecuteAgentRunResult result(
        ExecuteAgentRunRequest request, ExecuteAgentRunResult.Outcome outcome) {
      return new ExecuteAgentRunResult(
          ExecuteAgentRunResult.SCHEMA_VERSION,
          request.agentRunId(),
          request.logicalRunId(),
          request.attemptId(),
          request.attemptNo(),
          outcome,
          outcome == ExecuteAgentRunResult.Outcome.COMPLETED
              ? RecordingAgentRunWorkflow.graphResult(request)
              : null,
          outcome == ExecuteAgentRunResult.Outcome.COMPLETED ? RESULT_HASH : null,
          1,
          outcome == ExecuteAgentRunResult.Outcome.COMPLETED,
          outcome == ExecuteAgentRunResult.Outcome.COMPLETED ? null : "NON_COMPLETED_CHILD",
          false,
          outcome == ExecuteAgentRunResult.Outcome.COMPLETED
              ? null
              : AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
          Instant.ofEpochMilli(Workflow.currentTimeMillis()));
    }

  }

  public static final class BlockingLifecycleAgentRunWorkflow implements AgentRunWorkflow {
    private static final List<ExecuteAgentRunRequest> requests = new CopyOnWriteArrayList<>();

    private static void reset() {
      requests.clear();
    }

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
      requests.add(request);
      Workflow.sleep(Duration.ofDays(1));
      throw new AssertionError("blocking AgentRun child unexpectedly resumed");
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
      return run(request);
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {}
  }

  private static final class WinningAttemptFinalizationReads
      implements IntakeAgentRunFinalizationReadActivities {
    private final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();
    private final List<IntakeDomainEventRef> committedEvents = new CopyOnWriteArrayList<>();
    private final WinningMismatch mismatch;

    private WinningAttemptFinalizationReads(WinningMismatch mismatch) {
      this.mismatch = mismatch;
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
      boolean winning = WINNING_FIRST_COMMAND_ID.equals(request.command().commandId());
      IntakeAgentRunFinalizationReadResult result =
          finalizationResult(request, winning ? mismatch : WinningMismatch.NONE, winning);
      if (result.resolution() == IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED) {
        committedEvents.add(result.receipt().committedEvent());
      }
      return result;
    }

    private static IntakeAgentRunFinalizationReadResult finalizationResult(
        IntakeAgentRunFinalizationReadRequest request,
        WinningMismatch mismatch,
        boolean winning) {
      IntakeWorkflowCommand command = request.command();
      IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
      ExecuteAgentRunRequest agentRequest = target.request();
      String formalCommandId = winning ? WINNING_FORMAL_COMMAND_ID : command.commandId();
      String attemptId = winning ? WINNING_ATTEMPT_ID : agentRequest.attemptId();
      String resultHash = winning ? WINNING_RESULT_HASH : RESULT_HASH;
      String proposalHash = winning ? WINNING_PROPOSAL_HASH : PROPOSAL_HASH;
      long revision = winning ? 1 : target.expectedProcessRevision() + 1;
      long eventSequence = winning ? 2 : command.sequence() + 1;

      switch (mismatch) {
        case ATTEMPT -> attemptId = agentRequest.attemptId();
        case COMMAND -> formalCommandId = command.commandId();
        case GRAPH, NONE, OPERATION, RESULT, REVISION -> {}
      }
      if (mismatch == WinningMismatch.REVISION) {
        revision = 2;
      }

      String graphCommandId =
          winning ? WINNING_GRAPH_COMMAND_ID : command.commandId();
      if (mismatch == WinningMismatch.GRAPH) {
        graphCommandId = WINNING_WRONG_GRAPH_COMMAND_ID;
      }
      String operationKey =
          IntakeOperationKeys.turnFinalize(
              CASE_ID, EPOCH, THREAD_ID, formalCommandId, resultHash);
      String locatorOperationKey = operationKey;
      String locatorResultHash = resultHash;
      if (mismatch == WinningMismatch.OPERATION) {
        locatorOperationKey =
            IntakeOperationKeys.turnFinalize(
                CASE_ID, EPOCH, THREAD_ID, WINNING_WRONG_COMMAND_ID, resultHash);
      }
      if (mismatch == WinningMismatch.RESULT) {
        locatorResultHash = WINNING_WRONG_RESULT_HASH;
      }

      String eventId =
          "EVENT_P9_WINNING_" + command.commandId().replace(':', '_') + "_" + mismatch.name();
      IntakeAgentRunRef agentRun =
          new IntakeAgentRunRef(
              "intake-agent-run-ref.v1", agentRequest.logicalRunId(), attemptId, resultHash);
      IntakeGraphExecutionRef graph =
          new IntakeGraphExecutionRef(
              "intake-graph-execution-ref.v1",
              THREAD_ID,
              graphCommandId,
              agentRequest.command().graphKey(),
              GRAPH_VERSION,
              "CHECKPOINT_P9_WINNING",
              "urn:after-sale-flow:graph-result:p9-winning",
              resultHash,
              "urn:after-sale-flow:intake-proposal:p9-winning",
              proposalHash);
      OperationReceipt operation =
          new OperationReceipt(
              "intake-operation-receipt.v1",
              operationKey,
              command.requestHash(),
              resultHash,
              revision,
              revision);
      IntakeDomainEventRef event =
          new IntakeDomainEventRef(
              "intake-domain-event-ref.v1",
              eventId,
              "urn:after-sale-flow:intake-event:" + eventId,
              hash(5),
              eventSequence,
              IntakeDomainEventType.TURN_READY_TO_CONFIRM,
              IntakeParty.INITIATOR,
              formalCommandId,
              TENANT,
              CASE_ID,
              EPOCH,
              FENCE,
              INITIATOR_SCOPE,
              operationKey,
              command.requestHash(),
              resultHash,
              revision,
              revision,
              agentRun,
              graph);
      FormalFinalizationReceipt formal =
          new FormalFinalizationReceipt(
              "intake-finalization-receipt.v1",
              operationKey,
              TENANT,
              CASE_ID,
              EPOCH,
              THREAD_ID,
              INITIATOR_SCOPE,
              AGENT_SESSION_ID,
              formalCommandId,
              agentRequest.logicalRunId(),
              attemptId,
              resultHash,
              proposalHash,
              revision,
              revision,
              FENCE,
              "MESSAGE_P9_WINNING_" + formalCommandId.replace(':', '_'),
              1L,
              null,
              List.of(event.eventId()),
              List.of("OUTBOX_P9_WINNING"),
              "COMMITTED",
              "2026-07-28T00:00:00Z",
              hash(6));
      TurnFinalizationReceipt receipt =
          new TurnFinalizationReceipt(
              "intake-turn-finalization-activity-receipt.v1", operation, formal, event);
      FinalizationLocator locator =
          new FinalizationLocator(
              "intake-agent-run-finalization-locator.v1",
              IntakeTargetAgentRunContext.TARGET_LANE,
              ACTIVATION_ID,
              target.activationManifestHash(),
              FENCE,
              agentRequest.logicalRunId(),
              attemptId,
              locatorResultHash,
              proposalHash,
              "CHECKPOINT_P9_WINNING",
              locatorOperationKey,
              "MANIFEST_P9_WINNING",
              hash(9),
              hash(1),
              hash(2));
      return new IntakeAgentRunFinalizationReadResult(
          "intake-agent-run-finalization-read-result.v1",
          IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED,
          locator,
          receipt);
    }
  }

  private static final class RecoverableWinningAttemptFinalizationReads
      implements IntakeAgentRunFinalizationReadActivities {
    private final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();
    private volatile boolean committedReceiptAvailable;
    private volatile IntakeAgentRunFinalizationReadResult committedReceipt;

    private void allowCommittedReceipt() {
      committedReceiptAvailable = true;
    }

    private void allowCommittedReceipt(IntakeAgentRunFinalizationReadResult committedReceipt) {
      this.committedReceipt = committedReceipt;
      committedReceiptAvailable = true;
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
      if (!committedReceiptAvailable) {
        return new IntakeAgentRunFinalizationReadResult(
            "intake-agent-run-finalization-read-result.v1",
            IntakeAgentRunFinalizationReadResult.Resolution.PENDING,
            null,
            null);
      }
      if (committedReceipt != null) {
        return committedReceipt;
      }
      return WinningAttemptFinalizationReads.finalizationResult(
          request, WinningMismatch.NONE, true);
    }
  }

  private static final class BlockingFinalizationReads
      implements IntakeAgentRunFinalizationReadActivities {
    private static volatile CountDownLatch started = new CountDownLatch(1);
    private static volatile CountDownLatch released = new CountDownLatch(1);
    private final RecordingFinalizationReads delegate = new RecordingFinalizationReads();

    private static void reset() {
      started = new CountDownLatch(1);
      released = new CountDownLatch(1);
    }

    private static boolean awaitStarted() {
      try {
        return started.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while awaiting finalization read", interrupted);
      }
    }

    private static void release() {
      released.countDown();
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      started.countDown();
      try {
        if (!released.await(30, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out awaiting finalization release");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while blocking finalization read", interrupted);
      }
      return delegate.readFinalization(request);
    }
  }

  private static final class FinalizationRejectedReads
      implements IntakeAgentRunFinalizationReadActivities {
    private static final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();
    private final RecordingFinalizationReads committed = new RecordingFinalizationReads();

    private static void reset() {
      requests.clear();
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
      if (!LIFECYCLE_FIRST_COMMAND_ID.equals(request.command().commandId())) {
        return committed.readFinalization(request);
      }
      return terminalNoCommitResult(request);
    }

    private static IntakeAgentRunFinalizationReadResult terminalNoCommitResult(
        IntakeAgentRunFinalizationReadRequest request) {
      ExecuteAgentRunRequest agentRequest =
          request.command().executionContext().targetAgentRun().request();
      RoomGraphResult graph = RecordingAgentRunWorkflow.graphResult(agentRequest);
      Instant completedAt = Instant.parse("2026-08-10T16:07:06.400Z");
      ExecuteAgentRunResult completedAudit =
          new ExecuteAgentRunResult(
              ExecuteAgentRunResult.SCHEMA_VERSION,
              agentRequest.agentRunId(),
              agentRequest.logicalRunId(),
              agentRequest.attemptId(),
              agentRequest.attemptNo(),
              ExecuteAgentRunResult.Outcome.COMPLETED,
              graph,
              graph.outputHash(),
              1,
              true,
              null,
              false,
              null,
              completedAt);
      TerminalNoCommitEvidence evidence =
          new TerminalNoCommitEvidence(
              TerminalNoCommitEvidence.SCHEMA_VERSION,
              agentRequest.logicalRunId(),
              agentRequest.attemptId(),
              agentRequest.attemptId(),
              agentRequest.attemptNo(),
              AgentRunAttemptStatus.ABORTED,
              "FINALIZATION_REJECTED",
              "UNCOMMITTED",
              "INTAKE_RESPONDENT_MATRIX_NOT_READY",
              2,
              completedAudit);
      return new IntakeAgentRunFinalizationReadResult(
          IntakeAgentRunFinalizationReadResult.SCHEMA_VERSION,
          IntakeAgentRunFinalizationReadResult.Resolution.TERMINAL_NO_COMMIT,
          null,
          null,
          evidence);
    }
  }

  private static final class PendingFinalizationRejectedReads
      implements IntakeAgentRunFinalizationReadActivities {
    private static final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();
    private static volatile boolean terminalEvidenceEnabled;

    private static void reset() {
      requests.clear();
      terminalEvidenceEnabled = false;
    }

    private static void enableTerminalEvidence() {
      terminalEvidenceEnabled = true;
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
      if (!terminalEvidenceEnabled) {
        return new IntakeAgentRunFinalizationReadResult(
            IntakeAgentRunFinalizationReadResult.LEGACY_SCHEMA_VERSION,
            IntakeAgentRunFinalizationReadResult.Resolution.PENDING,
            null,
            null);
      }
      return FinalizationRejectedReads.terminalNoCommitResult(request);
    }
  }

  private static final class RecordingFinalizationReads
      implements IntakeAgentRunFinalizationReadActivities {
    private static final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();
    private static final AtomicInteger pendingResponses = new AtomicInteger();
    private final List<IntakeDomainEventRef> committedEvents = new CopyOnWriteArrayList<>();
    private final IntakeDomainEventType formalEventType;
    private final IntakeAgentRunFinalizationReadResult.Resolution firstResolution;
    private final IntakeAgentRunFinalizationReadResult.Resolution subsequentResolution;

    private RecordingFinalizationReads() {
      this(IntakeDomainEventType.TURN_READY_TO_CONFIRM, null, null);
    }

    private RecordingFinalizationReads(IntakeDomainEventType formalEventType) {
      this(formalEventType, null, null);
    }

    private RecordingFinalizationReads(
        IntakeAgentRunFinalizationReadResult.Resolution firstResolution) {
      this(IntakeDomainEventType.TURN_READY_TO_CONFIRM, firstResolution, null);
    }

    private RecordingFinalizationReads(
        IntakeAgentRunFinalizationReadResult.Resolution firstResolution,
        IntakeAgentRunFinalizationReadResult.Resolution subsequentResolution) {
      this(
          IntakeDomainEventType.TURN_READY_TO_CONFIRM,
          firstResolution,
          subsequentResolution);
    }

    private RecordingFinalizationReads(
        IntakeDomainEventType formalEventType,
        IntakeAgentRunFinalizationReadResult.Resolution firstResolution,
        IntakeAgentRunFinalizationReadResult.Resolution subsequentResolution) {
      this.formalEventType = formalEventType;
      this.firstResolution = firstResolution;
      this.subsequentResolution = subsequentResolution;
    }

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
      if (pendingResponses.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
        return new IntakeAgentRunFinalizationReadResult(
            "intake-agent-run-finalization-read-result.v1",
            IntakeAgentRunFinalizationReadResult.Resolution.PENDING,
            null,
            null);
      }
      IntakeAgentRunFinalizationReadResult.Resolution scriptedResolution =
          requests.size() == 1 ? firstResolution : subsequentResolution;
      if (scriptedResolution == IntakeAgentRunFinalizationReadResult.Resolution.ABSENT_TERMINAL) {
        return new IntakeAgentRunFinalizationReadResult(
            "intake-agent-run-finalization-read-result.v1",
            scriptedResolution,
            null,
            null);
      }
      IntakeWorkflowCommand command = request.command();
      IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
      ExecuteAgentRunRequest agentRequest = target.request();
      long committedRevision = target.expectedProcessRevision() + 1;
      String eventId = "EVENT_" + command.commandId().replace(':', '_');
      String operationKey =
          IntakeOperationKeys.turnFinalize(
              CASE_ID, EPOCH, THREAD_ID, command.commandId(), RESULT_HASH);
      IntakeAgentRunRef agentRun =
          new IntakeAgentRunRef(
              "intake-agent-run-ref.v1",
              agentRequest.logicalRunId(),
              agentRequest.attemptId(),
              RESULT_HASH);
      IntakeGraphExecutionRef graph =
          new IntakeGraphExecutionRef(
              "intake-graph-execution-ref.v1",
              THREAD_ID,
              command.commandId(),
              agentRequest.command().graphKey(),
              GRAPH_VERSION,
              "CHECKPOINT_P9_CHILD",
              "urn:after-sale-flow:graph-result:p9-child",
              RESULT_HASH,
              "urn:after-sale-flow:intake-proposal:p9-child",
              PROPOSAL_HASH);
      OperationReceipt operation =
          new OperationReceipt(
              "intake-operation-receipt.v1",
              operationKey,
              command.requestHash(),
              RESULT_HASH,
              committedRevision,
              committedRevision);
      IntakeDomainEventRef event =
          new IntakeDomainEventRef(
              "intake-domain-event-ref.v1",
              eventId,
              "urn:after-sale-flow:intake-event:" + eventId,
              hash(5),
              formalEventSequence(command),
              formalEventType,
              IntakeParty.INITIATOR,
              command.commandId(),
              TENANT,
              CASE_ID,
              EPOCH,
              FENCE,
              INITIATOR_SCOPE,
              operationKey,
              command.requestHash(),
              RESULT_HASH,
              committedRevision,
              committedRevision,
              agentRun,
              graph);
      committedEvents.add(event);
      FormalFinalizationReceipt formal =
          new FormalFinalizationReceipt(
              "intake-finalization-receipt.v1",
              operationKey,
              TENANT,
              CASE_ID,
              EPOCH,
              THREAD_ID,
              INITIATOR_SCOPE,
              AGENT_SESSION_ID,
              command.commandId(),
              agentRequest.logicalRunId(),
              agentRequest.attemptId(),
              RESULT_HASH,
              PROPOSAL_HASH,
              committedRevision,
              committedRevision,
              FENCE,
              "MESSAGE_" + command.commandId().replace(':', '_'),
              command.sequence(),
              null,
              List.of(event.eventId()),
              List.of("OUTBOX_P9_CHILD"),
              "COMMITTED",
              "2026-07-28T00:00:00Z",
              hash(6));
      TurnFinalizationReceipt receipt =
          new TurnFinalizationReceipt(
              "intake-turn-finalization-activity-receipt.v1", operation, formal, event);
      FinalizationLocator locator =
          new FinalizationLocator(
              "intake-agent-run-finalization-locator.v1",
              IntakeTargetAgentRunContext.TARGET_LANE,
              ACTIVATION_ID,
              target.activationManifestHash(),
              FENCE,
              agentRequest.logicalRunId(),
              agentRequest.attemptId(),
              RESULT_HASH,
              PROPOSAL_HASH,
              "CHECKPOINT_P9_CHILD",
              operationKey,
              "MANIFEST_P9_CHILD",
              hash(9),
              hash(1),
              hash(2));
      return new IntakeAgentRunFinalizationReadResult(
          "intake-agent-run-finalization-read-result.v1",
          scriptedResolution == null
              ? IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED
              : scriptedResolution,
          locator,
          receipt);
    }
  }

  private static final class SnapshotOnlyActivities implements IntakeRoomActivities {
    private final List<SnapshotPublicationRequest> snapshotRequests =
        new CopyOnWriteArrayList<>();
    private final List<GraphExecutionRequest> graphRequests = new CopyOnWriteArrayList<>();
    private final List<TurnFinalizationRequest> finalizationRequests =
        new CopyOnWriteArrayList<>();
    private final List<BranchCommitRequest> acceptRequests = new CopyOnWriteArrayList<>();
    private final List<BranchCommitReceipt> acceptReceipts = new CopyOnWriteArrayList<>();
    private final List<BranchCommitRequest> cancelRequests = new CopyOnWriteArrayList<>();
    private final List<BranchCommitReceipt> cancelReceipts = new CopyOnWriteArrayList<>();
    private final long branchEventSequenceOffset;

    private SnapshotOnlyActivities() {
      this(0);
    }

    private SnapshotOnlyActivities(long branchEventSequenceOffset) {
      this.branchEventSequenceOffset = branchEventSequenceOffset;
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
      snapshotRequests.add(request);
      return new SnapshotPublicationReceipt(
          "intake-snapshot-publication-receipt.v1",
          new OperationReceipt(
              "intake-operation-receipt.v1",
              request.operationKey(),
              request.requestHash(),
              hash(9),
              0,
              0),
          new ImmutablePayloadRef(
              "immutable-payload-ref.v1",
              "SNAPSHOT_P9_CHILD",
              "INTAKE_SNAPSHOT",
              "intake-domain-snapshot.v2",
              "urn:after-sale-flow:intake-snapshot:p9-child",
              "VERSION_P9_CHILD",
              hash(9),
              1024),
          0);
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
      graphRequests.add(request);
      throw new AssertionError("target path must not execute the legacy Graph Activity");
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
      finalizationRequests.add(request);
      throw new AssertionError("target path must not execute the legacy Finalizer Activity");
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
      acceptRequests.add(request);
      BranchCommitReceipt receipt = branchReceipt(request);
      acceptReceipts.add(receipt);
      return receipt;
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
      cancelRequests.add(request);
      BranchCommitReceipt receipt = branchReceipt(request, IntakeDomainEventType.CANCELLED);
      cancelReceipts.add(receipt);
      return receipt;
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
      throw new UnsupportedOperationException();
    }

    private BranchCommitReceipt branchReceipt(BranchCommitRequest request) {
      return branchReceipt(request, IntakeDomainEventType.INITIATOR_ACCEPTED);
    }

    private BranchCommitReceipt branchReceipt(
        BranchCommitRequest request, IntakeDomainEventType eventType) {
      long eventSequence = request.envelope().commandSequence() + branchEventSequenceOffset;
      long revision =
          Math.max(request.envelope().processRevision(), request.envelope().roomRevision()) + 1;
      String resultHash = hash(9);
      String eventId = "EVENT_" + request.envelope().commandId().replace(':', '_');
      IntakeDomainEventRef event =
          new IntakeDomainEventRef(
              "intake-domain-event-ref.v1",
              eventId,
              "urn:after-sale-flow:intake-event:" + eventId,
              hash(eventSequence + 2),
              eventSequence,
              eventType,
              request.envelope().party(),
              request.envelope().commandId(),
              request.envelope().tenantSurrogate(),
              request.envelope().caseId(),
              request.envelope().roomEpoch(),
              request.envelope().fencingToken(),
              request.envelope().actorScopeHash(),
              request.operationKey(),
              request.requestHash(),
              resultHash,
              revision,
              revision,
              null,
              null);
      return new BranchCommitReceipt(
          "intake-branch-commit-receipt.v1",
          request.operation(),
          new OperationReceipt(
              "intake-operation-receipt.v1",
              request.operationKey(),
              request.requestHash(),
              resultHash,
              revision,
              revision),
          event);
    }
  }
}
