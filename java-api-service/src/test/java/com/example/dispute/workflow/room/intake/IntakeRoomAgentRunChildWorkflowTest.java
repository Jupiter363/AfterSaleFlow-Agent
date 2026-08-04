package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
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
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult.FinalizationLocator;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
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
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
      environment.sleep(Duration.ofSeconds(1));

      IntakeRoomSnapshot released = workflow.state();
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
    for (int attempt = 0; attempt < 20; attempt++) {
      IntakeRoomSnapshot state = workflow.state();
      if (predicate.test(state)) {
        return state;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError("workflow did not reach the expected state");
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
        "target-e2e-intake-output.v1",
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
        "all-rooms-prompt.target-e2e.v1",
        "target-e2e.contract-blocked",
        "target-e2e-intake-output.v1",
        "all-rooms-policy.target-e2e.v1",
        "all-rooms-guardrail.target-e2e.v1",
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
                "target-e2e-intake-message.v1",
                command.payloadRef(),
                command.payloadHash(),
                128),
            new RoomGraphCommand.InvocationContext(
                "p9-intake-agent",
                "p9-prompt-v1",
                "p9-model-v1",
                "target-e2e-intake-output.v1",
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
        "agent-stream.v2",
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

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
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
              "target-e2e-intake-output.v1",
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
              "target-e2e-intake-output.v1",
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
               switch (command.commandId()) {
                 case GAP_FIRST_COMMAND_ID -> 2;
                 case GAP_SECOND_COMMAND_ID -> 3;
                 case LIFECYCLE_SECOND_COMMAND_ID -> 1;
                 default -> command.sequence();
               },
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
