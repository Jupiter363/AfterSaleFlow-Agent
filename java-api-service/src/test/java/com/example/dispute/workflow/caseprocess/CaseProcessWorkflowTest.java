package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivities;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateDeliveryException;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessIntakeProjectionRecoveryRequest;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessIntakeProjectionRecoveryResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.ProcessedCommandIdentity;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomCaseProcessWorkflow;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseProcessWorkflowTest {

  private static final String TENANT = "tenant-case-process";
  private static final String CASE_ID = "CASE_ProcessWorkflow";
  private static final String WORKFLOW_ID =
      CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
  private static final String RECOVERY_TASK_QUEUE = "case-process-projection-recovery-test";
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-17T08:00:00Z");

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private RecordingLedgerActivities ledger;
  private RecoveryProjectionActivities recoveryProjection;

  @BeforeEach
  void setUp() {
    environment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setInitialTime(OCCURRED_AT).build());
    Worker caseWorker = environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
    caseWorker.registerWorkflowImplementationTypes(CaseProcessWorkflowImpl.class);
    ledger = new RecordingLedgerActivities();
    recoveryProjection = new RecoveryProjectionActivities();
    caseWorker.registerActivitiesImplementations(ledger, recoveryProjection);
    Worker recoveryWorker = environment.newWorker(RECOVERY_TASK_QUEUE);
    recoveryWorker.registerWorkflowImplementationTypes(RecoveryTargetCaseProcessWorkflow.class);
    RecoveryTargetCaseProcessWorkflow.reset();
    Worker roomWorker = environment.newWorker(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
    roomWorker.registerWorkflowImplementationTypes(RoomControlWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void exposesAcknowledgedIntakeProjectionCompletionRecoveryUpdateContract() {
    var update =
        java.util.Arrays.stream(CaseProcessWorkflow.class.getMethods())
            .filter(method -> method.getName().equals("recoverIntakeProjectionCompletion"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "CaseProcess workflow must expose exact Intake projection recovery"));

    assertThat(update.getParameterTypes())
        .extracting(Class::getName)
        .containsExactly(
            "com.example.dispute.workflow.temporal.caseprocess."
                + "CaseProcessIntakeProjectionRecoveryRequest");
    assertThat(update.getReturnType().getName())
        .isEqualTo(
            "com.example.dispute.workflow.temporal.caseprocess."
                + "CaseProcessIntakeProjectionRecoveryResult");
    assertThat(update.getAnnotation(io.temporal.workflow.UpdateMethod.class)).isNotNull();
    assertThat(update.getAnnotation(io.temporal.workflow.UpdateMethod.class).name())
        .isEqualTo("recoverIntakeProjectionCompletion");
  }

  @Test
  void terminalNoCommitSignalConvergesOnceAndReplaysWithoutDuplicateAccounting() {
    CaseProcessWorkflow targetWorkflow =
        client.newWorkflowStub(
            CaseProcessWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID)
                .setTaskQueue(RECOVERY_TASK_QUEUE)
                .build());
    WorkflowClient.start(targetWorkflow::run, (CaseProcessCarryState) null);
    ProvisionRoomEpochReceipt provisioned =
        provision(targetWorkflow, projectionRecoveryProvisioning());
    CaseCommandRef command = projectionRecoveryCommand();
    ledger.put(command);
    targetWorkflow.acceptCommand(command);
    CaseProcessSnapshot before =
        awaitProcess(
            targetWorkflow,
            snapshot ->
                snapshot.nextCommandSequence() == 2
                    && snapshot.observedProcessRevision() == 1
                    && Long.valueOf(1).equals(snapshot.activeRoomRevision()));
    TargetIntakeCommandTerminalNoCommit authority =
        terminalNoCommitAuthority(command, before);

    targetWorkflow.targetIntakeCommandTerminalNoCommit(authority);
    awaitTerminalNoCommitConvergences(1);

    ConvergeTargetIntakeTerminalNoCommit first = ledger.terminalNoCommitConvergences.getFirst();
    assertThat(first.authority()).isEqualTo(authority);
    assertThat(first.caseWorkflowId()).isEqualTo(WORKFLOW_ID);
    assertThat(first.caseWorkflowRunId()).isEqualTo(provisioned.caseWorkflowRunId());
    assertThat(first.caseWorkflowBuildId()).isEqualTo("case-process-recovery-test.v1");
    assertThat(ledger.terminalNoCommitOutcomes)
        .containsExactly(TerminalNoCommitOutcome.TERMINALIZED);
    assertTerminalNoCommitAccountingUnchanged(before, targetWorkflow.state());

    targetWorkflow.targetIntakeCommandTerminalNoCommit(authority);
    awaitTerminalNoCommitConvergences(2);
    assertThat(ledger.terminalNoCommitOutcomes)
        .containsExactly(
            TerminalNoCommitOutcome.TERMINALIZED,
            TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
    assertTerminalNoCommitAccountingUnchanged(before, targetWorkflow.state());
    assertThat(RecoveryTargetCaseProcessWorkflow.commandDispatches.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.eventDispatches.get()).isZero();
  }

  @Test
  void acknowledgedV3SignalRecoversExactRejectedV2WithoutClearingForForeignAuthority() {
    CaseProcessWorkflow targetWorkflow =
        client.newWorkflowStub(
            CaseProcessWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID)
                .setTaskQueue(RECOVERY_TASK_QUEUE)
                .build());
    WorkflowClient.start(targetWorkflow::run, (CaseProcessCarryState) null);
    provision(targetWorkflow, projectionRecoveryProvisioning());
    CaseCommandRef command = projectionRecoveryCommand();
    ledger.put(command);
    targetWorkflow.acceptCommand(command);
    CaseProcessSnapshot before =
        awaitProcess(
            targetWorkflow,
            snapshot ->
                snapshot.nextCommandSequence() == 2
                    && snapshot.observedProcessRevision() == 1
                    && Long.valueOf(1).equals(snapshot.activeRoomRevision()));
    TargetIntakeCommandTerminalNoCommit observedV2 =
        terminalNoCommitAuthority(command, before);
    ledger.rejectNextTerminalNoCommit = true;

    targetWorkflow.targetIntakeCommandTerminalNoCommit(observedV2);
    CaseProcessSnapshot rejected =
        awaitProcess(
            targetWorkflow,
            snapshot ->
                "TARGET_INTAKE_TERMINAL_NO_COMMIT_REJECTED".equals(
                    snapshot.protocolErrorCode()));
    assertThat(ledger.terminalNoCommitConvergences).hasSize(1);
    assertThat(ledger.terminalNoCommitOutcomes).isEmpty();

    CaseCommandRef foreignCommand = command(2, RoomType.INTAKE, before.activeRoomEpoch());
    TargetIntakeCommandTerminalNoCommit foreignV3 =
        terminalNoCommitAuthority(foreignCommand, before)
            .withProjectionLineage(0, 0, List.of());
    targetWorkflow.targetIntakeCommandTerminalNoCommit(foreignV3);
    environment.sleep(Duration.ofSeconds(1));
    assertThat(ledger.terminalNoCommitConvergences).hasSize(1);
    assertThat(targetWorkflow.state().protocolErrorCode())
        .isEqualTo(rejected.protocolErrorCode());

    TargetIntakeCommandTerminalNoCommit acknowledgedV3 =
        observedV2.withProjectionLineage(0, 0, List.of());
    targetWorkflow.targetIntakeCommandTerminalNoCommit(acknowledgedV3);
    awaitTerminalNoCommitConvergences(2);

    assertThat(ledger.terminalNoCommitConvergences)
        .extracting(convergence -> convergence.authority().schemaVersion())
        .containsExactly(
            TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION,
            TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION);
    assertThat(ledger.terminalNoCommitOutcomes)
        .containsExactly(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
    assertTerminalNoCommitAccountingUnchanged(before, targetWorkflow.state());
    assertThat(RecoveryTargetCaseProcessWorkflow.commandDispatches.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.eventDispatches.get()).isZero();
  }

  @Test
  void acknowledgedIntakeProjectionRecoveryConsumesBufferedFormalEventExactlyOnceWithoutRoomRedispatch() {
    PreparedProjectionRecovery prepared = prepareProjectionRecovery();
    int projectionCallsBeforeRecovery = recoveryProjection.completionCalls.get();
    int roomEventsBeforeRecovery = RecoveryTargetCaseProcessWorkflow.eventDispatches.get();

    CaseProcessIntakeProjectionRecoveryResult result =
        prepared.workflow().recoverIntakeProjectionCompletion(prepared.request());

    CaseProcessSnapshot recovered =
        awaitProcess(
            prepared.workflow(),
            snapshot ->
                snapshot.nextCaseEventSequence() == 2
                    && snapshot.processedEventCount() == 1
                    && snapshot.protocolErrorCode() == null);
    assertThat(result.disposition())
        .isEqualTo(CaseProcessIntakeProjectionRecoveryResult.Disposition.ADOPTED);
    assertThat(result.request()).isEqualTo(prepared.request());
    assertThat(result.projectionResult().outcome())
        .isEqualTo(CompleteConsumedIntakeProjectionOutcome.APPLIED);
    assertThat(recoveryProjection.completionCalls.get())
        .isEqualTo(projectionCallsBeforeRecovery + 1);
    assertThat(recoveryProjection.applyCalls.get()).isZero();
    assertThat(recoveryProjection.commands)
        .containsExactly(prepared.request().projectionCommand(), prepared.request().projectionCommand());
    assertThat(RecoveryTargetCaseProcessWorkflow.startCalls.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.commandDispatches.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.eventDispatches.get())
        .isEqualTo(roomEventsBeforeRecovery);
    assertThat(RecoveryTargetCaseProcessWorkflow.closeCalls.get()).isZero();
    assertThat(recovered.nextCommandSequence()).isEqualTo(2);
    assertThat(recovered.processedCommandCount()).isEqualTo(1);
    assertThat(recovered.bufferedEventCount()).isZero();
    assertThat(recovered.protocolErrorOrigin()).isNull();
    assertThat(recovered.activeChildWorkflowId())
        .isEqualTo(prepared.failed().activeChildWorkflowId());
    assertThat(recovered.activeChildWorkflowRunId())
        .isEqualTo(prepared.failed().activeChildWorkflowRunId());
  }

  @Test
  void exactIntakeProjectionRecoveryReplayReturnsCachedResultWithoutDuplicateAccounting() {
    PreparedProjectionRecovery prepared = prepareProjectionRecovery();
    CaseProcessIntakeProjectionRecoveryResult adopted =
        prepared.workflow().recoverIntakeProjectionCompletion(prepared.request());
    CaseProcessSnapshot afterAdoption =
        awaitProcess(
            prepared.workflow(),
            snapshot ->
                snapshot.nextCaseEventSequence() == 2
                    && snapshot.processedEventCount() == 1
                    && snapshot.protocolErrorCode() == null);
    int completionCallsAfterAdoption = recoveryProjection.completionCalls.get();
    int commandDispatchesAfterAdoption =
        RecoveryTargetCaseProcessWorkflow.commandDispatches.get();
    int eventDispatchesAfterAdoption = RecoveryTargetCaseProcessWorkflow.eventDispatches.get();

    CaseProcessIntakeProjectionRecoveryResult replay =
        prepared.workflow().recoverIntakeProjectionCompletion(prepared.request());
    CaseProcessSnapshot afterReplay = prepared.workflow().state();

    assertThat(replay).isEqualTo(adopted);
    assertThat(recoveryProjection.completionCalls.get()).isEqualTo(completionCallsAfterAdoption);
    assertThat(recoveryProjection.applyCalls.get()).isZero();
    assertThat(RecoveryTargetCaseProcessWorkflow.commandDispatches.get())
        .isEqualTo(commandDispatchesAfterAdoption);
    assertThat(RecoveryTargetCaseProcessWorkflow.eventDispatches.get())
        .isEqualTo(eventDispatchesAfterAdoption);
    assertThat(RecoveryTargetCaseProcessWorkflow.closeCalls.get()).isZero();
    assertThat(afterReplay).isEqualTo(afterAdoption);
  }

  @Test
  void updateWithStartRecoversACommandGapAndRoutesCommandsSerially() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.EVIDENCE, 0);
    ledger.put(first);
    ledger.put(second);

    TemporalUpdateGateway.DeliveryReceipt receipt = startWith(second);

    CaseProcessSnapshot state = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(receipt.temporalRunId()).isNotBlank();
    assertThat(state.processedCommandCount()).isEqualTo(2);
    assertThat(state.pendingCommandCount()).isZero();
    assertThat(state.blockedReason()).isEqualTo("NONE");
    assertThat(state.activeChildWorkflowId())
        .isEqualTo(CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.EVIDENCE, 0));
    assertThat(ledger.commandLoads)
        .anySatisfy(
            range -> {
              assertThat(range.fromSequenceInclusive()).isEqualTo(1);
              assertThat(range.toSequenceInclusive()).isGreaterThanOrEqualTo(1);
            });

    RoomControlSnapshot room =
        awaitRoom(state.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 2);
    assertThat(room.recentCommandIds()).containsExactly("command-1", "command-2");
  }

  @Test
  void outOfOrderDomainEventLoadsTheGapAndDuplicateSignalDoesNotAdvanceTwice() {
    CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
    ledger.put(command);
    startWith(command);
    CaseProcessSnapshot started = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    CaseDomainEventRef first = event(1, RoomType.EVIDENCE, 0);
    CaseDomainEventRef second = event(2, RoomType.EVIDENCE, 0);
    CaseDomainEventRef third = event(3, RoomType.EVIDENCE, 0);
    ledger.put(first);
    ledger.put(second);
    ledger.put(third);

    workflow().domainEventCommitted(third);

    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 4);
    assertThat(recovered.processedEventCount()).isEqualTo(3);
    assertThat(recovered.bufferedEventCount()).isZero();
    assertThat(ledger.eventLoads).isNotEmpty();

    workflow().domainEventCommitted(second);
    CaseProcessSnapshot afterDuplicate =
        awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 4);
    assertThat(afterDuplicate.processedEventCount()).isEqualTo(3);
    assertThat(afterDuplicate.blockedReason()).isEqualTo("NONE");

    RoomControlSnapshot room =
        awaitRoom(started.activeChildWorkflowId(), snapshot -> snapshot.processedEventCount() == 3);
    assertThat(room.recentEventIds()).containsExactly("event-1", "event-2", "event-3");
  }

  @Test
  void unresolvedEventGapBecomesVisibleAndManualRetryRecoversIt() {
    CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
    ledger.put(command);
    startWith(command);
    awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    workflow().domainEventCommitted(event(4, RoomType.EVIDENCE, 0));

    CaseProcessSnapshot blocked =
        awaitProcess(snapshot -> "EVENT_GAP_MANUAL_RECOVERY".equals(snapshot.blockedReason()));
    assertThat(blocked.bufferedEventCount()).isLessThanOrEqualTo(128);
    assertThat(ledger.gapReports)
        .anySatisfy(
            report -> {
              assertThat(report.stream()).isEqualTo(SequenceStream.DOMAIN_EVENT);
              assertThat(report.expectedSequence()).isEqualTo(1);
              assertThat(report.highestObservedSequence()).isEqualTo(4);
              assertThat(report.recoveryAttempts()).isEqualTo(3);
            });

    for (int sequence = 1; sequence <= 4; sequence++) {
      ledger.put(event(sequence, RoomType.EVIDENCE, 0));
    }
    workflow().retrySequenceGap();

    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 5);
    assertThat(recovered.processedEventCount()).isEqualTo(4);
    assertThat(recovered.blockedReason()).isEqualTo("NONE");
  }

  @Test
  void malformedCommandLedgerResponseRequiresManualRecoveryWithoutFailingWorkflow() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.EVIDENCE, 0);
    ledger.put(second);
    ledger.invalidCommandResponse = true;

    startWith(second);

    CaseProcessSnapshot blocked =
        awaitProcess(snapshot -> "COMMAND_GAP_MANUAL_RECOVERY".equals(snapshot.blockedReason()));
    assertThat(blocked.protocolErrorCode()).isEqualTo("COMMAND_LEDGER_RESPONSE_INVALID");
    assertThat(blocked.nextCommandSequence()).isEqualTo(1);

    ledger.invalidCommandResponse = false;
    ledger.put(first);
    workflow().retrySequenceGap();

    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(recovered.blockedReason()).isEqualTo("NONE");
    assertThat(recovered.processedCommandCount()).isEqualTo(2);
  }

  @Test
  void malformedEventLedgerResponseRequiresManualRecoveryWithoutFailingWorkflow() {
    CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
    ledger.put(command);
    startWith(command);
    awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    ledger.invalidEventResponse = true;

    workflow().domainEventCommitted(event(4, RoomType.EVIDENCE, 0));

    CaseProcessSnapshot blocked =
        awaitProcess(snapshot -> "EVENT_GAP_MANUAL_RECOVERY".equals(snapshot.blockedReason()));
    assertThat(blocked.protocolErrorCode()).isEqualTo("DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
    assertThat(blocked.nextCaseEventSequence()).isEqualTo(1);

    ledger.invalidEventResponse = false;
    for (int sequence = 1; sequence <= 4; sequence++) {
      ledger.put(event(sequence, RoomType.EVIDENCE, 0));
    }
    workflow().retrySequenceGap();

    CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 5);
    assertThat(recovered.blockedReason()).isEqualTo("NONE");
    assertThat(recovered.processedEventCount()).isEqualTo(4);
  }

  @Test
  void concurrentOutOfOrderUpdatesReachTheRoomInLedgerSequence() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.EVIDENCE, 0);
    CaseCommandRef third = command(3, RoomType.EVIDENCE, 0);
    ledger.put(first);
    ledger.put(second);
    ledger.put(third);
    startWith(first);
    CaseProcessSnapshot started = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    CompletableFuture<Void> thirdUpdate =
        CompletableFuture.runAsync(() -> workflow().acceptCommand(third));
    CompletableFuture<Void> secondUpdate =
        CompletableFuture.runAsync(() -> workflow().acceptCommand(second));
    CompletableFuture.allOf(thirdUpdate, secondUpdate).join();

    CaseProcessSnapshot completed = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 4);
    assertThat(completed.processedCommandCount()).isEqualTo(3);
    RoomControlSnapshot room =
        awaitRoom(
            started.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 3);
    assertThat(room.recentCommandIds()).containsExactly("command-1", "command-2", "command-3");
  }

  @Test
  void aNewRoomEpochGetsADistinctStableChildWorkflow() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.EVIDENCE, 1);
    ledger.put(first);
    ledger.put(second);
    startWith(first);
    CaseProcessSnapshot firstEpoch = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    provision(provisioning(RoomType.EVIDENCE, 1, 2, 1, 1, 0));
    workflow().acceptCommand(second);

    CaseProcessSnapshot secondEpoch = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(secondEpoch.activeChildWorkflowId())
        .isEqualTo(CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.EVIDENCE, 1))
        .isNotEqualTo(firstEpoch.activeChildWorkflowId());
    RoomControlSnapshot room =
        awaitRoom(
            secondEpoch.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(room.roomEpoch()).isEqualTo(1);
    assertThat(room.recentCommandIds()).containsExactly("command-2");
  }

  @Test
  void aProvisionedFutureRoomReceivesItsEventAndCommandWithoutLegacyFallback() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.HEARING, 0);
    CaseDomainEventRef futureEvent = event(1, RoomType.HEARING, 0);
    ledger.put(first);
    ledger.put(second);
    ledger.put(futureEvent);
    startWith(first);
    CaseProcessSnapshot firstEpoch = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    provision(provisioning(RoomType.HEARING, 0, 2, 1, 1, 0));
    workflow().domainEventCommitted(futureEvent);

    CaseProcessSnapshot eventRouted =
        awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 2);
    assertThat(eventRouted.processedEventCount()).isEqualTo(1);
    assertThat(eventRouted.bufferedEventCount()).isZero();

    workflow().acceptCommand(second);

    CaseProcessSnapshot activated =
        awaitProcess(
            snapshot ->
                snapshot.nextCommandSequence() == 3 && snapshot.nextCaseEventSequence() == 2);
    assertThat(activated.processedEventCount()).isEqualTo(1);
    assertThat(activated.bufferedEventCount()).isZero();
    assertThat(activated.activeChildWorkflowId()).isNotEqualTo(firstEpoch.activeChildWorkflowId());
    RoomControlSnapshot room =
        awaitRoom(
            activated.activeChildWorkflowId(), snapshot -> snapshot.processedEventCount() == 1);
    assertThat(room.roomType()).isEqualTo(RoomType.HEARING);
    assertThat(room.roomEpoch()).isZero();
    assertThat(room.recentEventIds()).containsExactly("event-1");
  }

  @Test
  void aClosedRoomTupleSurvivesContinueAsNewAndClassifiesItsLateEventAsStale() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef second = command(2, RoomType.HEARING, 0);
    ledger.put(first);
    ledger.put(second);
    startWith(first);
    provision(provisioning(RoomType.HEARING, 0, 2, 1, 1, 0));
    workflow().acceptCommand(second);
    CaseProcessSnapshot switched = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);

    workflow().requestContinueAsNew();
    CaseProcessSnapshot continued =
        awaitProcess(snapshot -> snapshot.runGeneration() == switched.runGeneration() + 1);

    workflow().domainEventCommitted(event(1, RoomType.EVIDENCE, 0));

    CaseProcessSnapshot consumed = awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 2);
    assertThat(consumed.processedEventCount()).isEqualTo(1);
    assertThat(consumed.bufferedEventCount()).isZero();
    RoomControlSnapshot activeRoom = awaitRoom(continued.activeChildWorkflowId(), snapshot -> true);
    assertThat(activeRoom.roomType()).isEqualTo(RoomType.HEARING);
    assertThat(activeRoom.processedEventCount()).isZero();
  }

  @Test
  void continueAsNewKeepsTheRecentCommandCarryStateBounded() {
    CaseCommandRef last = null;
    for (int sequence = 1; sequence <= 260; sequence++) {
      last = command(sequence, RoomType.EVIDENCE, 0);
      ledger.put(last);
    }
    startWith(last);

    CaseProcessSnapshot before = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 261);
    int generationBeforeRequest = before.runGeneration();
    assertThat(before.recentCommandCount()).isEqualTo(256);
    assertThat(before.recentCommandIds())
        .hasSize(256)
        .startsWith("command-5")
        .endsWith("command-260")
        .doesNotContain("command-1", "command-2", "command-3", "command-4");

    workflow().requestContinueAsNew();

    CaseProcessSnapshot continued =
        awaitProcess(snapshot -> snapshot.runGeneration() == generationBeforeRequest + 1);
    assertThat(continued.recentCommandCount()).isEqualTo(256);
    assertThat(continued.recentCommandIds())
        .hasSize(256)
        .startsWith("command-5")
        .endsWith("command-260");
  }

  @Test
  void continueAsNewCarriesStateAndReconcilesCrossRunReplayAgainstJavaLedger() {
    CaseCommandRef first = shortLivedCommand(1);
    ledger.put(first);
    startWith(first);
    CaseProcessSnapshot before = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    workflow().requestContinueAsNew();
    CaseProcessSnapshot continued =
        awaitProcess(
            snapshot ->
                snapshot.runGeneration() == 1
                    && !snapshot.workflowRunId().equals(before.workflowRunId()));
    assertThat(continued.nextCommandSequence()).isEqualTo(2);
    assertThat(continued.activeChildWorkflowId()).isEqualTo(before.activeChildWorkflowId());

    environment.sleep(Duration.ofSeconds(1));
    int loadsBeforeReplay = ledger.commandLoads.size();
    workflow().acceptCommand(first);
    CaseProcessSnapshot afterReplay = awaitProcess(snapshot -> snapshot.runGeneration() == 1);
    assertThat(afterReplay.processedCommandCount()).isEqualTo(1);
    assertThat(ledger.commandLoads.size()).isGreaterThan(loadsBeforeReplay);

    CaseCommandRef second = command(2, RoomType.EVIDENCE, 0);
    ledger.put(second);
    workflow().acceptCommand(second);
    CaseProcessSnapshot afterSecond = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(afterSecond.runGeneration()).isEqualTo(1);

    RoomControlSnapshot room =
        awaitRoom(
            afterSecond.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 2);
    assertThat(room.recentCommandIds()).containsExactly("command-1", "command-2");
  }

  @Test
  void runAgeTimerContinuesAsNewAndTheCapturedClosedHistoryReplays() throws Exception {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    ledger.put(first);
    startWith(first);
    CaseProcessSnapshot initial = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

    environment.sleep(Duration.ofHours(24));

    CaseProcessSnapshot continued =
        awaitProcess(
            snapshot ->
                snapshot.runGeneration() == 1
                    && !snapshot.workflowRunId().equals(initial.workflowRunId()));
    WorkflowExecutionHistory captured = client.fetchHistory(WORKFLOW_ID, initial.workflowRunId());
    WorkflowExecutionHistory serializedCapture =
        WorkflowExecutionHistory.fromJson(captured.toJson(true), WORKFLOW_ID);

    assertThat(captured.getWorkflowExecution().getWorkflowId()).isEqualTo(WORKFLOW_ID);
    assertThat(serializedCapture.getLastEvent().getEventType())
        .isEqualTo(EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW);
    assertThat(continued.nextCommandSequence()).isEqualTo(2);
    assertThat(continued.processedCommandCount()).isEqualTo(1);

    WorkflowReplayer.replayWorkflowExecution(serializedCapture, CaseProcessWorkflowImpl.class);
  }

  @Test
  void commandForAnotherWorkflowScopeIsRejectedWithoutMutatingState() {
    CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
    ledger.put(first);
    startWith(first);
    CaseProcessSnapshot before = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    CaseCommandRef wrongCase = command(2, "CASE_Other", RoomType.EVIDENCE, 0);

    assertThatThrownBy(() -> workflow().acceptCommand(wrongCase))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot after = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
    assertThat(after.processedCommandCount()).isEqualTo(before.processedCommandCount());
    assertThat(after.activeChildWorkflowId()).isEqualTo(before.activeChildWorkflowId());
  }

  @Test
  void updateValidatorRejectsACommandWhoseDeadlineAlreadyElapsed() {
    CaseCommandRef expired = expiredCommand(1);
    ledger.put(expired);

    assertThatThrownBy(() -> startWith(expired))
        .isInstanceOfSatisfying(
            TemporalUpdateDeliveryException.class,
            exception -> {
              assertThat(exception.errorCode()).isEqualTo("TEMPORAL_UPDATE_REJECTED");
              assertThat(exception.retryable()).isFalse();
            });
  }

  @Test
  void recoveredExpiredCommandIsPersistedAndConsumedWithoutRoomExecution() {
    CaseCommandRef expiredFirst = expiredCommand(1);
    CaseCommandRef liveSecond = command(2, RoomType.EVIDENCE, 0);
    ledger.put(expiredFirst);
    ledger.put(liveSecond);

    startWith(liveSecond);

    CaseProcessSnapshot state = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(state.processedCommandCount()).isEqualTo(2);
    assertThat(ledger.expirations)
        .singleElement()
        .satisfies(
            expiration -> {
              assertThat(expiration.commandId()).isEqualTo("command-1");
              assertThat(expiration.expiredAt()).isAfterOrEqualTo(expiration.deadlineAt());
            });
    RoomControlSnapshot room =
        awaitRoom(state.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(room.recentCommandIds()).containsExactly("command-2");
  }

  @Test
  void failedLedgerCommandIsConsumedAsATombstoneAndNeverRoutedToTheRoom() {
    CaseCommandRef failedFirst = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef liveSecond = command(2, RoomType.EVIDENCE, 0);
    ledger.put(failedFirst, CaseCommandLedgerState.FAILED);
    ledger.put(liveSecond);

    startWith(liveSecond);

    CaseProcessSnapshot state = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(state.processedCommandCount()).isEqualTo(2);
    assertThat(state.blockedReason()).isEqualTo("NONE");
    RoomControlSnapshot room =
        awaitRoom(state.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(room.recentCommandIds()).containsExactly("command-2");
  }

  @Test
  void gapRecoveryPromotesABufferedLiveCommandToItsAuthoritativeTerminalState() {
    CaseCommandRef liveFirst = command(1, RoomType.EVIDENCE, 0);
    CaseCommandRef failedSecond = command(2, RoomType.EVIDENCE, 0);
    ledger.put(liveFirst);
    ledger.put(failedSecond, CaseCommandLedgerState.FAILED);

    startWith(failedSecond);

    CaseProcessSnapshot state = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
    assertThat(state.processedCommandCount()).isEqualTo(2);
    RoomControlSnapshot room =
        awaitRoom(state.activeChildWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(room.recentCommandIds()).containsExactly("command-1");
  }

  @Test
  void directTemporalCommandBeforeProvisioningIsPermanentlyRejected() {
    startWorkflow();
    CaseCommandRef unprovisioned = command(1, RoomType.INTAKE, 0);

    assertThatThrownBy(() -> workflow().acceptCommand(unprovisioned))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state = awaitProcess(snapshot -> true);
    assertThat(state.activeChildWorkflowId()).isNull();
    assertThat(state.provisioningCommitmentCount()).isZero();
  }

  @Test
  void commandUpdateWithStartCannotBootstrapAnUnprovisionedCaseWorkflow() {
    CaseCommandRef unprovisioned = command(1, RoomType.INTAKE, 0);
    var gateway = new SdkTemporalUpdateGateway(client);

    assertThatThrownBy(
            () ->
                gateway.deliver(
                    new TemporalUpdateGateway.UpdateWithStartRequest(
                        WORKFLOW_ID,
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        unprovisioned.commandId(),
                        unprovisioned)))
        .isInstanceOfSatisfying(
            TemporalUpdateDeliveryException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("TEMPORAL_UPDATE_REJECTED");
              assertThat(failure.retryable()).isFalse();
            });
  }

  @Test
  void provisioningBindsCompleteSelectionAndStableFirstExecutionRunIds() {
    startWorkflow();
    ProvisionRoomEpoch request = provisioning(RoomType.INTAKE, 0, 1, 0, 0, 0);

    ProvisionRoomEpochReceipt receipt = provision(request);

    assertThat(receipt.matches(request)).isTrue();
    assertThat(receipt.caseWorkflowRunId()).isNotBlank();
    assertThat(receipt.roomWorkflowRunId()).isNotBlank();
    assertThat(workflow().provisioningReceipt()).isEqualTo(receipt);
    assertThat(workflow().provisioningCommitment().payloadSha256())
        .isEqualTo(request.payloadSha256());
    RoomControlWorkflow room =
        client.newWorkflowStub(RoomControlWorkflow.class, receipt.roomWorkflowId());
    assertThat(room.provisioningReceipt()).isEqualTo(receipt);
    RoomControlSnapshot roomState = room.state();
    assertThat(roomState.fencingToken()).isEqualTo(1);
    assertThat(roomState.writerMode()).isEqualTo(WriterMode.SHADOW);
    assertThat(roomState.graphKey()).isEqualTo(request.graphKey());
    assertThat(roomState.projectionRef()).isNull();
  }

  @Test
  void v2ProvisioningFailsClosedWhenTheTypedBridgeIsNotRegistered() {
    startWorkflow();
    ProvisionRoomEpoch request = v2Provisioning();

    assertThatThrownBy(() -> provision(request)).isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot -> "INTAKE_CHILD_BRIDGE_START_FAILED".equals(snapshot.protocolErrorCode()));
    assertThat(state.activeChildWorkflowId()).isNull();
    assertThat(state.provisioningCommitmentCount()).isZero();
  }

  @Test
  void globalFenceIncreasesWhileEpochZeroIsValidAcrossRoomTypes() {
    startWorkflow();
    provision(provisioning(RoomType.INTAKE, 0, 1, 0, 0, 0));

    ProvisionRoomEpochReceipt evidence = provision(provisioning(RoomType.EVIDENCE, 0, 2, 0, 0, 0));

    assertThat(evidence.roomEpoch()).isZero();
    assertThat(evidence.roomType()).isEqualTo(RoomType.EVIDENCE);
    assertThat(workflow().state().activeFencingToken()).isEqualTo(2);
    assertThatThrownBy(() -> provision(provisioning(RoomType.HEARING, 0, 1, 0, 0, 0)))
        .isInstanceOf(WorkflowUpdateException.class);
    assertThatThrownBy(() -> provision(provisioning(RoomType.EVIDENCE, 0, 3, 0, 0, 0)))
        .isInstanceOf(WorkflowUpdateException.class);
  }

  @Test
  void provisioningBoundaryRecoversDurableUnsignaledStreamsBeforeSwitch() {
    startWorkflow();
    provision(provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0));
    CaseCommandRef currentRoomCommand = command(1, RoomType.EVIDENCE, 0);
    CaseDomainEventRef currentRoomEvent = event(1, RoomType.EVIDENCE, 0);
    ledger.put(currentRoomCommand);
    ledger.put(currentRoomEvent);
    ProvisionRoomEpoch nextRoom = provisioning(RoomType.HEARING, 0, 2, 1, 1, 1);

    ProvisionRoomEpochReceipt switchedReceipt = provision(nextRoom);

    CaseProcessSnapshot switched =
        awaitProcess(
            snapshot ->
                snapshot.nextCommandSequence() == 2
                    && snapshot.nextCaseEventSequence() == 2
                    && snapshot.activeRoomType() == RoomType.HEARING
                    && snapshot.activeFencingToken() == 2);
    assertThat(switched.processedCommandCount()).isEqualTo(1);
    assertThat(switched.processedEventCount()).isEqualTo(1);
    assertThat(switched.activeChildWorkflowId()).isEqualTo(nextRoom.roomWorkflowId());
    assertThat(ledger.commandLoads)
        .anySatisfy(
            range -> {
              assertThat(range.fromSequenceInclusive()).isEqualTo(1);
              assertThat(range.toSequenceInclusive()).isEqualTo(1);
            });
    assertThat(ledger.eventLoads)
        .anySatisfy(
            range -> {
              assertThat(range.fromSequenceInclusive()).isEqualTo(1);
              assertThat(range.toSequenceInclusive()).isEqualTo(1);
            });
    assertThat(provision(nextRoom)).isEqualTo(switchedReceipt);

    ProvisionRoomEpoch staleBoundary = provisioning(RoomType.REVIEW, 0, 3, 1, 0, 0);
    assertThatThrownBy(() -> provision(staleBoundary))
        .isInstanceOf(WorkflowUpdateException.class);
    CaseProcessSnapshot rejected =
        awaitProcess(
            snapshot ->
                "ROOM_EPOCH_SEQUENCE_BOUNDARY_CONFLICT".equals(snapshot.protocolErrorCode()));
    assertThat(rejected.nextCommandSequence()).isEqualTo(2);
    assertThat(rejected.nextCaseEventSequence()).isEqualTo(2);
    assertThat(rejected.activeRoomType()).isEqualTo(RoomType.HEARING);
    assertThat(rejected.activeFencingToken()).isEqualTo(2);
    assertThat(rejected.activeChildWorkflowId()).isEqualTo(nextRoom.roomWorkflowId());
  }

  @Test
  void childStartConflictFailsOnlyTheUpdateAndPreservesTheActiveRoom() {
    ProvisionRoomEpoch first = provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0);
    ProvisionRoomEpoch blocked = provisioning(RoomType.HEARING, 0, 2, 0, 0, 0);
    startWorkflow(blocked.roomWorkflowId());
    startWorkflow();
    ProvisionRoomEpochReceipt firstReceipt = provision(first);

    assertThatThrownBy(() -> provision(blocked)).isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state = awaitProcess(snapshot -> true);
    assertThat(state.activeRoomType()).isEqualTo(RoomType.EVIDENCE);
    assertThat(state.activeRoomEpoch()).isZero();
    assertThat(state.activeFencingToken()).isEqualTo(1);
    assertThat(state.activeChildWorkflowId()).isEqualTo(firstReceipt.roomWorkflowId());
    assertThat(state.protocolErrorCode()).isEqualTo("ROOM_EPOCH_CHILD_START_CONFLICT");
    assertThat(awaitRoom(firstReceipt.roomWorkflowId(), snapshot -> true)).isNotNull();
  }

  @Test
  void closedOldRoomDoesNotRollBackAHigherFenceProvisioning() {
    startWorkflow();
    ProvisionRoomEpochReceipt first =
        provision(provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0));
    RoomControlWorkflow oldRoom =
        client.newWorkflowStub(RoomControlWorkflow.class, first.roomWorkflowId());
    oldRoom.close("TEST_ROOM_FAILURE");
    awaitWorkflowCompleted(WorkflowStub.fromTyped(oldRoom));

    ProvisionRoomEpochReceipt replacement =
        provision(provisioning(RoomType.HEARING, 0, 2, 0, 0, 0));

    CaseProcessSnapshot state = awaitProcess(snapshot -> snapshot.activeFencingToken() == 2);
    assertThat(state.activeRoomType()).isEqualTo(RoomType.HEARING);
    assertThat(state.activeChildWorkflowId()).isEqualTo(replacement.roomWorkflowId());
    assertThat(state.protocolErrorCode()).isEqualTo("ROOM_CONTROL_CLOSE_FAILED");
  }

  @Test
  void closedActiveRoomFailsOnlyTheCommandAndGatesManualRecovery() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0));
    RoomControlWorkflow room =
        client.newWorkflowStub(RoomControlWorkflow.class, receipt.roomWorkflowId());
    room.close("TEST_ROOM_FAILURE");
    awaitWorkflowCompleted(WorkflowStub.fromTyped(room));
    CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
    ledger.put(command);

    assertThatThrownBy(() -> workflow().acceptCommand(command))
        .isInstanceOf(WorkflowUpdateException.class);

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot ->
                "COMMAND_GAP_MANUAL_RECOVERY".equals(snapshot.blockedReason()));
    assertThat(state.protocolErrorCode()).isEqualTo("CASE_PROCESS_ROOM_ROUTING_FAILED");
    assertThat(state.activeChildWorkflowId()).isEqualTo(receipt.roomWorkflowId());
    assertThat(state.processedCommandCount()).isZero();
  }

  @Test
  void closedActiveRoomGatesDomainEventRoutingForManualRecovery() {
    startWorkflow();
    ProvisionRoomEpochReceipt receipt =
        provision(provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0));
    RoomControlWorkflow room =
        client.newWorkflowStub(RoomControlWorkflow.class, receipt.roomWorkflowId());
    room.close("TEST_ROOM_FAILURE");
    awaitWorkflowCompleted(WorkflowStub.fromTyped(room));

    workflow().domainEventCommitted(event(1, RoomType.EVIDENCE, 0));

    CaseProcessSnapshot state =
        awaitProcess(
            snapshot -> "EVENT_GAP_MANUAL_RECOVERY".equals(snapshot.blockedReason()));
    assertThat(state.protocolErrorCode()).isEqualTo("CASE_PROCESS_ROOM_EVENT_ROUTING_FAILED");
    assertThat(state.activeChildWorkflowId()).isEqualTo(receipt.roomWorkflowId());
    assertThat(state.nextCaseEventSequence()).isEqualTo(1);
    assertThat(state.processedEventCount()).isZero();
    assertThat(state.bufferedEventCount()).isEqualTo(1);
  }

  @Test
  void sameUpdateIdWithChangedSelectionIsRejectedAfterContinueAsNew() {
    startWorkflow();
    ProvisionRoomEpoch original = provisioning(RoomType.INTAKE, 0, 1, 0, 0, 0);
    provision(original);
    workflow().requestContinueAsNew();
    awaitProcess(snapshot -> snapshot.runGeneration() == 1);

    assertThatThrownBy(() -> provision(withGraphVersion(original, "2.0.0")))
        .isInstanceOf(WorkflowUpdateException.class);
  }

  @Test
  void commitmentReplayAfterContinueAsNewReturnsOriginalReceipt() {
    startWorkflow();
    ProvisionRoomEpoch request = provisioning(RoomType.INTAKE, 0, 1, 0, 0, 0);
    ProvisionRoomEpochReceipt original = provision(request);
    workflow().requestContinueAsNew();
    CaseProcessSnapshot continued = awaitProcess(snapshot -> snapshot.runGeneration() == 1);

    ProvisionRoomEpochReceipt replayed = provision(request);

    assertThat(replayed).isEqualTo(original);
    assertThat(replayed.caseWorkflowRunId()).isNotEqualTo(continued.workflowRunId());
    assertThat(workflow().provisioningReceipt()).isEqualTo(original);
  }

  @Test
  void queuedOldCommandDrainsBeforeHigherFenceSwitchesTheChild() {
    startWorkflow();
    ProvisionRoomEpoch firstEpoch = provisioning(RoomType.EVIDENCE, 0, 1, 0, 0, 0);
    ProvisionRoomEpochReceipt firstReceipt = provision(firstEpoch);
    CaseCommandRef oldCommand = command(1, RoomType.EVIDENCE, 0);
    ledger.put(oldCommand);
    ProvisionRoomEpoch nextEpoch = provisioning(RoomType.HEARING, 0, 2, 1, 1, 0);

    environment.getWorkerFactory().suspendPolling();
    CompletableFuture<Void> oldCommandUpdate =
        CompletableFuture.runAsync(() -> workflow().acceptCommand(oldCommand));
    sleepBriefly();
    CompletableFuture<ProvisionRoomEpochReceipt> nextProvision =
        CompletableFuture.supplyAsync(() -> provision(nextEpoch));
    sleepBriefly();
    environment.getWorkerFactory().resumePolling();
    CompletableFuture.allOf(oldCommandUpdate, nextProvision).join();

    CaseProcessSnapshot switched =
        awaitProcess(
            snapshot ->
                snapshot.activeRoomType() == RoomType.HEARING
                    && snapshot.activeFencingToken() == 2);
    assertThat(switched.activeChildWorkflowId()).isEqualTo(nextEpoch.roomWorkflowId());
    RoomControlSnapshot oldRoom =
        awaitRoom(firstReceipt.roomWorkflowId(), snapshot -> snapshot.processedCommandCount() == 1);
    assertThat(oldRoom.recentCommandIds()).containsExactly(oldCommand.commandId());
  }

  private PreparedProjectionRecovery prepareProjectionRecovery() {
    CaseProcessWorkflow recoveryWorkflow =
        client.newWorkflowStub(
            CaseProcessWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID)
                .setTaskQueue(RECOVERY_TASK_QUEUE)
                .build());
    WorkflowClient.start(recoveryWorkflow::run, (CaseProcessCarryState) null);
    ProvisionRoomEpochReceipt provisioned =
        provision(recoveryWorkflow, projectionRecoveryProvisioning());
    CaseCommandRef command = projectionRecoveryCommand();
    ledger.put(command);
    recoveryWorkflow.acceptCommand(command);
    CaseProcessSnapshot afterCommand =
        awaitProcess(
            recoveryWorkflow,
            snapshot ->
                snapshot.nextCommandSequence() == 2
                    && snapshot.observedProcessRevision() == 1
                    && Long.valueOf(1).equals(snapshot.activeRoomRevision()));
    assertThat(afterCommand.activeChildKind()).isEqualTo(ActiveChildKind.TARGET_TYPED_ROOM);

    CaseDomainEventRef event = projectionRecoveryEvent();
    recoveryWorkflow.domainEventCommitted(event);
    CaseProcessSnapshot failed =
        awaitProcess(
            recoveryWorkflow,
            snapshot ->
                "INTAKE_PROCESS_PROJECTION_COMPLETION_FAILED"
                        .equals(snapshot.protocolErrorCode())
                    && snapshot.protocolErrorOrigin() == RecoveryErrorOrigin.DOMAIN_EVENT);
    assertThat(failed.workflowRunId()).isEqualTo(provisioned.caseWorkflowRunId());
    assertThat(failed.nextCaseEventSequence()).isEqualTo(1);
    assertThat(failed.processedEventCount()).isZero();
    assertThat(failed.bufferedEventCount()).isEqualTo(1);
    assertThat(recoveryProjection.completionCalls.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.commandDispatches.get()).isEqualTo(1);
    assertThat(RecoveryTargetCaseProcessWorkflow.eventDispatches.get()).isEqualTo(1);

    CompleteConsumedIntakeProjectionCommand projectionCommand =
        new CompleteConsumedIntakeProjectionCommand(
            "complete-consumed-intake-projection.v1",
            TENANT,
            CASE_ID,
            event.eventId(),
            event.caseEventSequence(),
            event.eventType(),
            command.caseCommandSequence(),
            failed.activeRoomEpoch(),
            failed.activeFencingToken(),
            failed.observedProcessRevision(),
            failed.activeRoomRevision(),
            failed.workflowId(),
            failed.workflowRunId(),
            failed.activeChildWorkflowRunId());
    assertThat(recoveryProjection.commands).containsExactly(projectionCommand);
    CaseProcessIntakeProjectionRecoveryRequest request =
        new CaseProcessIntakeProjectionRecoveryRequest(
            CaseProcessIntakeProjectionRecoveryRequest.SCHEMA_VERSION,
            failed.workflowId(),
            failed.workflowRunId(),
            failed.workflowRunId(),
            TENANT,
            CASE_ID,
            RoomType.INTAKE,
            failed.activeRoomEpoch(),
            failed.activeFencingToken(),
            failed.activeChildWorkflowId(),
            failed.activeChildWorkflowRunId(),
            failed.observedProcessRevision(),
            failed.activeRoomRevision(),
            failed.nextCommandSequence(),
            failed.nextCaseEventSequence(),
            failed.processedCommandCount(),
            failed.processedEventCount(),
            new ProcessedCommandIdentity(
                command.commandId(), command.caseCommandSequence(), command.requestHash()),
            event,
            projectionCommand);
    return new PreparedProjectionRecovery(recoveryWorkflow, request, failed);
  }

  private void awaitTerminalNoCommitConvergences(int expectedCount) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (ledger.terminalNoCommitConvergences.size() == expectedCount) {
        return;
      }
      sleepBriefly();
    }
    throw new AssertionError("terminal-no-commit convergence count did not settle");
  }

  private static void assertTerminalNoCommitAccountingUnchanged(
      CaseProcessSnapshot expected, CaseProcessSnapshot actual) {
    assertThat(actual.observedProcessRevision()).isEqualTo(expected.observedProcessRevision());
    assertThat(actual.activeRoomRevision()).isEqualTo(expected.activeRoomRevision());
    assertThat(actual.nextCommandSequence()).isEqualTo(expected.nextCommandSequence());
    assertThat(actual.nextCaseEventSequence()).isEqualTo(expected.nextCaseEventSequence());
    assertThat(actual.processedCommandCount()).isEqualTo(expected.processedCommandCount());
    assertThat(actual.processedEventCount()).isEqualTo(expected.processedEventCount());
    assertThat(actual.pendingCommandCount()).isZero();
    assertThat(actual.bufferedEventCount()).isZero();
    assertThat(actual.recentCommandIds()).isEqualTo(expected.recentCommandIds());
    assertThat(actual.protocolErrorCode()).isNull();
  }

  private static TargetIntakeCommandTerminalNoCommit terminalNoCommitAuthority(
      CaseCommandRef command, CaseProcessSnapshot snapshot) {
    return new TargetIntakeCommandTerminalNoCommit(
        TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION,
        TENANT,
        CASE_ID,
        RoomType.INTAKE,
        snapshot.activeRoomEpoch(),
        snapshot.activeFencingToken(),
        snapshot.activeChildWorkflowId(),
        snapshot.activeChildWorkflowRunId(),
        snapshot.activeRoomWorkflowBuildId(),
        "activation-terminal-no-commit",
        "1".repeat(64),
        "case-process-recovery-test.v1",
        snapshot.activeRoomWorkflowBuildId(),
        "agent-terminal-no-commit.v1",
        "2".repeat(64),
        "graph-terminal-no-commit.v1",
        "3".repeat(64),
        "4".repeat(64),
        "5".repeat(64),
        "6".repeat(64),
        command.commandId(),
        command.caseCommandSequence(),
        command.requestHash(),
        "message-terminal-no-commit",
        command.payloadRef().uri(),
        command.payloadRef().sha256(),
        command.expectedProcessRevision(),
        command.expectedProcessRevision() + 1,
        0,
        1,
        0L,
        0,
        "logical-run-terminal-no-commit",
        "attempt-terminal-no-commit-1",
        "attempt-terminal-no-commit-1",
        1,
        AgentRunAttemptStatus.ABORTED,
        ExecuteAgentRunResult.Outcome.FAILED,
        "GRAPH_STREAM_PROTOCOL_REJECTED",
        false,
        AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
        3,
        true,
        OCCURRED_AT.plusSeconds(2));
  }

  private static ProvisionRoomEpoch projectionRecoveryProvisioning() {
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-intake-projection-recovery",
        TENANT,
        CASE_ID,
        "room-intake-projection-recovery",
        RoomType.INTAKE,
        1,
        0,
        0,
        11,
        "ACTIVE",
        "INTAKE",
        "ACTIVE",
        WriterMode.TEMPORAL,
        WORKFLOW_ID,
        CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, 1),
        TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
        TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
        TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
        "case-process-recovery-test.v1",
        TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
        "intake-room-recovery-test.v1",
        TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
        TargetTypedRoomProtocol.STREAM_PROTOCOL,
        0,
        0,
        1,
        1,
        OCCURRED_AT.plusSeconds(3_600),
        null,
        null,
        OCCURRED_AT);
  }

  private static CaseCommandRef projectionRecoveryCommand() {
    return new CaseCommandRef(
        "case-command-ref.v1",
        "command-intake-projection-recovery",
        TENANT,
        CASE_ID,
        1,
        CommandType.INTAKE_MESSAGE,
        RoomType.INTAKE,
        1,
        new ActorRef("user-case-process", ActorRole.USER, List.of("intake:message")),
        new PayloadRef(
            "intake-command.v1",
            "urn:test:intake:projection-recovery-command",
            "c".repeat(64),
            32),
        0,
        OCCURRED_AT.plusSeconds(1),
        OCCURRED_AT.plusSeconds(3_600),
        "00-11111111111111111111111111111111-2222222222222222-01",
        "c".repeat(64));
  }

  private static CaseDomainEventRef projectionRecoveryEvent() {
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "event-intake-projection-recovery",
        TENANT,
        CASE_ID,
        1,
        "INTAKE_TURN_READY_TO_CONFIRM",
        RoomType.INTAKE,
        1,
        new PayloadRef(
            "intake-formal-event.v1",
            "urn:test:intake:projection-recovery-event",
            "d".repeat(64),
            32),
        OCCURRED_AT.plusSeconds(2),
        "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
  }

  private TemporalUpdateGateway.DeliveryReceipt startWith(CaseCommandRef command) {
    startWorkflow();
    provision(
        provisioning(
            command.roomType(), command.roomEpoch(), 1, command.expectedProcessRevision(), 0, 0));
    var gateway = new SdkTemporalUpdateGateway(client);
    return gateway.deliver(
        new TemporalUpdateGateway.UpdateWithStartRequest(
            WORKFLOW_ID,
            CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
            CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
            command.commandId(),
            command));
  }

  private void startWorkflow() {
    startWorkflow(WORKFLOW_ID);
  }

  private void startWorkflow(String workflowId) {
    CaseProcessWorkflow workflow =
        client.newWorkflowStub(
            CaseProcessWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                .build());
    WorkflowClient.start(workflow::run, (CaseProcessCarryState) null);
  }

  private ProvisionRoomEpochReceipt provision(ProvisionRoomEpoch request) {
    return provision(workflow(), request);
  }

  private ProvisionRoomEpochReceipt provision(
      CaseProcessWorkflow targetWorkflow, ProvisionRoomEpoch request) {
    return WorkflowStub.fromTyped(targetWorkflow)
        .startUpdate(
            UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                .setUpdateName(CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
                .setUpdateId(request.updateId())
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build(),
            request)
        .getResult();
  }

  private static ProvisionRoomEpoch provisioning(
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      long initialProcessRevision,
      long lastCommandSequence,
      long lastCaseEventSequence) {
    String suffix = roomType.name().toLowerCase() + "-" + roomEpoch;
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-" + suffix,
        TENANT,
        CASE_ID,
        "room-" + suffix,
        roomType,
        roomEpoch,
        initialProcessRevision,
        0,
        fencingToken,
        "ACTIVE",
        roomType.name(),
        "ACTIVE",
        WriterMode.SHADOW,
        WORKFLOW_ID,
        CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, roomType, roomEpoch),
        "room-epoch-selection.v1",
        "case-process-contract.v1",
        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
        "case-control.v1",
        roomType.name().toLowerCase() + ".v2",
        "1.0.0",
        "checkpoint.v1",
        "agent-stream.v2",
        lastCommandSequence,
        lastCaseEventSequence,
        lastCommandSequence + 1,
        lastCaseEventSequence + 1,
        OCCURRED_AT.plusSeconds(3600),
        null,
        null,
        OCCURRED_AT.plusSeconds(fencingToken));
  }

  private static ProvisionRoomEpoch v2Provisioning() {
    ProvisionRoomEpoch v1 = provisioning(RoomType.INTAKE, 0, 1, 0, 0, 0);
    return new ProvisionRoomEpoch(
        v1.schemaVersion(),
        v1.epochId(),
        v1.tenantSurrogate(),
        v1.caseId(),
        v1.roomId(),
        v1.roomType(),
        v1.roomEpoch(),
        v1.initialProcessRevision(),
        v1.initialRoomRevision(),
        v1.fencingToken(),
        v1.macroPhase(),
        v1.currentRoom(),
        v1.roomPhase(),
        v1.writerMode(),
        v1.caseWorkflowId(),
        v1.roomWorkflowId(),
        "room-epoch-selection.v2",
        v1.processContractVersion(),
        v1.workflowType(),
        v1.temporalBuildId(),
        "IntakeRoomWorkflow",
        "intake-room.synthetic.v1",
        v1.graphKey(),
        "2.0.0",
        "intake-checkpoint.v2",
        v1.streamProtocol(),
        v1.lastCommandSequence(),
        v1.lastCaseEventSequence(),
        v1.firstCommandSequence(),
        v1.firstCaseEventSequence(),
        v1.projectedDeadlineAt(),
        v1.projectionRef(),
        v1.projectionSha256(),
        v1.requestedAt());
  }

  private static ProvisionRoomEpoch withGraphVersion(
      ProvisionRoomEpoch request, String graphVersion) {
    return new ProvisionRoomEpoch(
        request.schemaVersion(),
        request.epochId(),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomId(),
        request.roomType(),
        request.roomEpoch(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.fencingToken(),
        request.macroPhase(),
        request.currentRoom(),
        request.roomPhase(),
        request.writerMode(),
        request.caseWorkflowId(),
        request.roomWorkflowId(),
        request.selectionSchemaVersion(),
        request.processContractVersion(),
        request.workflowType(),
        request.temporalBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.graphKey(),
        graphVersion,
        request.checkpointSchemaVersion(),
        request.streamProtocol(),
        request.lastCommandSequence(),
        request.lastCaseEventSequence(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        request.projectedDeadlineAt(),
        request.projectionRef(),
        request.projectionSha256(),
        request.requestedAt());
  }

  private CaseProcessWorkflow workflow() {
    return client.newWorkflowStub(CaseProcessWorkflow.class, WORKFLOW_ID);
  }

  private CaseProcessSnapshot awaitProcess(Predicate<CaseProcessSnapshot> predicate) {
    return awaitProcess(workflow(), predicate);
  }

  private CaseProcessSnapshot awaitProcess(
      CaseProcessWorkflow targetWorkflow, Predicate<CaseProcessSnapshot> predicate) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    RuntimeException lastFailure = null;
    CaseProcessSnapshot lastSnapshot = null;
    while (System.nanoTime() < deadline) {
      try {
        CaseProcessSnapshot snapshot = targetWorkflow.state();
        lastSnapshot = snapshot;
        if (predicate.test(snapshot)) {
          return snapshot;
        }
      } catch (RuntimeException exception) {
        lastFailure = exception;
      }
      sleepBriefly();
    }
    throw new AssertionError(
        "case process state did not converge; last snapshot=" + lastSnapshot, lastFailure);
  }

  private static void awaitWorkflowCompleted(WorkflowStub workflow) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (workflow.describe().getStatus() == WORKFLOW_EXECUTION_STATUS_COMPLETED) {
        return;
      }
      sleepBriefly();
    }
    throw new AssertionError("workflow did not complete");
  }

  private RoomControlSnapshot awaitRoom(
      String childWorkflowId, Predicate<RoomControlSnapshot> predicate) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    RuntimeException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        RoomControlWorkflow room =
            client.newWorkflowStub(RoomControlWorkflow.class, childWorkflowId);
        RoomControlSnapshot snapshot = room.state();
        if (predicate.test(snapshot)) {
          return snapshot;
        }
      } catch (RuntimeException exception) {
        lastFailure = exception;
      }
      sleepBriefly();
    }
    throw new AssertionError("room control state did not converge", lastFailure);
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test interrupted", exception);
    }
  }

  private static CaseCommandRef command(int sequence, RoomType roomType, long roomEpoch) {
    return command(sequence, CASE_ID, roomType, roomEpoch);
  }

  private static CaseCommandRef command(
      int sequence, String caseId, RoomType roomType, long roomEpoch) {
    char hashCharacter = Character.forDigit(sequence % 16, 16);
    return new CaseCommandRef(
        "case-command-ref.v1",
        "command-" + sequence,
        TENANT,
        caseId,
        sequence,
        CommandType.EVIDENCE_SUBMIT,
        roomType,
        roomEpoch,
        new ActorRef("user-case-process", ActorRole.USER, List.of("case:command")),
        new PayloadRef(
            "case-process-command.v1",
            "urn:test:command:" + sequence,
            String.valueOf(hashCharacter).repeat(64),
            32),
        Math.max(0, sequence - 1L),
        OCCURRED_AT.plusSeconds(sequence),
        OCCURRED_AT.plusSeconds(3600 + sequence),
        "00-11111111111111111111111111111111-2222222222222222-01",
        String.valueOf(hashCharacter).repeat(64));
  }

  private static CaseCommandRef expiredCommand(int sequence) {
    CaseCommandRef command = command(sequence, RoomType.EVIDENCE, 0);
    return new CaseCommandRef(
        command.schemaVersion(),
        command.commandId(),
        command.tenantSurrogate(),
        command.caseId(),
        command.caseCommandSequence(),
        command.commandType(),
        command.roomType(),
        command.roomEpoch(),
        command.actorRef(),
        command.payloadRef(),
        command.expectedProcessRevision(),
        OCCURRED_AT.minusSeconds(1),
        OCCURRED_AT,
        command.traceparent(),
        command.requestHash());
  }

  private static CaseCommandRef shortLivedCommand(int sequence) {
    CaseCommandRef command = command(sequence, RoomType.EVIDENCE, 0);
    return new CaseCommandRef(
        command.schemaVersion(),
        command.commandId(),
        command.tenantSurrogate(),
        command.caseId(),
        command.caseCommandSequence(),
        command.commandType(),
        command.roomType(),
        command.roomEpoch(),
        command.actorRef(),
        command.payloadRef(),
        command.expectedProcessRevision(),
        OCCURRED_AT.minusSeconds(1),
        OCCURRED_AT.plusSeconds(1),
        command.traceparent(),
        command.requestHash());
  }

  private static CaseDomainEventRef event(int sequence, RoomType roomType, long roomEpoch) {
    char hashCharacter = Character.forDigit((sequence + 8) % 16, 16);
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "event-" + sequence,
        TENANT,
        CASE_ID,
        sequence,
        "TEST_EVENT",
        roomType,
        roomEpoch,
        new PayloadRef(
            "case-domain-event.v1",
            "urn:test:event:" + sequence,
            String.valueOf(hashCharacter).repeat(64),
            16),
        OCCURRED_AT.plusSeconds(sequence),
        "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
  }

  private record PreparedProjectionRecovery(
      CaseProcessWorkflow workflow,
      CaseProcessIntakeProjectionRecoveryRequest request,
      CaseProcessSnapshot failed) {}

  public static final class RecoveryTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessWorkflow {

    private static final AtomicInteger startCalls = new AtomicInteger();
    private static final AtomicInteger commandDispatches = new AtomicInteger();
    private static final AtomicInteger eventDispatches = new AtomicInteger();
    private static final AtomicInteger closeCalls = new AtomicInteger();

    private static void reset() {
      startCalls.set(0);
      commandDispatches.set(0);
      eventDispatches.set(0);
      closeCalls.set(0);
    }

    @Override
    protected TargetTypedRoomChildHandle startTargetTypedRoomChild(
        ProvisionRoomEpoch request, String provisioningHash) {
      startCalls.incrementAndGet();
      return new RecordingTargetHandle(
          WorkflowExecution.newBuilder()
              .setWorkflowId(request.roomWorkflowId())
              .setRunId("intake-projection-recovery-child-run")
              .build(),
          request.roomType(),
          request.roomEpoch(),
          request.fencingToken(),
          request.initialProcessRevision(),
          request.initialRoomRevision());
    }

    @Override
    protected TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
        ActiveChildDescriptor descriptor) {
      return new RecordingTargetHandle(
          WorkflowExecution.newBuilder()
              .setWorkflowId(descriptor.workflowId())
              .setRunId(descriptor.startedRunId())
              .build(),
          descriptor.roomType(),
          descriptor.roomEpoch(),
          descriptor.fencingToken(),
          descriptor.currentProcessRevision(),
          descriptor.currentRoomRevision());
    }

    private static final class RecordingTargetHandle implements TargetTypedRoomChildHandle {
      private final WorkflowExecution execution;
      private final RoomType roomType;
      private final long roomEpoch;
      private final long fencingToken;
      private long processRevision;
      private long roomRevision;

      private RecordingTargetHandle(
          WorkflowExecution execution,
          RoomType roomType,
          long roomEpoch,
          long fencingToken,
          long processRevision,
          long roomRevision) {
        this.execution = execution;
        this.roomType = roomType;
        this.roomEpoch = roomEpoch;
        this.fencingToken = fencingToken;
        this.processRevision = processRevision;
        this.roomRevision = roomRevision;
      }

      @Override
      public WorkflowExecution execution() {
        return execution;
      }

      @Override
      public TargetTypedRoomDispatchReceipt commandAccepted(CaseCommandRef command) {
        commandDispatches.incrementAndGet();
        processRevision = Math.max(processRevision, command.expectedProcessRevision() + 1);
        roomRevision++;
        return receipt();
      }

      @Override
      public TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event) {
        eventDispatches.incrementAndGet();
        return receipt();
      }

      @Override
      public String initiatorActorScopeHash() {
        return "a".repeat(64);
      }

      @Override
      public String respondentActorScopeHash() {
        return "b".repeat(64);
      }

      @Override
      public void close(String reason) {
        closeCalls.incrementAndGet();
      }

      private TargetTypedRoomDispatchReceipt receipt() {
        return new TargetTypedRoomDispatchReceipt(
            roomType, roomEpoch, fencingToken, processRevision, roomRevision);
      }
    }
  }

  private static final class RecoveryProjectionActivities implements ProcessProjectionActivities {
    private final AtomicInteger applyCalls = new AtomicInteger();
    private final AtomicInteger completionCalls = new AtomicInteger();
    private final List<CompleteConsumedIntakeProjectionCommand> commands =
        new CopyOnWriteArrayList<>();

    @Override
    public ApplyProjectionResult apply(ApplyProjectionCommand command) {
      applyCalls.incrementAndGet();
      throw new UnsupportedOperationException("fenced projection is outside this recovery fixture");
    }

    @Override
    public CompleteConsumedIntakeProjectionResult completeConsumedIntakeProjection(
        CompleteConsumedIntakeProjectionCommand command) {
      commands.add(command);
      int call = completionCalls.incrementAndGet();
      if (call == 1) {
        throw ApplicationFailure.newNonRetryableFailure(
            "fixture leaves the routed formal event at the acknowledged recovery boundary",
            "TEST_INTAKE_PROJECTION_COMPLETION_FAILED");
      }
      return new CompleteConsumedIntakeProjectionResult(
          "complete-consumed-intake-projection-result.v1",
          command.eventId(),
          command.caseEventSequence(),
          CompleteConsumedIntakeProjectionOutcome.APPLIED,
          command.lastCommandSequence(),
          command.processRevision(),
          command.roomRevision(),
          command.roomEpoch(),
          command.fencingToken(),
          command.temporalWorkflowId(),
          command.firstExecutionRunId(),
          command.activeChildRunId(),
          "urn:test:intake:projection-recovery-result",
          "e".repeat(64),
          OCCURRED_AT.plusSeconds(call));
    }
  }

  private static final class RecordingLedgerActivities
      implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {

    private final ConcurrentSkipListMap<Long, CaseCommandRef> commands =
        new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, CaseCommandLedgerState> commandStates =
        new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, CaseDomainEventRef> events =
        new ConcurrentSkipListMap<>();
    private final List<LoadSequenceRange> commandLoads = new CopyOnWriteArrayList<>();
    private final List<LoadSequenceRange> eventLoads = new CopyOnWriteArrayList<>();
    private final List<SequenceGapReport> gapReports = new CopyOnWriteArrayList<>();
    private final List<ExpireCaseCommand> expirations = new CopyOnWriteArrayList<>();
    private final List<ConvergeTargetIntakeTerminalNoCommit> terminalNoCommitConvergences =
        new CopyOnWriteArrayList<>();
    private final List<TerminalNoCommitOutcome> terminalNoCommitOutcomes =
        new CopyOnWriteArrayList<>();
    private volatile boolean invalidCommandResponse;
    private volatile boolean invalidEventResponse;
    private volatile boolean rejectNextTerminalNoCommit;

    void put(CaseCommandRef command) {
      commands.put(command.caseCommandSequence(), command);
      commandStates.put(
          command.caseCommandSequence(), CaseCommandLedgerState.PENDING_ORCHESTRATION);
    }

    void put(CaseCommandRef command, CaseCommandLedgerState state) {
      commands.put(command.caseCommandSequence(), command);
      commandStates.put(command.caseCommandSequence(), state);
    }

    void put(CaseDomainEventRef event) {
      events.put(event.caseEventSequence(), event);
    }

    @Override
    public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
      commandLoads.add(request);
      if (invalidCommandResponse) {
        return List.of(
            command(Math.toIntExact(request.toSequenceInclusive() + 1), RoomType.EVIDENCE, 0));
      }
      return new ArrayList<>(
          commands
              .subMap(request.fromSequenceInclusive(), true, request.toSequenceInclusive(), true)
              .values());
    }

    @Override
    public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(LoadSequenceRange request) {
      commandLoads.add(request);
      if (invalidCommandResponse) {
        CaseCommandRef invalid =
            command(Math.toIntExact(request.toSequenceInclusive() + 1), RoomType.EVIDENCE, 0);
        return List.of(
            new CaseCommandLedgerEntry(
                "case-command-ledger-entry.v1",
                invalid,
                CaseCommandLedgerState.PENDING_ORCHESTRATION));
      }
      return commands
          .subMap(request.fromSequenceInclusive(), true, request.toSequenceInclusive(), true)
          .values()
          .stream()
          .map(
              command ->
                  new CaseCommandLedgerEntry(
                      "case-command-ledger-entry.v1",
                      command,
                      commandStates.get(command.caseCommandSequence())))
          .toList();
    }

    @Override
    public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
      eventLoads.add(request);
      if (invalidEventResponse) {
        return List.of(
            event(Math.toIntExact(request.toSequenceInclusive() + 1), RoomType.EVIDENCE, 0));
      }
      return new ArrayList<>(
          events
              .subMap(request.fromSequenceInclusive(), true, request.toSequenceInclusive(), true)
              .values());
    }

    @Override
    public void reportSequenceGap(SequenceGapReport report) {
      gapReports.add(report);
    }

    @Override
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
      expirations.add(request);
      commandStates.put(request.caseCommandSequence(), CaseCommandLedgerState.EXPIRED);
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
      commandStates.put(request.caseCommandSequence(), CaseCommandLedgerState.SHADOW_COMPLETED);
      return new RecordCaseCommandRoutedResult(
          "record-case-command-routed-result.v1", CommandLifecycleOutcome.SHADOW_COMPLETED);
    }

    @Override
    public ConvergeTargetIntakeTerminalNoCommitResult convergeTargetIntakeTerminalNoCommit(
        ConvergeTargetIntakeTerminalNoCommit request) {
      TargetIntakeCommandTerminalNoCommit authority = request.authority();
      TargetIntakeCommandTerminalNoCommit firstAuthority =
          terminalNoCommitConvergences.isEmpty()
              ? null
              : terminalNoCommitConvergences.getFirst().authority();
      boolean acknowledgedUpgrade =
          firstAuthority != null
              && TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
                  firstAuthority.schemaVersion())
              && TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                  authority.schemaVersion())
              && firstAuthority.equals(authority.asObservedV2Authority());
      if (firstAuthority != null
          && !firstAuthority.equals(authority)
          && !acknowledgedUpgrade) {
        throw ApplicationFailure.newNonRetryableFailure(
            "terminal-no-commit authority changed", "TerminalNoCommitAuthorityConflict");
      }
      if (rejectNextTerminalNoCommit) {
        rejectNextTerminalNoCommit = false;
        terminalNoCommitConvergences.add(request);
        throw ApplicationFailure.newNonRetryableFailure(
            "projection source cursor is stale",
            "TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE");
      }
      TerminalNoCommitOutcome outcome =
          terminalNoCommitConvergences.isEmpty()
              ? TerminalNoCommitOutcome.TERMINALIZED
              : TerminalNoCommitOutcome.IDEMPOTENT_REPLAY;
      terminalNoCommitConvergences.add(request);
      terminalNoCommitOutcomes.add(outcome);
      return new ConvergeTargetIntakeTerminalNoCommitResult(
          "converge-target-intake-terminal-no-commit-result.v1",
          outcome,
          authority,
          authority.receiptUri(),
          authority.receiptSha256(),
          authority.newProcessRevision(),
          authority.newRoomRevision(),
          authority.caseCommandSequence(),
          authority.lastCaseEventSequence());
    }

    @Override
    public ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
        ResolveTargetIntakeTerminalNoCommit request) {
      throw new AssertionError("CaseProcess signal convergence must not use Room recovery read");
    }
  }
}
