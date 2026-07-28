package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
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
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

  @Test
  void targetMessageRunsNativeAgentRunChildThenReadsCommittedReceipt() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String roomQueue = "phase9-intake-child-room";
      SnapshotOnlyActivities snapshotActivities = new SnapshotOnlyActivities();
      RecordingAgentRunWorkflow.requests.clear();
      RecordingFinalizationReads.requests.clear();

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

  private static final class RecordingFinalizationReads
      implements IntakeAgentRunFinalizationReadActivities {
    private static final List<IntakeAgentRunFinalizationReadRequest> requests =
        new CopyOnWriteArrayList<>();

    @Override
    public IntakeAgentRunFinalizationReadResult readFinalization(
        IntakeAgentRunFinalizationReadRequest request) {
      requests.add(request);
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
              command.sequence(),
              IntakeDomainEventType.TURN_READY_TO_CONFIRM,
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
          IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED,
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
      throw new UnsupportedOperationException();
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
