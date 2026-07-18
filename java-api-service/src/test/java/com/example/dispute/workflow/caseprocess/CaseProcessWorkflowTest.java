package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW;
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
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateDeliveryException;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.WorkflowExecutionHistory;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseProcessWorkflowTest {

  private static final String TENANT = "tenant-case-process";
  private static final String CASE_ID = "CASE_ProcessWorkflow";
  private static final String WORKFLOW_ID =
      CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-17T08:00:00Z");

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private RecordingLedgerActivities ledger;

  @BeforeEach
  void setUp() {
    environment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setInitialTime(OCCURRED_AT).build());
    Worker caseWorker = environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
    caseWorker.registerWorkflowImplementationTypes(CaseProcessWorkflowImpl.class);
    ledger = new RecordingLedgerActivities();
    caseWorker.registerActivitiesImplementations(ledger);
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
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    RuntimeException lastFailure = null;
    CaseProcessSnapshot lastSnapshot = null;
    while (System.nanoTime() < deadline) {
      try {
        CaseProcessSnapshot snapshot = workflow().state();
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
    private volatile boolean invalidCommandResponse;
    private volatile boolean invalidEventResponse;

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
  }
}
