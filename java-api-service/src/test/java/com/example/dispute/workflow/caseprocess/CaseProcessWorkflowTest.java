package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.WorkflowExecutionHistory;
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
        environment = TestWorkflowEnvironment.newInstance();
        Worker caseWorker =
                environment.newWorker(
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
        caseWorker.registerWorkflowImplementationTypes(CaseProcessWorkflowImpl.class);
        ledger = new RecordingLedgerActivities();
        caseWorker.registerActivitiesImplementations(ledger);
        Worker roomWorker =
                environment.newWorker(
                        CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
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

        CaseProcessSnapshot state =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
        assertThat(receipt.temporalRunId()).isNotBlank();
        assertThat(state.processedCommandCount()).isEqualTo(2);
        assertThat(state.pendingCommandCount()).isZero();
        assertThat(state.blockedReason()).isEqualTo("NONE");
        assertThat(state.activeChildWorkflowId())
                .isEqualTo(
                        CaseProcessWorkflowProtocol.roomWorkflowId(
                                CASE_ID, RoomType.EVIDENCE, 0));
        assertThat(ledger.commandLoads)
                .anySatisfy(
                        range -> {
                            assertThat(range.fromSequenceInclusive()).isEqualTo(1);
                            assertThat(range.toSequenceInclusive()).isGreaterThanOrEqualTo(1);
                        });

        RoomControlSnapshot room =
                awaitRoom(
                        state.activeChildWorkflowId(),
                        snapshot -> snapshot.processedCommandCount() == 2);
        assertThat(room.recentCommandIds())
                .containsExactly("command-1", "command-2");
    }

    @Test
    void outOfOrderDomainEventLoadsTheGapAndDuplicateSignalDoesNotAdvanceTwice() {
        CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
        ledger.put(command);
        startWith(command);
        CaseProcessSnapshot started =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        CaseDomainEventRef first = event(1, RoomType.EVIDENCE, 0);
        CaseDomainEventRef second = event(2, RoomType.EVIDENCE, 0);
        CaseDomainEventRef third = event(3, RoomType.EVIDENCE, 0);
        ledger.put(first);
        ledger.put(second);
        ledger.put(third);

        workflow().domainEventCommitted(third);

        CaseProcessSnapshot recovered =
                awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 4);
        assertThat(recovered.processedEventCount()).isEqualTo(3);
        assertThat(recovered.bufferedEventCount()).isZero();
        assertThat(ledger.eventLoads).isNotEmpty();

        workflow().domainEventCommitted(second);
        CaseProcessSnapshot afterDuplicate =
                awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 4);
        assertThat(afterDuplicate.processedEventCount()).isEqualTo(3);
        assertThat(afterDuplicate.blockedReason()).isEqualTo("NONE");

        RoomControlSnapshot room =
                awaitRoom(
                        started.activeChildWorkflowId(),
                        snapshot -> snapshot.processedEventCount() == 3);
        assertThat(room.recentEventIds())
                .containsExactly("event-1", "event-2", "event-3");
    }

    @Test
    void unresolvedEventGapBecomesVisibleAndManualRetryRecoversIt() {
        CaseCommandRef command = command(1, RoomType.EVIDENCE, 0);
        ledger.put(command);
        startWith(command);
        awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        workflow().domainEventCommitted(event(4, RoomType.EVIDENCE, 0));

        CaseProcessSnapshot blocked =
                awaitProcess(
                        snapshot ->
                                "EVENT_GAP_MANUAL_RECOVERY".equals(
                                        snapshot.blockedReason()));
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

        CaseProcessSnapshot recovered =
                awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 5);
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
                awaitProcess(
                        snapshot ->
                                "COMMAND_GAP_MANUAL_RECOVERY".equals(
                                        snapshot.blockedReason()));
        assertThat(blocked.protocolErrorCode())
                .isEqualTo("COMMAND_LEDGER_RESPONSE_INVALID");
        assertThat(blocked.nextCommandSequence()).isEqualTo(1);

        ledger.invalidCommandResponse = false;
        ledger.put(first);
        workflow().retrySequenceGap();

        CaseProcessSnapshot recovered =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
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
                awaitProcess(
                        snapshot ->
                                "EVENT_GAP_MANUAL_RECOVERY".equals(
                                        snapshot.blockedReason()));
        assertThat(blocked.protocolErrorCode())
                .isEqualTo("DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
        assertThat(blocked.nextCaseEventSequence()).isEqualTo(1);

        ledger.invalidEventResponse = false;
        for (int sequence = 1; sequence <= 4; sequence++) {
            ledger.put(event(sequence, RoomType.EVIDENCE, 0));
        }
        workflow().retrySequenceGap();

        CaseProcessSnapshot recovered =
                awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 5);
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
        CaseProcessSnapshot started =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        CompletableFuture<Void> thirdUpdate =
                CompletableFuture.runAsync(() -> workflow().acceptCommand(third));
        CompletableFuture<Void> secondUpdate =
                CompletableFuture.runAsync(() -> workflow().acceptCommand(second));
        CompletableFuture.allOf(thirdUpdate, secondUpdate).join();

        CaseProcessSnapshot completed =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 4);
        assertThat(completed.processedCommandCount()).isEqualTo(3);
        RoomControlSnapshot room =
                awaitRoom(
                        started.activeChildWorkflowId(),
                        snapshot -> snapshot.processedCommandCount() == 3);
        assertThat(room.recentCommandIds())
                .containsExactly("command-1", "command-2", "command-3");
    }

    @Test
    void aNewRoomEpochGetsADistinctStableChildWorkflow() {
        CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
        CaseCommandRef second = command(2, RoomType.EVIDENCE, 1);
        ledger.put(first);
        ledger.put(second);
        startWith(first);
        CaseProcessSnapshot firstEpoch =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        workflow().acceptCommand(second);

        CaseProcessSnapshot secondEpoch =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
        assertThat(secondEpoch.activeChildWorkflowId())
                .isEqualTo(
                        CaseProcessWorkflowProtocol.roomWorkflowId(
                                CASE_ID, RoomType.EVIDENCE, 1))
                .isNotEqualTo(firstEpoch.activeChildWorkflowId());
        RoomControlSnapshot room =
                awaitRoom(
                        secondEpoch.activeChildWorkflowId(),
                        snapshot -> snapshot.processedCommandCount() == 1);
        assertThat(room.roomEpoch()).isEqualTo(1);
        assertThat(room.recentCommandIds()).containsExactly("command-2");
    }

    @Test
    void continueAsNewKeepsTheRecentCommandCarryStateBounded() {
        CaseCommandRef last = null;
        for (int sequence = 1; sequence <= 260; sequence++) {
            last = command(sequence, RoomType.EVIDENCE, 0);
            ledger.put(last);
        }
        startWith(last);

        CaseProcessSnapshot before =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 261);
        assertThat(before.runGeneration()).isZero();
        assertThat(before.recentCommandCount()).isEqualTo(256);
        assertThat(before.recentCommandIds())
                .hasSize(256)
                .startsWith("command-5")
                .endsWith("command-260")
                .doesNotContain("command-1", "command-2", "command-3", "command-4");

        workflow().requestContinueAsNew();

        CaseProcessSnapshot continued =
                awaitProcess(snapshot -> snapshot.runGeneration() == 1);
        assertThat(continued.recentCommandCount()).isEqualTo(256);
        assertThat(continued.recentCommandIds())
                .hasSize(256)
                .startsWith("command-5")
                .endsWith("command-260");
    }

    @Test
    void continueAsNewCarriesStateAndReconcilesCrossRunReplayAgainstJavaLedger() {
        CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
        ledger.put(first);
        startWith(first);
        CaseProcessSnapshot before =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        workflow().requestContinueAsNew();
        CaseProcessSnapshot continued =
                awaitProcess(
                        snapshot ->
                                snapshot.runGeneration() == 1
                                        && !snapshot.workflowRunId()
                                                .equals(before.workflowRunId()));
        assertThat(continued.nextCommandSequence()).isEqualTo(2);
        assertThat(continued.activeChildWorkflowId())
                .isEqualTo(before.activeChildWorkflowId());

        int loadsBeforeReplay = ledger.commandLoads.size();
        workflow().acceptCommand(first);
        CaseProcessSnapshot afterReplay =
                awaitProcess(snapshot -> snapshot.runGeneration() == 1);
        assertThat(afterReplay.processedCommandCount()).isEqualTo(1);
        assertThat(ledger.commandLoads.size()).isGreaterThan(loadsBeforeReplay);

        CaseCommandRef second = command(2, RoomType.EVIDENCE, 0);
        ledger.put(second);
        workflow().acceptCommand(second);
        CaseProcessSnapshot afterSecond =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 3);
        assertThat(afterSecond.runGeneration()).isEqualTo(1);

        RoomControlSnapshot room =
                awaitRoom(
                        afterSecond.activeChildWorkflowId(),
                        snapshot -> snapshot.processedCommandCount() == 2);
        assertThat(room.recentCommandIds())
                .containsExactly("command-1", "command-2");
    }

    @Test
    void runAgeTimerContinuesAsNewAndTheCapturedClosedHistoryReplays()
            throws Exception {
        CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
        ledger.put(first);
        startWith(first);
        CaseProcessSnapshot initial =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        environment.sleep(Duration.ofHours(24));

        CaseProcessSnapshot continued =
                awaitProcess(
                        snapshot ->
                                snapshot.runGeneration() == 1
                                        && !snapshot.workflowRunId()
                                                .equals(initial.workflowRunId()));
        WorkflowExecutionHistory captured =
                client.fetchHistory(WORKFLOW_ID, initial.workflowRunId());
        WorkflowExecutionHistory serializedCapture =
                WorkflowExecutionHistory.fromJson(captured.toJson(true), WORKFLOW_ID);

        assertThat(captured.getWorkflowExecution().getWorkflowId())
                .isEqualTo(WORKFLOW_ID);
        assertThat(serializedCapture.getLastEvent().getEventType())
                .isEqualTo(EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW);
        assertThat(continued.nextCommandSequence()).isEqualTo(2);
        assertThat(continued.processedCommandCount()).isEqualTo(1);

        WorkflowReplayer.replayWorkflowExecution(
                serializedCapture, CaseProcessWorkflowImpl.class);
    }

    @Test
    void commandForAnotherWorkflowScopeIsRejectedWithoutMutatingState() {
        CaseCommandRef first = command(1, RoomType.EVIDENCE, 0);
        ledger.put(first);
        startWith(first);
        CaseProcessSnapshot before =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
        CaseCommandRef wrongCase = command(2, "CASE_Other", RoomType.EVIDENCE, 0);

        assertThatThrownBy(() -> workflow().acceptCommand(wrongCase))
                .isInstanceOf(WorkflowUpdateException.class);

        CaseProcessSnapshot after =
                awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);
        assertThat(after.processedCommandCount()).isEqualTo(before.processedCommandCount());
        assertThat(after.activeChildWorkflowId()).isEqualTo(before.activeChildWorkflowId());
    }

    private TemporalUpdateGateway.DeliveryReceipt startWith(CaseCommandRef command) {
        var gateway = new SdkTemporalUpdateGateway(client);
        return gateway.deliver(
                new TemporalUpdateGateway.UpdateWithStartRequest(
                        WORKFLOW_ID,
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        command.commandId(),
                        command));
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
                "case process state did not converge; last snapshot=" + lastSnapshot,
                lastFailure);
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

    private static CaseCommandRef command(
            int sequence, RoomType roomType, long roomEpoch) {
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

    private static CaseDomainEventRef event(
            int sequence, RoomType roomType, long roomEpoch) {
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
            implements CaseProcessLedgerActivities {

        private final ConcurrentSkipListMap<Long, CaseCommandRef> commands =
                new ConcurrentSkipListMap<>();
        private final ConcurrentSkipListMap<Long, CaseDomainEventRef> events =
                new ConcurrentSkipListMap<>();
        private final List<LoadSequenceRange> commandLoads = new CopyOnWriteArrayList<>();
        private final List<LoadSequenceRange> eventLoads = new CopyOnWriteArrayList<>();
        private final List<SequenceGapReport> gapReports = new CopyOnWriteArrayList<>();
        private volatile boolean invalidCommandResponse;
        private volatile boolean invalidEventResponse;

        void put(CaseCommandRef command) {
            commands.put(command.caseCommandSequence(), command);
        }

        void put(CaseDomainEventRef event) {
            events.put(event.caseEventSequence(), event);
        }

        @Override
        public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
            commandLoads.add(request);
            if (invalidCommandResponse) {
                return List.of(
                        command(
                                Math.toIntExact(request.toSequenceInclusive() + 1),
                                RoomType.EVIDENCE,
                                0));
            }
            return new ArrayList<>(
                    commands
                            .subMap(
                                    request.fromSequenceInclusive(),
                                    true,
                                    request.toSequenceInclusive(),
                                    true)
                            .values());
        }

        @Override
        public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
            eventLoads.add(request);
            if (invalidEventResponse) {
                return List.of(
                        event(
                                Math.toIntExact(request.toSequenceInclusive() + 1),
                                RoomType.EVIDENCE,
                                0));
            }
            return new ArrayList<>(
                    events
                            .subMap(
                                    request.fromSequenceInclusive(),
                                    true,
                                    request.toSequenceInclusive(),
                                    true)
                            .values());
        }

        @Override
        public void reportSequenceGap(SequenceGapReport report) {
            gapReports.add(report);
        }
    }
}
