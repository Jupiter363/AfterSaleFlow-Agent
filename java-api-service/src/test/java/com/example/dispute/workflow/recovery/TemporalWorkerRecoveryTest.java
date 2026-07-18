package com.example.dispute.workflow.recovery;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningGateway;
import com.example.dispute.workflow.infrastructure.bootstrap.SdkRoomEpochProvisioningGateway;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.temporal.EvidenceWindowActivities;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.activity.Activity;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemporalWorkerRecoveryTest {

    private static final String EVIDENCE_QUEUE = "evidence-worker-recovery";
    private static final String TENANT = "tenant-worker-recovery";
    private static final String CASE_ID = "CASE_WorkerRecovery";
    private static final int WORKFLOW_POLLER_COUNT = 2;
    private static final String CASE_WORKFLOW_ID =
            CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-17T08:00:00Z");

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private WorkerFactory workerFactory;
    private final List<WorkerFactory> workerFactories = new ArrayList<>();
    private final AtomicInteger workerGeneration = new AtomicInteger();
    private final AtomicInteger drainSequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        environment =
                TestWorkflowEnvironment.newInstance(
                        TestEnvironmentOptions.newBuilder().setInitialTime(OCCURRED_AT).build());
        environment.start();
        client = environment.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        shutdownWorkers();
        environment.close();
    }

    @Test
    void timerFiresWhileWorkerPollingIsSuspendedAndRunsAfterPollingResumes() {
        RecordingEvidenceActivities activities = new RecordingEvidenceActivities();
        startEvidenceWorkers(activities);
        EvidenceWindowWorkflow workflow = evidenceWorkflow("CASE_TIMER_RESTART");
        WorkflowClient.start(
                workflow::run, new EvidenceWindowCommand("CASE_TIMER_RESTART", Duration.ofHours(2)));
        awaitHistoryEvent("evidence-window-CASE_TIMER_RESTART", EVENT_TYPE_TIMER_STARTED, 1);

        quiesceActiveWorkers(List.of("evidence-window-CASE_TIMER_RESTART"), EVIDENCE_QUEUE);
        environment.sleep(Duration.ofMinutes(90).plusSeconds(1));
        awaitHistoryEvent("evidence-window-CASE_TIMER_RESTART", EVENT_TYPE_TIMER_FIRED, 1);
        assertThat(activities.warnedCases).isEmpty();
        resumeActiveWorkers();

        awaitCondition(() -> activities.warnedCases.contains("CASE_TIMER_RESTART"));
        workflow.partyCompleted("USER");
        workflow.partyCompleted("MERCHANT");

        EvidenceWindowResult result = evidenceResult(workflow);
        assertThat(result.stopReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(activities.warnedCases).containsExactly("CASE_TIMER_RESTART");
        assertThat(activities.expiredCases).isEmpty();
    }

    @Test
    void activityTimeoutReadsTheCommittedEffectAfterItsFirstCompletionIsLost() {
        CommitThenLoseCompletionEvidenceActivities activities =
                new CommitThenLoseCompletionEvidenceActivities();
        startEvidenceWorkers(activities);
        EvidenceWindowWorkflow workflow = evidenceWorkflow("CASE_ACTIVITY_RETRY");
        WorkflowClient.start(
                workflow::run, new EvidenceWindowCommand("CASE_ACTIVITY_RETRY", Duration.ofMinutes(31)));

        environment.sleep(Duration.ofMinutes(1).plusSeconds(1));
        awaitCondition(() -> activities.warnAttempts.get() >= 1);
        environment.sleep(Duration.ofMinutes(2));
        awaitActivityAttempt("evidence-window-CASE_ACTIVITY_RETRY", 2);
        awaitCondition(() -> activities.warnAttempts.get() >= 2);
        workflow.partyCompleted("USER");
        workflow.partyCompleted("MERCHANT");

        EvidenceWindowResult result = evidenceResult(workflow);
        assertThat(result.stopReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(activities.warnAttempts).hasValue(2);
        assertThat(activities.committedWarnings).containsExactly("CASE_ACTIVITY_RETRY");
    }

    @Test
    void signalAcceptedWhileWorkerPollingIsSuspendedAdvancesParentAndRoomExactlyOnce() {
        RecoveryLedgerActivities ledger = new RecoveryLedgerActivities();
        CaseCommandRef command = command(1);
        ledger.put(command);
        startControlWorkers(ledger);
        provisionCaseWorkflow();

        new SdkTemporalUpdateGateway(client)
                .deliver(
                        new TemporalUpdateGateway.UpdateWithStartRequest(
                                CASE_WORKFLOW_ID,
                                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                                command.commandId(),
                                command));
        CaseProcessSnapshot started = awaitProcess(snapshot -> snapshot.nextCommandSequence() == 2);

        quiesceActiveWorkers(
                List.of(CASE_WORKFLOW_ID, started.activeChildWorkflowId()),
                CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
        CaseProcessWorkflow workflow =
                client.newWorkflowStub(CaseProcessWorkflow.class, CASE_WORKFLOW_ID);
        CaseDomainEventRef first = event(1);
        workflow.domainEventCommitted(first);
        workflow.domainEventCommitted(first);
        workflow.domainEventCommitted(event(2));
        awaitHistoryEvent(CASE_WORKFLOW_ID, EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, 3);
        resumeActiveWorkers();

        CaseProcessSnapshot recovered = awaitProcess(snapshot -> snapshot.nextCaseEventSequence() == 3);
        RoomControlSnapshot room =
                awaitRoom(started.activeChildWorkflowId(), snapshot -> snapshot.processedEventCount() == 2);
        assertThat(recovered.processedEventCount()).isEqualTo(2);
        assertThat(room.recentEventIds())
                .containsExactly("event-worker-recovery-1", "event-worker-recovery-2");
        assertThat(
                        client.fetchHistory(CASE_WORKFLOW_ID).getEvents().stream()
                                .filter(
                                        historyEvent ->
                                                historyEvent.getEventType() == EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
                                .count())
                .isEqualTo(3);
    }

    private void startEvidenceWorkers(EvidenceWindowActivities activities) {
        workerFactory = newWorkerFactory();
        Worker worker = workerFactory.newWorker(EVIDENCE_QUEUE, recoveryWorkerOptions());
        worker.registerWorkflowImplementationTypes(
                EvidenceWindowWorkflowImpl.class, PollDrainWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        workerFactory.start();
        advancePastStickyFallbackAfterRestart();
    }

    private void startControlWorkers(CaseProcessLedgerActivities activities) {
        workerFactory = newWorkerFactory();
        Worker caseWorker =
                workerFactory.newWorker(
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE, recoveryWorkerOptions());
        caseWorker.registerWorkflowImplementationTypes(
                CaseProcessWorkflowImpl.class, PollDrainWorkflowImpl.class);
        caseWorker.registerActivitiesImplementations(activities);
        Worker roomWorker =
                workerFactory.newWorker(
                        CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE, recoveryWorkerOptions());
        roomWorker.registerWorkflowImplementationTypes(
                RoomControlWorkflowImpl.class, PollDrainWorkflowImpl.class);
        workerFactory.start();
        advancePastStickyFallbackAfterRestart();
    }

    private void provisionCaseWorkflow() {
        ProvisionRoomEpoch command = provisioning();
        RoomEpochBootstrapProperties properties =
                new RoomEpochBootstrapProperties(
                        true,
                        1,
                        1,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        SdkRoomEpochProvisioningGateway gateway =
                new SdkRoomEpochProvisioningGateway(client, properties);
        try {
            gateway.provision(
                    new RoomEpochProvisioningGateway.ProvisioningRequest(
                            CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                            CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                            command.updateId(),
                            command.payloadSha256(),
                            command));
        } finally {
            gateway.closeExecutor();
        }
    }

    private void advancePastStickyFallbackAfterRestart() {
        if (workerGeneration.get() > 1) {
            environment.sleep(Duration.ofMillis(250));
        }
    }

    private WorkerFactory newWorkerFactory() {
        WorkflowClient workerClient =
                WorkflowClient.newInstance(
                        environment.getWorkflowServiceStubs(),
                        WorkflowClientOptions.newBuilder()
                                .setNamespace("default")
                                .setIdentity("worker-recovery-" + workerGeneration.incrementAndGet())
                                .build());
        WorkerFactory factory =
                WorkerFactory.newInstance(
                        workerClient,
                        WorkerFactoryOptions.newBuilder()
                                .setWorkflowCacheSize(0)
                                .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ofMillis(100))
                                .build());
        workerFactories.add(factory);
        return factory;
    }

    private static WorkerOptions recoveryWorkerOptions() {
        return WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskPollers(WORKFLOW_POLLER_COUNT)
                .setMaxConcurrentActivityTaskPollers(WORKFLOW_POLLER_COUNT)
                .setStickyQueueScheduleToStartTimeout(Duration.ofMillis(100))
                .build();
    }

    private void quiesceActiveWorkers(List<String> stickyWorkflowIds, String... taskQueues) {
        if (workerFactory == null) {
            return;
        }
        var testService =
                TestServiceGrpc.newBlockingStub(environment.getWorkflowServiceStubs().getRawChannel());
        testService.lockTimeSkipping(LockTimeSkippingRequest.getDefaultInstance());
        try {
            workerFactory.suspendPolling();
            for (String workflowId : stickyWorkflowIds) {
                client
                        .newUntypedWorkflowStub(workflowId)
                        .query(WorkflowClient.QUERY_TYPE_STACK_TRACE, String.class);
            }
            for (String taskQueue : taskQueues) {
                String token =
                        "poll-drain-" + workerGeneration.get() + "-" + drainSequence.incrementAndGet();
                PollDrainWorkflow workflow =
                        client.newWorkflowStub(
                                PollDrainWorkflow.class,
                                WorkflowOptions.newBuilder().setWorkflowId(token).setTaskQueue(taskQueue).build());
                WorkflowClient.start(workflow::run, token);
                try {
                    io.temporal.client.WorkflowStub.fromTyped(workflow)
                            .getResult(10, TimeUnit.SECONDS, Void.class);
                } catch (TimeoutException exception) {
                    throw new AssertionError(
                            "poll drain did not complete after suspension\n" + environment.getDiagnostics(),
                            exception);
                }
            }
        } finally {
            testService.unlockTimeSkipping(UnlockTimeSkippingRequest.getDefaultInstance());
        }
    }

    private void resumeActiveWorkers() {
        if (workerFactory == null) {
            throw new IllegalStateException("worker factory is not available");
        }
        workerFactory.resumePolling();
    }

    private void shutdownWorkers() {
        for (WorkerFactory factory : workerFactories) {
            factory.shutdownNow();
        }
        for (WorkerFactory factory : workerFactories) {
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
        workerFactories.clear();
        workerFactory = null;
    }

    private EvidenceWindowWorkflow evidenceWorkflow(String caseId) {
        return client.newWorkflowStub(
                EvidenceWindowWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("evidence-window-" + caseId)
                        .setTaskQueue(EVIDENCE_QUEUE)
                        .build());
    }

    private static EvidenceWindowResult evidenceResult(EvidenceWindowWorkflow workflow) {
        return io.temporal.client.WorkflowStub.fromTyped(workflow)
                .getResult(EvidenceWindowResult.class);
    }

    private CaseProcessSnapshot awaitProcess(Predicate<CaseProcessSnapshot> predicate) {
        CaseProcessWorkflow workflow =
                client.newWorkflowStub(CaseProcessWorkflow.class, CASE_WORKFLOW_ID);
        return awaitValue(workflow::state, predicate, "case process state");
    }

    private RoomControlSnapshot awaitRoom(
            String workflowId, Predicate<RoomControlSnapshot> predicate) {
        RoomControlWorkflow workflow = client.newWorkflowStub(RoomControlWorkflow.class, workflowId);
        return awaitValue(workflow::state, predicate, "room control state");
    }

    private void awaitHistoryEvent(
            String workflowId, io.temporal.api.enums.v1.EventType eventType, long expectedCount) {
        awaitCondition(
                () ->
                        client.fetchHistory(workflowId).getEvents().stream()
                                        .filter(event -> event.getEventType() == eventType)
                                        .count()
                                >= expectedCount);
    }

    private void awaitActivityAttempt(String workflowId, int expectedAttempt) {
        awaitCondition(
                () ->
                        client.fetchHistory(workflowId).getEvents().stream()
                                .filter(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_STARTED)
                                .anyMatch(
                                        event ->
                                                event.getActivityTaskStartedEventAttributes().getAttempt()
                                                        >= expectedAttempt));
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // The server may not have committed the event or query result yet.
            }
            sleepBriefly();
        }
        throw new AssertionError(
                "condition did not converge before timeout\n" + environment.getDiagnostics());
    }

    private <T> T awaitValue(
            java.util.function.Supplier<T> supplier, Predicate<T> predicate, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RuntimeException lastFailure = null;
        T lastValue = null;
        while (System.nanoTime() < deadline) {
            try {
                lastValue = supplier.get();
                if (predicate.test(lastValue)) {
                    return lastValue;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            sleepBriefly();
        }
        throw new AssertionError(
                description
                        + " did not converge; last value="
                        + lastValue
                        + "\n"
                        + environment.getDiagnostics(),
                lastFailure);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", exception);
        }
    }

    private static CaseCommandRef command(int sequence) {
        return new CaseCommandRef(
                "case-command-ref.v1",
                "command-worker-recovery-" + sequence,
                TENANT,
                CASE_ID,
                sequence,
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                0,
                new ActorRef("user-worker-recovery", ActorRole.USER, List.of("case:command")),
                new PayloadRef(
                        "worker-recovery-command.v1",
                        "urn:test:worker-recovery:" + sequence,
                        Integer.toHexString(sequence).repeat(64),
                        32),
                sequence - 1L,
                OCCURRED_AT.plusSeconds(sequence),
                OCCURRED_AT.plusSeconds(3600 + sequence),
                "00-11111111111111111111111111111111-2222222222222222-01",
                Integer.toHexString(sequence).repeat(64));
    }

    private static ProvisionRoomEpoch provisioning() {
        return new ProvisionRoomEpoch(
                ProvisionRoomEpoch.SCHEMA_VERSION,
                "epoch-worker-recovery",
                TENANT,
                CASE_ID,
                "room-worker-recovery",
                RoomType.EVIDENCE,
                0,
                0,
                0,
                1,
                "EVIDENCE",
                "EVIDENCE",
                "OPEN",
                WriterMode.SHADOW,
                CASE_WORKFLOW_ID,
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        CASE_ID, RoomType.EVIDENCE, 0),
                "room-epoch-selection.v1",
                "case-process-contract.v1",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "worker-recovery-build-v1",
                "evidence.v2",
                "1.0.0",
                "checkpoint.v1",
                "agent-stream.v2",
                0,
                0,
                1,
                1,
                OCCURRED_AT.plusSeconds(3600),
                null,
                null,
                OCCURRED_AT);
    }

    private static CaseDomainEventRef event(int sequence) {
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                "event-worker-recovery-" + sequence,
                TENANT,
                CASE_ID,
                sequence,
                "WORKER_RECOVERY_EVENT",
                RoomType.EVIDENCE,
                0,
                new PayloadRef(
                        "worker-recovery-event.v1",
                        "urn:test:worker-recovery:event:" + sequence,
                        "a".repeat(64),
                        16),
                OCCURRED_AT.plusSeconds(sequence),
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
    }

    @WorkflowInterface
    public interface PollDrainWorkflow {

        @WorkflowMethod
        void run(String token);
    }

    public static final class PollDrainWorkflowImpl implements PollDrainWorkflow {

        @Override
        public void run(String token) {}
    }

    private static class RecordingEvidenceActivities implements EvidenceWindowActivities {

        final List<String> warnedCases = new CopyOnWriteArrayList<>();
        final List<String> expiredCases = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void warn(String caseId) {
            if (!warnedCases.contains(caseId)) {
                warnedCases.add(caseId);
            }
        }

        @Override
        public synchronized void expire(String caseId) {
            if (!expiredCases.contains(caseId)) {
                expiredCases.add(caseId);
            }
        }
    }

    private static final class CommitThenLoseCompletionEvidenceActivities
            implements EvidenceWindowActivities {

        private final Set<String> committedWarnings = ConcurrentHashMap.newKeySet();
        private final AtomicInteger warnAttempts = new AtomicInteger();

        @Override
        public void warn(String caseId) {
            int attempt = warnAttempts.incrementAndGet();
            boolean committed = committedWarnings.add(caseId);
            if (attempt == 1 && committed) {
                Activity.getExecutionContext().doNotCompleteOnReturn();
            }
        }

        @Override
        public void expire(String caseId) {}
    }

    private static final class RecoveryLedgerActivities
            implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {

        private final ConcurrentSkipListMap<Long, CaseCommandRef> commands =
                new ConcurrentSkipListMap<>();

        void put(CaseCommandRef command) {
            commands.put(command.caseCommandSequence(), command);
        }

        @Override
        public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
            return new ArrayList<>(
                    commands
                            .subMap(request.fromSequenceInclusive(), true, request.toSequenceInclusive(), true)
                            .values());
        }

        @Override
        public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(LoadSequenceRange request) {
            return loadCaseCommands(request).stream()
                    .map(
                            command ->
                                    new CaseCommandLedgerEntry(
                                            "case-command-ledger-entry.v1",
                                            command,
                                            CaseCommandLedgerState.PENDING_ORCHESTRATION))
                    .toList();
        }

        @Override
        public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
            return List.of();
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
            return new RecordCaseCommandRoutedResult(
                    "record-case-command-routed-result.v1", CommandLifecycleOutcome.SHADOW_COMPLETED);
        }
    }
}
