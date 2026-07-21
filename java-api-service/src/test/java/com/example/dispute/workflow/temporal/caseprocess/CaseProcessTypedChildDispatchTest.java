package com.example.dispute.workflow.temporal.caseprocess;

import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandDecision;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Promise;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseProcessTypedChildDispatchTest {

  private static final String TENANT = "tenant-typed-dispatch";
  private static final String CASE_ID = "CASE_TypedDispatch";
  private static final String WORKFLOW_ID =
      CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
  private static final String ACTOR_SCOPE = "a".repeat(64);
  private static final String RESPONDENT_SCOPE = "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private RecordingLedger ledger;
  private RecordingBridge bridge;

  @BeforeEach
  void setUp() {
    environment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setInitialTime(NOW).build());
    Worker caseWorker = environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
    caseWorker.registerWorkflowImplementationTypes(
        CaseProcessWorkflowImpl.class, ReplacementCancellationProbeImpl.class);
    ledger = new RecordingLedger();
    bridge = new RecordingBridge();
    caseWorker.registerActivitiesImplementations(ledger, bridge);
    Worker roomWorker = environment.newWorker(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
    roomWorker.registerWorkflowImplementationTypes(
        RoomControlWorkflowImpl.class, RecordingIntakeWorkflow.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void exactMarkerAndSelectionMatrixFailsClosedWithDistinctCodes() {
    assertThat(selection(Workflow.DEFAULT_VERSION, "room-epoch-selection.v1", WriterMode.SHADOW,
            RoomType.EVIDENCE, null, null, "evidence.v2"))
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
    assertThat(selection(1, "room-epoch-selection.v1", WriterMode.SHADOW, RoomType.INTAKE,
            null, null, "intake.v2"))
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
    assertThat(selection(Workflow.DEFAULT_VERSION, "room-epoch-selection.v2", WriterMode.SHADOW,
            RoomType.INTAKE, "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"))
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
    assertThat(selection(1, "room-epoch-selection.v2", WriterMode.SHADOW, RoomType.INTAKE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"))
        .isEqualTo(ActiveChildKind.TYPED_INTAKE);
    assertThat(selection(1, "room-epoch-selection.v2", WriterMode.TEMPORAL, RoomType.INTAKE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"))
        .isEqualTo(ActiveChildKind.TYPED_INTAKE);

    assertSelectionFailure(
        "INTAKE_CHILD_SELECTION_VERSION_INVALID",
        () -> selection(1, "room-epoch-selection.v3", WriterMode.SHADOW, RoomType.INTAKE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"));
    assertSelectionFailure(
        "INTAKE_CHILD_WRITER_MODE_INVALID",
        () -> selection(1, "room-epoch-selection.v2", WriterMode.LEGACY, RoomType.INTAKE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"));
    assertSelectionFailure(
        "INTAKE_CHILD_ROOM_TYPE_INVALID",
        () -> selection(1, "room-epoch-selection.v2", WriterMode.SHADOW, RoomType.EVIDENCE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v2"));
    assertSelectionFailure(
        "INTAKE_CHILD_WORKFLOW_TYPE_INVALID",
        () -> selection(1, "room-epoch-selection.v2", WriterMode.SHADOW, RoomType.INTAKE,
            "CaseProcessWorkflow", "intake-room.synthetic.v1", "intake.v2"));
    assertSelectionFailure(
        "INTAKE_CHILD_WORKFLOW_BUILD_INVALID",
        () -> selection(1, "room-epoch-selection.v2", WriterMode.SHADOW, RoomType.INTAKE,
            "IntakeRoomWorkflow", "case-control.v1", "intake.v2"));
    assertSelectionFailure(
        "INTAKE_CHILD_SELECTION_INVALID",
        () -> selection(1, "room-epoch-selection.v2", WriterMode.SHADOW, RoomType.INTAKE,
            "IntakeRoomWorkflow", "intake-room.synthetic.v1", "intake.v3"));
  }

  @Test
  void legacyAmbiguousActiveBindingErrorRestoresAsProvisioningOrigin() {
    assertThat(
            CaseProcessWorkflowImpl.inferLegacyErrorOrigin(
                "INTAKE_CHILD_ACTIVE_BINDING_INVALID"))
        .isEqualTo(RecoveryErrorOrigin.PROVISIONING);
    assertThat(
            CaseProcessWorkflowImpl.inferLegacyErrorOrigin(
                "INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID"))
        .isEqualTo(RecoveryErrorOrigin.COMMAND);
    assertThat(
            CaseProcessWorkflowImpl.inferLegacyErrorOrigin(
                "INTAKE_CHILD_BRIDGE_EVENT_BINDING_INVALID"))
        .isEqualTo(RecoveryErrorOrigin.DOMAIN_EVENT);
  }

  @Test
  void activityCancellationPropagatesTheCanceledFailureInsteadOfItsWrapper() {
    CanceledFailure canceled = new CanceledFailure("bridge activity canceled");
    ActivityFailure wrapper =
        new ActivityFailure(
            "bridge activity failed",
            1,
            2,
            "BindIntakeChildCommand",
            "activity-id",
            io.temporal.api.enums.v1.RetryState.RETRY_STATE_CANCEL_REQUESTED,
            "test-worker",
            canceled);

    assertThatThrownBy(() -> CaseProcessWorkflowImpl.rethrowIfCanceled(wrapper))
        .isSameAs(canceled);
  }

  @Test
  void typedStartCommandAndDomainEventUseOnlyTypedSignals() {
    startWorkflow();
    ProvisionRoomEpoch request = typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0);
    ProvisionRoomEpochReceipt receipt = provision(request);

    CaseProcessSnapshot started = awaitProcess(snapshot -> snapshot.activeChildKind() != null);
    assertThat(started.activeChildKind()).isEqualTo(ActiveChildKind.TYPED_INTAKE);
    assertThat(started.activeSelectionSchemaVersion()).isEqualTo("room-epoch-selection.v2");
    assertThat(started.activeRoomWorkflowType()).isEqualTo("IntakeRoomWorkflow");
    assertThat(started.activeRoomWorkflowBuildId()).isEqualTo("intake-room.synthetic.v1");

    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    workflow().acceptCommand(command);
    workflow().domainEventCommitted(event(1, 0));

    CaseProcessSnapshot routed =
        awaitProcess(
            snapshot ->
                snapshot.nextCommandSequence() == 2 && snapshot.nextCaseEventSequence() == 2);
    IntakeRoomSnapshot child =
        awaitIntake(
            receipt.roomWorkflowId(),
            snapshot ->
                snapshot.processedCommandCount() == 1 && snapshot.processedEventCount() == 1);
    assertThat(routed.observedProcessRevision()).isEqualTo(1);
    assertThat(child.processedCommandCount()).isEqualTo(1);
    assertThat(child.processedEventCount()).isEqualTo(1);
    assertThat(bridge.commandCalls).hasValue(1);
    assertThat(bridge.eventCalls).hasValue(1);
    assertThat(signalNames(receipt.roomWorkflowId()))
        .contains("intakeCommandAccepted", "intakeDomainEventCommitted")
        .doesNotContain("roomCommandAccepted", "roomDomainEventCommitted", "roomClose");
  }

  @Test
  void retryableBridgeReadRetriesThenStartsTypedChild() {
    bridge.startUnavailableFailures = 2;
    startWorkflow();

    ProvisionRoomEpochReceipt receipt = provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));

    assertThat(receipt.roomWorkflowRunId()).isNotBlank();
    assertThat(bridge.startCalls).hasValue(3);
    assertThat(workflow().state().activeChildKind()).isEqualTo(ActiveChildKind.TYPED_INTAKE);
  }

  @Test
  void exhaustedRetryableBridgeReadLeavesProvisioningStateUntouched() {
    bridge.startUnavailableFailures = Integer.MAX_VALUE;
    startWorkflow();

    assertThatThrownBy(() -> provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0)))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot -> "INTAKE_CHILD_BRIDGE_START_FAILED".equals(snapshot.protocolErrorCode()));
    assertThat(bridge.startCalls).hasValue(3);
    assertThat(state.activeChildWorkflowId()).isNull();
    assertThat(state.provisioningCommitmentCount()).isZero();
  }

  @Test
  void exhaustedCommandBridgeReadPreservesSequenceRevisionAndRecoversOnManualRetry() {
    startWorkflow();
    provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    bridge.commandUnavailableFailures = Integer.MAX_VALUE;

    assertThatThrownBy(() -> workflow().acceptCommand(command))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot blocked =
        awaitProcess(
            snapshot -> "INTAKE_CHILD_BRIDGE_COMMAND_FAILED".equals(snapshot.protocolErrorCode()));
    assertThat(bridge.commandCalls).hasValue(3);
    assertThat(blocked.nextCommandSequence()).isEqualTo(1);
    assertThat(blocked.processedCommandCount()).isZero();
    assertThat(blocked.observedProcessRevision()).isZero();

    bridge.commandUnavailableFailures = 0;
    workflow().retrySequenceGap();

    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    assertThat(recovered.observedProcessRevision()).isEqualTo(1);
    assertThat(recovered.protocolErrorCode()).isNull();
  }

  @Test
  void invariantEventBridgeFailureRunsOnceAndPreservesBufferedEventState() {
    startWorkflow();
    provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    bridge.eventInvariantFailure = true;

    workflow().domainEventCommitted(event(1, 0));

    CaseProcessSnapshot blocked =
        awaitProcess(
            snapshot -> "INTAKE_CHILD_BRIDGE_EVENT_FAILED".equals(snapshot.protocolErrorCode()));
    assertThat(bridge.eventCalls).hasValue(1);
    assertThat(blocked.nextCaseEventSequence()).isEqualTo(1);
    assertThat(blocked.processedEventCount()).isZero();
    assertThat(blocked.bufferedEventCount()).isEqualTo(1);
    assertThat(blocked.observedProcessRevision()).isZero();
  }

  @Test
  void malformedCommandBindingFailsClosedBeforeTypedSignal() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    bridge.commandFault = CommandBindingFault.NULL_BINDING;

    assertThatThrownBy(() -> workflow().acceptCommand(command))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot blocked =
        awaitProcess(
            snapshot ->
                "INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID"
                    .equals(snapshot.protocolErrorCode()));
    assertThat(blocked.nextCommandSequence()).isEqualTo(1);
    assertThat(blocked.protocolErrorOrigin()).isEqualTo(RecoveryErrorOrigin.COMMAND);
    assertThat(awaitIntake(receipt.roomWorkflowId(), snapshot -> true).processedCommandCount())
        .isZero();
  }

  @Test
  void commandBindingOuterAndInnerPinsAreValidatedBeforeTypedSignal() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    bridge.commandFault = CommandBindingFault.OUTER_PAYLOAD_HASH;

    assertThatThrownBy(() -> workflow().acceptCommand(command))
        .isInstanceOf(WorkflowUpdateException.class);
    awaitProcess(
        snapshot ->
            "INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID"
                .equals(snapshot.protocolErrorCode()));

    bridge.commandFault = CommandBindingFault.INNER_COMMAND_ID;
    workflow().retrySequenceGap();
    awaitProcess(snapshot -> bridge.commandCalls.get() >= 2);
    CaseProcessSnapshot blocked = workflow().state();
    assertThat(blocked.nextCommandSequence()).isEqualTo(1);
    assertThat(awaitIntake(receipt.roomWorkflowId(), snapshot -> true).processedCommandCount())
        .isZero();
  }

  @Test
  void domainEventBindingOuterAndInnerPinsAreValidatedBeforeTypedSignal() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    bridge.eventFault = EventBindingFault.OUTER_SOURCE_PAYLOAD_HASH;

    workflow().domainEventCommitted(event(1, 0));

    CaseProcessSnapshot blocked =
        awaitProcess(
            snapshot ->
                "INTAKE_CHILD_BRIDGE_EVENT_BINDING_INVALID"
                    .equals(snapshot.protocolErrorCode()));
    assertThat(blocked.nextCaseEventSequence()).isEqualTo(1);
    assertThat(blocked.protocolErrorOrigin()).isEqualTo(RecoveryErrorOrigin.DOMAIN_EVENT);
    assertThat(awaitIntake(receipt.roomWorkflowId(), snapshot -> true).processedEventCount())
        .isZero();

    bridge.eventFault = EventBindingFault.INNER_EVENT_ID;
    workflow().retrySequenceGap();
    awaitProcess(snapshot -> bridge.eventCalls.get() >= 2);
    assertThat(workflow().state().nextCaseEventSequence()).isEqualTo(1);
    assertThat(awaitIntake(receipt.roomWorkflowId(), snapshot -> true).processedEventCount())
        .isZero();
  }

  @Test
  void malformedDomainEventBindingFailsClosedBeforeTypedSignal() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    bridge.eventFault = EventBindingFault.NULL_BINDING;

    workflow().domainEventCommitted(event(1, 0));

    CaseProcessSnapshot blocked =
        awaitProcess(
            snapshot ->
                "INTAKE_CHILD_BRIDGE_EVENT_BINDING_INVALID"
                    .equals(snapshot.protocolErrorCode()));
    assertThat(blocked.nextCaseEventSequence()).isEqualTo(1);
    assertThat(awaitIntake(receipt.roomWorkflowId(), snapshot -> true).processedEventCount())
        .isZero();
  }

  @Test
  void roomRevisionStartsFromProvisioningAndAdvancesMonotonicallyOnEvents() {
    startWorkflow();
    provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    assertThat(workflow().state().activeRoomRevision()).isZero();

    workflow().domainEventCommitted(event(1, 0));
    CaseProcessSnapshot afterEvent =
        awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 2);
    assertThat(afterEvent.activeRoomRevision()).isEqualTo(1);

    CaseCommandRef command = command(1, 0, 1);
    ledger.put(command);
    bridge.commandRoomRevision = 0;
    assertThatThrownBy(() -> workflow().acceptCommand(command))
        .isInstanceOf(WorkflowUpdateException.class);
    CaseProcessSnapshot stale =
        awaitProcess(
            snapshot ->
                "INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID"
                    .equals(snapshot.protocolErrorCode()));
    assertThat(stale.activeRoomRevision()).isEqualTo(1);
    assertThat(stale.nextCommandSequence()).isEqualTo(1);

    bridge.commandRoomRevision = 1;
    workflow().retrySequenceGap();
    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    assertThat(recovered.activeRoomRevision()).isEqualTo(1);
  }

  @Test
  void successfulOldChildRoutingDoesNotClearProvisioningBindingError() {
    startWorkflow();
    ProvisionRoomEpochReceipt generic =
        provision(genericProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    bridge.startActiveBindingMismatch = true;

    assertThatThrownBy(() -> provision(typedProvision(RoomType.INTAKE, 1, 2, 0, 0, 0)))
        .isInstanceOf(WorkflowUpdateException.class);
    CaseProcessSnapshot failedProvision =
        awaitProcess(
            snapshot ->
                "INTAKE_CHILD_ACTIVE_BINDING_INVALID".equals(snapshot.protocolErrorCode()));
    assertThat(failedProvision.protocolErrorOrigin()).isEqualTo(RecoveryErrorOrigin.PROVISIONING);

    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    workflow().acceptCommand(command);
    CaseProcessSnapshot routed = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    assertThat(routed.activeChildWorkflowId()).isEqualTo(generic.roomWorkflowId());
    assertThat(routed.protocolErrorCode()).isEqualTo("INTAKE_CHILD_ACTIVE_BINDING_INVALID");
    assertThat(routed.protocolErrorOrigin()).isEqualTo(RecoveryErrorOrigin.PROVISIONING);

    bridge.startActiveBindingMismatch = false;
    provision(typedProvision(RoomType.INTAKE, 1, 3, 1, 1, 0));
    assertThat(workflow().state().protocolErrorCode()).isNull();
    assertThat(workflow().state().protocolErrorOrigin()).isNull();
  }

  @Test
  void detachedCompensationCancelsChildStartedBeforeRootCancellation() {
    ProvisionRoomEpoch request = typedProvision(RoomType.INTAKE, 7, 7, 0, 0, 0);
    String probeId = "case-process-replacement-cancellation-probe";
    ReplacementCancellationProbe probe =
        client.newWorkflowStub(
            ReplacementCancellationProbe.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(probeId)
                .setTaskQueue(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                .build());

    WorkflowClient.start(probe::run, request.roomWorkflowId(), typedStart(request));

    WorkflowExecution started = awaitProbeStarted(probe);
    assertThat(started.getWorkflowId()).isEqualTo(request.roomWorkflowId());
    assertThat(started.getRunId()).isNotBlank();
    assertThat(probe.authorityCommitted()).isFalse();
    client.newUntypedWorkflowStub(probeId).cancel();

    awaitStatus(probeId, WORKFLOW_EXECUTION_STATUS_CANCELED);
    awaitStatus(request.roomWorkflowId(), WORKFLOW_EXECUTION_STATUS_CANCELED);
    assertThat(client.fetchHistory(probeId).getEvents())
        .anySatisfy(
            historyEvent -> {
              assertThat(historyEvent.hasRequestCancelExternalWorkflowExecutionInitiatedEventAttributes())
                  .isTrue();
              assertThat(
                      historyEvent
                          .getRequestCancelExternalWorkflowExecutionInitiatedEventAttributes()
                          .getWorkflowExecution())
                  .isEqualTo(started);
            });
  }

  @Test
  void invariantBridgeFailureIsNotRetriedAndDoesNotReplaceGenericChild() {
    startWorkflow();
    ProvisionRoomEpochReceipt generic =
        provision(genericProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    bridge.startInvariantFailure = true;

    assertThatThrownBy(() -> provision(typedProvision(RoomType.INTAKE, 1, 2, 0, 0, 0)))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot -> "INTAKE_CHILD_BRIDGE_START_FAILED".equals(snapshot.protocolErrorCode()));
    assertThat(bridge.startCalls).hasValue(1);
    assertThat(state.activeChildWorkflowId()).isEqualTo(generic.roomWorkflowId());
    assertThat(state.activeChildKind()).isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
    assertThat(state.activeFencingToken()).isEqualTo(1);
    assertThat(state.provisioningCommitmentCount()).isEqualTo(1);
    assertThat(status(generic.roomWorkflowId())).isNotEqualTo(WORKFLOW_EXECUTION_STATUS_COMPLETED);
  }

  @Test
  void genericToTypedReplacementClosesTheGenericChild() {
    startWorkflow();
    ProvisionRoomEpochReceipt generic =
        provision(genericProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));

    ProvisionRoomEpochReceipt typed =
        provision(typedProvision(RoomType.INTAKE, 1, 2, 0, 0, 0));

    awaitStatus(generic.roomWorkflowId(), WORKFLOW_EXECUTION_STATUS_COMPLETED);
    assertThat(workflow().state().activeChildWorkflowId()).isEqualTo(typed.roomWorkflowId());
    assertThat(workflow().state().activeChildKind()).isEqualTo(ActiveChildKind.TYPED_INTAKE);
  }

  @Test
  void typedToGenericReplacementCancelsOldTypedChildWithoutGenericCloseSignal() {
    startWorkflow();
    ProvisionRoomEpochReceipt typed =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));

    ProvisionRoomEpochReceipt generic =
        provision(genericProvision(RoomType.EVIDENCE, 0, 2, 0, 0, 0));

    awaitStatus(typed.roomWorkflowId(), WORKFLOW_EXECUTION_STATUS_CANCELED);
    assertThat(signalNames(typed.roomWorkflowId()))
        .doesNotContain("roomClose", "intakeCommandAccepted");
    assertThat(workflow().state().activeChildWorkflowId()).isEqualTo(generic.roomWorkflowId());
    assertThat(workflow().state().activeChildKind())
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
  }

  @Test
  void typedToTypedReplacementCancelsOldTypedChild() {
    startWorkflow();
    ProvisionRoomEpochReceipt first =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));

    ProvisionRoomEpochReceipt second =
        provision(typedProvision(RoomType.INTAKE, 1, 2, 0, 0, 0));

    awaitStatus(first.roomWorkflowId(), WORKFLOW_EXECUTION_STATUS_CANCELED);
    assertThat(workflow().state().activeChildWorkflowId()).isEqualTo(second.roomWorkflowId());
    assertThat(workflow().state().activeChildKind()).isEqualTo(ActiveChildKind.TYPED_INTAKE);
  }

  @Test
  void parentContinueAsNewRestoresTypedRoutingAndCapturedMarkerHistoryReplays() throws Exception {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(typedProvision(RoomType.INTAKE, 0, 1, 0, 0, 0));
    CaseProcessSnapshot before = awaitProcess(snapshot -> snapshot.activeChildKind() != null);

    workflow().requestContinueAsNew();
    CaseProcessSnapshot continued =
        awaitProcess(
            snapshot ->
                snapshot.runGeneration() == 1
                    && !snapshot.workflowRunId().equals(before.workflowRunId()));
    WorkflowExecutionHistory captured = client.fetchHistory(WORKFLOW_ID, before.workflowRunId());
    WorkflowExecutionHistory serialized =
        WorkflowExecutionHistory.fromJson(captured.toJson(true), WORKFLOW_ID);
    WorkflowReplayer.replayWorkflowExecution(serialized, CaseProcessWorkflowImpl.class);

    CaseCommandRef command = command(1, 0, 0);
    ledger.put(command);
    workflow().acceptCommand(command);

    IntakeRoomSnapshot child =
        awaitIntake(receipt.roomWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(continued.activeChildKind()).isEqualTo(ActiveChildKind.TYPED_INTAKE);
    assertThat(child.processedCommandCount()).isEqualTo(1);
  }

  @Test
  void typedChildStartConflictPreservesOldDescriptorCommitmentAndHighWater() {
    startWorkflow();
    ProvisionRoomEpochReceipt old =
        provision(genericProvision(RoomType.EVIDENCE, 0, 1, 0, 0, 0));
    ProvisionRoomEpoch blocked = typedProvision(RoomType.INTAKE, 0, 2, 0, 0, 0);
    IntakeRoomWorkflow occupied =
        client.newWorkflowStub(
            IntakeRoomWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(blocked.roomWorkflowId())
                .setTaskQueue(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE)
                .build());
    WorkflowClient.start(occupied::run, typedStart(blocked));

    assertThatThrownBy(() -> provision(blocked)).isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot -> "ROOM_EPOCH_CHILD_START_CONFLICT".equals(snapshot.protocolErrorCode()));
    assertThat(state.activeChildWorkflowId()).isEqualTo(old.roomWorkflowId());
    assertThat(state.activeFencingToken()).isEqualTo(1);
    assertThat(state.provisioningCommitmentCount()).isEqualTo(1);
    assertThat(state.activeChildKind()).isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
  }

  private static ActiveChildKind selection(
      int marker,
      String selectionVersion,
      WriterMode writerMode,
      RoomType roomType,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      String graphKey) {
    return CaseProcessWorkflowImpl.selectProvisionedChildKind(
        marker,
        selectionVersion,
        writerMode,
        roomType,
        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
        roomWorkflowType,
        roomWorkflowBuildId,
        graphKey);
  }

  private static void assertSelectionFailure(String type, Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            ApplicationFailure.class, failure -> assertThat(failure.getType()).isEqualTo(type));
  }

  private void startWorkflow() {
    CaseProcessWorkflow workflow =
        client.newWorkflowStub(
            CaseProcessWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID)
                .setTaskQueue(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                .build());
    WorkflowClient.start(workflow::run, (CaseProcessCarryState) null);
  }

  private ProvisionRoomEpochReceipt provision(ProvisionRoomEpoch request) {
    return WorkflowStub.fromTyped(workflow())
        .startUpdate(
            UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                .setUpdateName(CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
                .setUpdateId(request.updateId())
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build(),
            request)
        .getResult();
  }

  private CaseProcessWorkflow workflow() {
    return client.newWorkflowStub(CaseProcessWorkflow.class, WORKFLOW_ID);
  }

  private CaseProcessSnapshot awaitProcess(Predicate<CaseProcessSnapshot> predicate) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    CaseProcessSnapshot last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = workflow().state();
        if (predicate.test(last)) {
          return last;
        }
      } catch (RuntimeException ignored) {
        // The query can race Workflow start or Continue-As-New.
      }
      sleepBriefly();
    }
    throw new AssertionError("case workflow did not converge; last=" + last);
  }

  private IntakeRoomSnapshot awaitIntake(
      String workflowId, Predicate<IntakeRoomSnapshot> predicate) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        IntakeRoomSnapshot snapshot =
            client.newWorkflowStub(IntakeRoomWorkflow.class, workflowId).state();
        if (predicate.test(snapshot)) {
          return snapshot;
        }
      } catch (RuntimeException ignored) {
        // The query can race child start.
      }
      sleepBriefly();
    }
    throw new AssertionError("typed Intake child did not converge");
  }

  private WorkflowExecution awaitProbeStarted(ReplacementCancellationProbe probe) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        WorkflowExecution started = probe.startedChild();
        if (started != null && !started.getRunId().isBlank()) {
          return started;
        }
      } catch (RuntimeException ignored) {
        // The query can race probe start.
      }
      sleepBriefly();
    }
    throw new AssertionError("replacement cancellation probe did not start its child");
  }

  private void awaitStatus(
      String workflowId, io.temporal.api.enums.v1.WorkflowExecutionStatus expected) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (status(workflowId) == expected) {
        return;
      }
      sleepBriefly();
    }
    throw new AssertionError("workflow did not reach status " + expected);
  }

  private io.temporal.api.enums.v1.WorkflowExecutionStatus status(String workflowId) {
    return client.newUntypedWorkflowStub(workflowId).describe().getStatus();
  }

  private List<String> signalNames(String workflowId) {
    return client.fetchHistory(workflowId).getEvents().stream()
        .filter(event -> event.hasWorkflowExecutionSignaledEventAttributes())
        .map(event -> event.getWorkflowExecutionSignaledEventAttributes().getSignalName())
        .toList();
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test interrupted", failure);
    }
  }

  private static ProvisionRoomEpoch typedProvision(
      RoomType roomType,
      long roomEpoch,
      long fence,
      long processRevision,
      long lastCommandSequence,
      long lastEventSequence) {
    return provision(
        roomType,
        roomEpoch,
        fence,
        processRevision,
        lastCommandSequence,
        lastEventSequence,
        "room-epoch-selection.v2",
        "IntakeRoomWorkflow",
        "intake-room.synthetic.v1",
        "intake.v2");
  }

  private static ProvisionRoomEpoch genericProvision(
      RoomType roomType,
      long roomEpoch,
      long fence,
      long processRevision,
      long lastCommandSequence,
      long lastEventSequence) {
    return provision(
        roomType,
        roomEpoch,
        fence,
        processRevision,
        lastCommandSequence,
        lastEventSequence,
        "room-epoch-selection.v1",
        null,
        null,
        roomType.name().toLowerCase() + ".v2");
  }

  private static ProvisionRoomEpoch provision(
      RoomType roomType,
      long roomEpoch,
      long fence,
      long processRevision,
      long lastCommandSequence,
      long lastEventSequence,
      String selectionVersion,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      String graphKey) {
    String suffix = roomType.name().toLowerCase() + "-" + roomEpoch;
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-" + suffix,
        TENANT,
        CASE_ID,
        "room-" + suffix,
        roomType,
        roomEpoch,
        processRevision,
        0,
        fence,
        "ACTIVE",
        roomType.name(),
        "ACTIVE",
        WriterMode.SHADOW,
        WORKFLOW_ID,
        CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, roomType, roomEpoch),
        selectionVersion,
        "case-process-contract.v1",
        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
        "case-control.synthetic.v1",
        roomWorkflowType,
        roomWorkflowBuildId,
        graphKey,
        "2.0.0",
        "intake-checkpoint.v2",
        "agent-stream.v2",
        lastCommandSequence,
        lastEventSequence,
        lastCommandSequence + 1,
        lastEventSequence + 1,
        NOW.plusSeconds(3600),
        null,
        null,
        NOW.plusSeconds(fence));
  }

  private static IntakeRoomStart typedStart(ProvisionRoomEpoch request) {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        request.roomWorkflowBuildId(),
        request.graphVersion(),
        request.checkpointSchemaVersion(),
        "prompt.v1",
        "model.synthetic.v1",
        "intake-turn.v2",
        "policy.v1",
        "guardrail.v1",
        "tools.none.v1",
        ACTOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static CaseCommandRef command(int sequence, long roomEpoch, long expectedRevision) {
    String hash = Integer.toHexString(sequence % 16).repeat(64);
    return new CaseCommandRef(
        "case-command-ref.v1",
        "command-" + sequence,
        TENANT,
        CASE_ID,
        sequence,
        CommandType.INTAKE_MESSAGE,
        RoomType.INTAKE,
        roomEpoch,
        new ActorRef("user-typed", ActorRole.USER, List.of("intake:message")),
        new PayloadRef("intake-command.v1", "urn:test:command:" + sequence, hash, 32),
        expectedRevision,
        NOW.plusSeconds(sequence),
        NOW.plusSeconds(3600 + sequence),
        "00-11111111111111111111111111111111-2222222222222222-01",
        hash);
  }

  private static CaseDomainEventRef event(int sequence, long roomEpoch) {
    String hash = "d".repeat(64);
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "event-" + sequence,
        TENANT,
        CASE_ID,
        sequence,
        "INTAKE_CANCELLED",
        RoomType.INTAKE,
        roomEpoch,
        new PayloadRef("intake-event.v1", "urn:test:event:" + sequence, hash, 16),
        NOW.plusSeconds(sequence),
        "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
  }

  public static final class RecordingIntakeWorkflow implements IntakeRoomWorkflow {
    private IntakeRoomStart start;
    private long commandCount;
    private long eventCount;

    @Override
    public IntakeRoomSnapshot run(IntakeRoomStart start) {
      this.start = start;
      Workflow.await(() -> false);
      return state();
    }

    @Override
    public void commandAccepted(IntakeWorkflowCommand command) {
      commandCount++;
    }

    @Override
    public void domainEventCommitted(IntakeDomainEventRef event) {
      eventCount++;
    }

    @Override
    public void requestContinueAsNew() {}

    @Override
    public IntakeRoomSnapshot state() {
      return new IntakeRoomSnapshot(
          "intake-room-snapshot.v1",
          start.tenantSurrogate(),
          start.caseId(),
          start.roomEpoch(),
          start.fencingToken(),
          start.initiatorActorScopeHash(),
          start.respondentActorScopeHash(),
          IntakeRoomPhase.OPEN,
          IntakeParty.INITIATOR,
          start.firstCommandSequence() + commandCount,
          start.firstEventSequence() + eventCount,
          commandCount,
          eventCount,
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
          null,
          start.initialProcessRevision(),
          start.initialRoomRevision(),
          null,
          0,
          null);
    }

    @Override
    public IntakeCommandDecision lastCommandDecision() {
      return null;
    }
  }

  @WorkflowInterface
  public interface ReplacementCancellationProbe {

    @WorkflowMethod(name = "ReplacementCancellationProbe")
    void run(String childWorkflowId, IntakeRoomStart start);

    @QueryMethod
    WorkflowExecution startedChild();

    @QueryMethod
    boolean authorityCommitted();
  }

  public static final class ReplacementCancellationProbeImpl
      implements ReplacementCancellationProbe {

    private WorkflowExecution startedChild;
    private boolean authorityCommitted;

    @Override
    public void run(String childWorkflowId, IntakeRoomStart start) {
      WorkflowExecution[] execution = new WorkflowExecution[1];
      CancellationScope detachedStart =
          Workflow.newDetachedCancellationScope(
              () -> {
                IntakeRoomWorkflow child =
                    Workflow.newChildWorkflowStub(
                        IntakeRoomWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(childWorkflowId)
                            .setTaskQueue(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE)
                            .setParentClosePolicy(
                                io.temporal.api.enums.v1.ParentClosePolicy
                                    .PARENT_CLOSE_POLICY_ABANDON)
                            .build());
                Promise<?> completion = Async.function(child::run, start);
                completion.exceptionally(failure -> null);
                execution[0] = Workflow.getWorkflowExecution(child).get();
              });
      detachedStart.run();
      startedChild = execution[0];
      try {
        Workflow.await(() -> false);
        authorityCommitted = true;
      } finally {
        if (!authorityCommitted && startedChild != null) {
          CaseProcessWorkflowImpl.cancelUncommittedChild(
              startedChild, "ROOM_CONTROL_PROVISIONING_NOT_COMMITTED");
        }
      }
    }

    @Override
    public WorkflowExecution startedChild() {
      return startedChild;
    }

    @Override
    public boolean authorityCommitted() {
      return authorityCommitted;
    }
  }

  private static final class RecordingBridge implements IntakeChildBridgeActivities {
    private final AtomicInteger startCalls = new AtomicInteger();
    private final AtomicInteger commandCalls = new AtomicInteger();
    private final AtomicInteger eventCalls = new AtomicInteger();
    private volatile int startUnavailableFailures;
    private volatile int commandUnavailableFailures;
    private volatile boolean startInvariantFailure;
    private volatile boolean eventInvariantFailure;
    private volatile boolean startActiveBindingMismatch;
    private volatile CommandBindingFault commandFault = CommandBindingFault.NONE;
    private volatile EventBindingFault eventFault = EventBindingFault.NONE;
    private volatile long commandRoomRevision;

    @Override
    public StartBinding bindStart(StartRequest request) {
      int attempt = startCalls.incrementAndGet();
      if (startInvariantFailure) {
        throw ApplicationFailure.newNonRetryableFailure(
            "invariant", "INTAKE_CHILD_BRIDGE_INVARIANT");
      }
      if (attempt <= startUnavailableFailures) {
        throw ApplicationFailure.newFailure(
            "unavailable", "INTAKE_CHILD_BRIDGE_READ_UNAVAILABLE");
      }
      ActiveChildBinding returnedBinding = request.activeBinding();
      if (startActiveBindingMismatch) {
        returnedBinding =
            new ActiveChildBinding(
                returnedBinding.schemaVersion(),
                returnedBinding.tenantSurrogate(),
                returnedBinding.caseId(),
                returnedBinding.roomEpoch() + 1,
                returnedBinding.fencingToken(),
                returnedBinding.selectionSchemaVersion(),
                returnedBinding.caseWorkflowType(),
                returnedBinding.caseWorkflowBuildId(),
                returnedBinding.roomWorkflowType(),
                returnedBinding.roomWorkflowBuildId());
      }
      return new StartBinding(
          "intake-child-start-binding.v1",
          returnedBinding,
          request.provisioning().payloadSha256(),
          typedStart(request.provisioning()));
    }

    @Override
    public CommandBinding bindCommand(CommandRequest request) {
      int attempt = commandCalls.incrementAndGet();
      if (attempt <= commandUnavailableFailures) {
        throw ApplicationFailure.newFailure(
            "unavailable", "INTAKE_CHILD_BRIDGE_READ_UNAVAILABLE");
      }
      if (commandFault == CommandBindingFault.NULL_BINDING) {
        return null;
      }
      CaseCommandRef source = request.command();
      String typedCommandId =
          commandFault == CommandBindingFault.INNER_COMMAND_ID
              ? source.commandId() + "-mismatch"
              : source.commandId();
      IntakeWorkflowCommand typed =
          new IntakeWorkflowCommand(
              "intake-workflow-command.v1",
              typedCommandId,
              source.tenantSurrogate(),
              source.caseId(),
              source.roomEpoch(),
              request.activeBinding().fencingToken(),
              source.caseCommandSequence(),
              IntakeCommandType.INTAKE_MESSAGE,
              IntakeParty.INITIATOR,
              ACTOR_SCOPE,
              source.payloadRef().uri(),
              source.payloadRef().sha256(),
              "intake.operation:" + source.caseId() + ":" + source.commandId(),
              source.requestHash());
      return new CommandBinding(
          "intake-child-command-binding.v1",
          request.activeBinding(),
          commandFault == CommandBindingFault.OUTER_PAYLOAD_HASH
              ? "c".repeat(64)
              : source.payloadRef().sha256(),
          source.requestHash(),
          source.expectedProcessRevision(),
          commandRoomRevision,
          typed);
    }

    @Override
    public DomainEventBinding bindDomainEvent(DomainEventRequest request) {
      eventCalls.incrementAndGet();
      if (eventInvariantFailure) {
        throw ApplicationFailure.newNonRetryableFailure(
            "invariant", "INTAKE_CHILD_BRIDGE_INVARIANT");
      }
      if (eventFault == EventBindingFault.NULL_BINDING) {
        return null;
      }
      CaseDomainEventRef source = request.event();
      String requestHash = "e".repeat(64);
      String typedEventId =
          eventFault == EventBindingFault.INNER_EVENT_ID
              ? source.eventId() + "-mismatch"
              : source.eventId();
      IntakeDomainEventRef typed =
          new IntakeDomainEventRef(
              "intake-domain-event-ref.v1",
              typedEventId,
              source.payloadRef().uri(),
              source.payloadRef().sha256(),
              source.caseEventSequence(),
              IntakeDomainEventType.CANCELLED,
              IntakeParty.INITIATOR,
              "command-event-" + source.caseEventSequence(),
              source.tenantSurrogate(),
              source.caseId(),
              source.roomEpoch(),
              request.activeBinding().fencingToken(),
              ACTOR_SCOPE,
              "intake.operation:" + source.caseId() + ":command-event-"
                  + source.caseEventSequence(),
              requestHash,
              "f".repeat(64),
              1,
              1,
              null,
              null);
      return new DomainEventBinding(
          "intake-child-domain-event-binding.v1",
          request.activeBinding(),
          eventFault == EventBindingFault.OUTER_SOURCE_PAYLOAD_HASH
              ? "c".repeat(64)
              : source.payloadRef().sha256(),
          requestHash,
          1,
          1,
          typed);
    }
  }

  private enum CommandBindingFault {
    NONE,
    NULL_BINDING,
    OUTER_PAYLOAD_HASH,
    INNER_COMMAND_ID
  }

  private enum EventBindingFault {
    NONE,
    NULL_BINDING,
    OUTER_SOURCE_PAYLOAD_HASH,
    INNER_EVENT_ID
  }

  private static final class RecordingLedger
      implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {
    private final Map<Long, CaseCommandRef> commands = new ConcurrentHashMap<>();
    private final Map<Long, CaseCommandLedgerState> states = new ConcurrentHashMap<>();
    private final List<CaseDomainEventRef> events = new CopyOnWriteArrayList<>();

    void put(CaseCommandRef command) {
      commands.put(command.caseCommandSequence(), command);
      states.put(command.caseCommandSequence(), CaseCommandLedgerState.PENDING_ORCHESTRATION);
    }

    @Override
    public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
      return commands.values().stream()
          .filter(
              command ->
                  command.caseCommandSequence() >= request.fromSequenceInclusive()
                      && command.caseCommandSequence() <= request.toSequenceInclusive())
          .sorted(java.util.Comparator.comparingLong(CaseCommandRef::caseCommandSequence))
          .toList();
    }

    @Override
    public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(LoadSequenceRange request) {
      return loadCaseCommands(request).stream()
          .map(
              command ->
                  new CaseCommandLedgerEntry(
                      "case-command-ledger-entry.v1",
                      command,
                      states.get(command.caseCommandSequence())))
          .toList();
    }

    @Override
    public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
      return new ArrayList<>(events);
    }

    @Override
    public void reportSequenceGap(SequenceGapReport report) {}

    @Override
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
      return new ExpireCaseCommandResult(
          "expire-case-command-result.v1", CommandLifecycleOutcome.EXPIRED);
    }

    @Override
    public RecordCaseCommandRoutedResult recordCaseCommandRouted(RecordCaseCommandRouted request) {
      return new RecordCaseCommandRoutedResult(
          "record-case-command-routed-result.v1", CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
    }

    @Override
    public RecordCaseCommandRoutedResult completeCaseCommandRouting(
        RecordCaseCommandRouted request) {
      states.put(request.caseCommandSequence(), CaseCommandLedgerState.SHADOW_COMPLETED);
      return new RecordCaseCommandRoutedResult(
          "record-case-command-routed-result.v1", CommandLifecycleOutcome.SHADOW_COMPLETED);
    }
  }
}
