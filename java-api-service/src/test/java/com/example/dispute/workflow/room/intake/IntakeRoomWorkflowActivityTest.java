package com.example.dispute.workflow.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityTemporalPolicy;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityFailureTypes;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
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
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
          .isEqualTo(2);

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
    assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(2);

    ActivityOptions reconciliationOnly =
        IntakeActivityTemporalPolicy.options(
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 0, 0, 0));
    assertThat(reconciliationOnly.getRetryOptions().getMaximumAttempts()).isEqualTo(1);
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
      assertThat(state.protocolErrorCode()).isEqualTo(IntakeActivityFailureTypes.SCHEMA);
      assertThat(activities.snapshotRequests).hasSize(1);
      assertThat(activities.graphRequests).hasSize(1);
      assertThat(activities.finalizationRequests).isEmpty();
    }
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
            new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                "intake-retry-budget.v1", 2, 2, 1),
            branchOperation));
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }

  private static final class FakeActivities implements IntakeRoomActivities {

    private final boolean failGraph;
    private final List<SnapshotPublicationRequest> snapshotRequests = new ArrayList<>();
    private final List<GraphExecutionRequest> graphRequests = new ArrayList<>();
    private final List<TurnFinalizationRequest> finalizationRequests = new ArrayList<>();
    private final List<BranchCommitRequest> acceptRequests = new ArrayList<>();

    private FakeActivities() {
      this(false);
    }

    private FakeActivities(boolean failGraph) {
      this.failGraph = failGraph;
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
      snapshotRequests.add(request);
      return new SnapshotPublicationReceipt(
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
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
      graphRequests.add(request);
      if (failGraph) {
        throw ApplicationFailure.newNonRetryableFailure(
            "synthetic schema rejection", IntakeActivityFailureTypes.SCHEMA);
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
      return new GraphExecutionReceipt(
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
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
      finalizationRequests.add(request);
      long revision = request.envelope().commandSequence();
      GraphExecutionReceipt graph = request.graphExecution();
      IntakeDomainEventRef event =
          event(
              "EVENT_" + request.envelope().commandId(),
              request.envelope(),
              IntakeDomainEventType.TURN_READY_TO_CONFIRM,
              request.operationKey(),
              graph.operation().resultHash(),
              revision,
              graph.agentRunRef(),
              graph.graphExecutionRef());
      return new TurnFinalizationReceipt(
          "intake-turn-finalization-activity-receipt.v1",
          operation(request.operationKey(), request.requestHash(), graph.operation().resultHash(), revision, revision),
          formalReceipt(request, event, revision),
          event);
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
      acceptRequests.add(request);
      return branchReceipt(request, IntakeDomainEventType.INITIATOR_ACCEPTED);
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
      return branchReceipt(request, IntakeDomainEventType.NOT_ADMISSIBLE);
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
      return branchReceipt(request, IntakeDomainEventType.CANCELLED);
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
      return branchReceipt(request, IntakeDomainEventType.RESPONDENT_CONFIRMED);
    }

    private static BranchCommitReceipt branchReceipt(
        BranchCommitRequest request, IntakeDomainEventType eventType) {
      long revision = request.envelope().commandSequence();
      String resultHash = hash(9);
      IntakeDomainEventRef event =
          event(
              "EVENT_" + request.envelope().commandId(),
              request.envelope(),
              eventType,
              request.operationKey(),
              resultHash,
              revision,
              null,
              null);
      return new BranchCommitReceipt(
          "intake-branch-commit-receipt.v1",
          request.operation(),
          operation(request.operationKey(), request.requestHash(), resultHash, revision, revision),
          event);
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
          envelope.commandSequence(),
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
  }
}
