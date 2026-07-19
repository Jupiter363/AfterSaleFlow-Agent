package com.example.dispute.workflow.agentrun;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivity;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivity;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflowImpl;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunLogicalAttemptWorkflowTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final ObjectMapper COMMAND_MAPPER = MAPPER.copy()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String LOGICAL_INPUT_HASH = "b".repeat(64);
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-19T07:00:00Z");

    private TestWorkflowEnvironment environment;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(AGENT_EXECUTION);
        worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void executesAttemptTwoInsideTheSameLogicalWorkflow() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-logical-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-logical-002");
        RunningWorkflow running = start(attemptOne);

        ExecuteAgentRunResult secondResult = update(running.workflow(), attemptTwo);

        assertThat(secondResult.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(attemptTwo.command().requestHash())
                .isNotEqualTo(attemptOne.command().requestHash());
        assertThat(attemptTwo.command().invocationContext().envelopeNonce())
                .isNotEqualTo(attemptOne.command().invocationContext().envelopeNonce());
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(secondResult);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
        assertThat(activities.finalizedAttempts).containsExactly(2L);
    }

    @Test
    void rejectsAChangedLogicalHashOrNonImmediatePredecessor() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-lineage-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-lineage-002");
        RunningWorkflow running = start(attemptOne);

        ExecuteAgentRunRequest changedHash = copyLineage(
                attemptTwo, "c".repeat(64), attemptOne.attemptId());
        assertThatThrownBy(() -> update(running.workflow(), changedHash))
                .isInstanceOf(WorkflowUpdateException.class);

        ExecuteAgentRunRequest skippedPredecessor = copyLineage(
                attemptTwo, LOGICAL_INPUT_HASH, "attempt-unrelated-001");
        assertThatThrownBy(() -> update(running.workflow(), skippedPredecessor))
                .isInstanceOf(WorkflowUpdateException.class);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L);
    }

    @Test
    void rejectsAnIncreasingResidualRetryBudget() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-budget-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-budget-002");
        RunningWorkflow running = start(attemptOne);

        ObjectNode commandJson = COMMAND_MAPPER.valueToTree(attemptTwo.command());
        ObjectNode retryBudget = commandJson.withObject("retry_budget");
        retryBudget.put(
                "provider_attempts_remaining",
                attemptOne.command().retryBudget().providerAttemptsRemaining() + 1);
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        RoomGraphCommand increased =
                COMMAND_MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        ExecuteAgentRunRequest invalid = new ExecuteAgentRunRequest(
                attemptTwo.schemaVersion(),
                attemptTwo.agentRunId(),
                attemptTwo.attemptNo(),
                attemptTwo.streamProtocol(),
                attemptTwo.logicalInputHash(),
                attemptTwo.previousAttemptId(),
                attemptTwo.resetRequired(),
                attemptTwo.publicSequenceOffset(),
                increased);

        assertThatThrownBy(() -> update(running.workflow(), invalid))
                .isInstanceOf(WorkflowUpdateException.class);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L);
    }

    @Test
    void rejectsABudgetIncreaseRelativeToTheImmediatePredecessor() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-budget-chain-001");
        ExecuteAgentRunRequest attemptTwo = withRetryBudget(
                request(2, "attempt-budget-chain-002"), 1, 1, 0);
        ExecuteAgentRunRequest attemptThree = withRetryBudget(
                request(3, "attempt-budget-chain-003"), 2, 2, 1);
        activities.retryableAttempts.add(2L);
        RunningWorkflow running = start(attemptOne);

        assertThat(update(running.workflow(), attemptTwo).recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        assertThatThrownBy(() -> update(running.workflow(), attemptThree))
                .isInstanceOf(WorkflowUpdateException.class);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
    }

    @Test
    void retrievesTheCompletedAttemptTwoUpdateAfterTheWorkflowCloses() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-closed-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-closed-002");
        RunningWorkflow running = start(attemptOne);

        ExecuteAgentRunResult completed = update(running.workflow(), attemptTwo);
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(completed);
        int executionsAfterClose = activities.executedRequests.size();

        ExecuteAgentRunResult retrieved = update(running.workflow(), attemptTwo);

        assertThat(retrieved).isEqualTo(completed);
        assertThat(activities.executedRequests).hasSize(executionsAfterClose);
    }

    @Test
    void acceptsOneAuthorizedSuccessorWhileThePredecessorUpdateIsFinishing()
            throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-serial-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-serial-002");
        ExecuteAgentRunRequest attemptThree = request(3, "attempt-serial-003");
        activities.retryableAttempts.add(2L);
        activities.blockAttemptNo = 2;
        RunningWorkflow running = start(attemptOne);

        CompletableFuture<ExecuteAgentRunResult> second =
                CompletableFuture.supplyAsync(() -> update(running.workflow(), attemptTwo));
        assertThat(activities.blockedAttemptEntered.await(5, TimeUnit.SECONDS)).isTrue();
        WorkflowUpdateHandle<ExecuteAgentRunResult> third =
                startAcceptedUpdate(running.workflow(), attemptThree);
        try {
            assertThat(activities.maximumConcurrentExecutions).hasValue(1);
            assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
        } finally {
            activities.releaseBlockedAttempt.countDown();
        }

        assertThat(second.get(5, TimeUnit.SECONDS).recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        ExecuteAgentRunResult completed = third.getResult();
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(completed);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L, 3L);
        assertThat(activities.maximumConcurrentExecutions).hasValue(1);
    }

    @Test
    void rejectsAnEarlyAcceptedSuccessorWhenThePredecessorDoesNotAuthorizeIt()
            throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-pending-terminal-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-pending-terminal-002");
        ExecuteAgentRunRequest attemptThree = request(3, "attempt-pending-terminal-003");
        activities.terminalFailureAttempts.add(2L);
        activities.blockAttemptNo = 2;
        RunningWorkflow running = start(attemptOne);

        CompletableFuture<ExecuteAgentRunResult> second =
                CompletableFuture.supplyAsync(() -> update(running.workflow(), attemptTwo));
        assertThat(activities.blockedAttemptEntered.await(5, TimeUnit.SECONDS)).isTrue();
        WorkflowUpdateHandle<ExecuteAgentRunResult> third =
                startAcceptedUpdate(running.workflow(), attemptThree);
        activities.releaseBlockedAttempt.countDown();

        ExecuteAgentRunResult terminal = second.get(5, TimeUnit.SECONDS);
        assertThat(terminal.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThatThrownBy(third::getResult)
                .isInstanceOf(WorkflowUpdateException.class);
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(terminal);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
    }

    @Test
    void deduplicatesAnAttemptAndRequiresANewCommandIdForEveryLogicalAttempt()
            throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-command-001");
        ExecuteAgentRunRequest reusedCommand =
                request(
                        2,
                        "attempt-command-reused",
                        attemptOne.command().commandId(),
                        attemptOne.attemptId());
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-command-002");
        ExecuteAgentRunRequest reusedByAttemptThree =
                request(
                        3,
                        "attempt-command-reused-003",
                        attemptTwo.command().commandId(),
                        attemptTwo.attemptId());
        ExecuteAgentRunRequest attemptThree = request(3, "attempt-command-003");
        activities.retryableAttempts.add(2L);
        RunningWorkflow running = start(attemptOne);

        assertThatThrownBy(() -> update(running.workflow(), reusedCommand))
                .isInstanceOf(WorkflowUpdateException.class);
        ExecuteAgentRunResult second = update(running.workflow(), attemptTwo);
        ExecuteAgentRunResult replay = update(running.workflow(), attemptTwo);
        assertThat(replay).isEqualTo(second);
        assertThatThrownBy(() -> update(running.workflow(), reusedByAttemptThree))
                .isInstanceOf(WorkflowUpdateException.class);

        ExecuteAgentRunResult third = update(running.workflow(), attemptThree);

        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(third);
        assertThat(activities.executedRequests)
                .extracting(request -> request.command().commandId())
                .containsExactly(
                        attemptOne.command().commandId(),
                        attemptTwo.command().commandId(),
                        attemptThree.command().commandId())
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsALateResultFromAnOlderAttemptBeforeFinalization() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-late-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-late-002");
        activities.staleResultOnAttemptNo = 2;
        activities.staleResultSource = attemptOne;
        RunningWorkflow running = start(attemptOne);

        assertThatThrownBy(() -> update(running.workflow(), attemptTwo))
                .isInstanceOf(WorkflowUpdateException.class);
        assertThatThrownBy(() -> running.result().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
        assertThat(activities.finalizedAttempts).isEmpty();
    }

    @Test
    void closesTheLogicalWorkflowAfterThreeAttemptsEvenIfTheLastResultClaimsRetryable()
            throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-bounded-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-bounded-002");
        ExecuteAgentRunRequest attemptThree = request(3, "attempt-bounded-003");
        activities.retryableAttempts.addAll(Set.of(2L, 3L));
        RunningWorkflow running = start(attemptOne);

        assertThat(update(running.workflow(), attemptTwo).retryable()).isTrue();
        ExecuteAgentRunResult third = update(running.workflow(), attemptThree);

        assertThat(third.retryable()).isTrue();
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(third);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L, 3L);
        assertThat(activities.maximumConcurrentExecutions).hasValue(1);
    }

    @Test
    void failLogicalRunActionClosesBeforeTheAttemptBudgetIsExhausted() throws Exception {
        ExecuteAgentRunRequest attemptOne = request(1, "attempt-terminal-001");
        ExecuteAgentRunRequest attemptTwo = request(2, "attempt-terminal-002");
        ExecuteAgentRunRequest attemptThree = request(3, "attempt-terminal-003");
        activities.terminalFailureAttempts.add(2L);
        RunningWorkflow running = start(attemptOne);

        ExecuteAgentRunResult terminal = update(running.workflow(), attemptTwo);

        assertThat(terminal.retryable()).isFalse();
        assertThat(terminal.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(running.result().get(5, TimeUnit.SECONDS)).isEqualTo(terminal);
        assertThatThrownBy(() -> update(running.workflow(), attemptThree))
                .isInstanceOfAny(
                        WorkflowUpdateException.class,
                        WorkflowNotFoundException.class);
        assertThat(activities.executedAttemptNumbers()).containsExactly(1L, 2L);
    }

    private RunningWorkflow start(ExecuteAgentRunRequest attemptOne) throws Exception {
        AgentRunWorkflow workflow =
                environment
                        .getWorkflowClient()
                        .newWorkflowStub(
                                AgentRunWorkflow.class,
                                WorkflowOptions.newBuilder()
                                        .setWorkflowId(
                                                "agent-run-v2:" + attemptOne.logicalRunId())
                                        .setTaskQueue(AGENT_EXECUTION)
                                        .build());
        CompletableFuture<ExecuteAgentRunResult> result =
                WorkflowClient.execute(workflow::run, attemptOne);
        assertThat(activities.firstAttemptFinished.await(5, TimeUnit.SECONDS)).isTrue();
        return new RunningWorkflow(workflow, result);
    }

    private static ExecuteAgentRunResult update(
            AgentRunWorkflow workflow, ExecuteAgentRunRequest request) {
        return WorkflowStub.fromTyped(workflow)
                .startUpdate(
                        UpdateOptions.newBuilder(ExecuteAgentRunResult.class)
                                .setUpdateName(AgentRunWorkflow.ATTEMPT_UPDATE)
                                .setUpdateId(request.attemptId())
                                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                                .build(),
                        request)
                .getResult();
    }

    private static WorkflowUpdateHandle<ExecuteAgentRunResult> startAcceptedUpdate(
            AgentRunWorkflow workflow, ExecuteAgentRunRequest request) {
        return WorkflowStub.fromTyped(workflow)
                .startUpdate(
                        UpdateOptions.newBuilder(ExecuteAgentRunResult.class)
                                .setUpdateName(AgentRunWorkflow.ATTEMPT_UPDATE)
                                .setUpdateId(request.attemptId())
                                .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                                .build(),
                        request);
    }

    private static ExecuteAgentRunRequest request(long attemptNo, String attemptId)
            throws Exception {
        RoomGraphCommand fixture = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return request(attemptNo, attemptId, fixture.commandId() + "-" + attemptNo);
    }

    private static ExecuteAgentRunRequest request(
            long attemptNo, String attemptId, String commandId) throws Exception {
        return request(attemptNo, attemptId, commandId, predecessorId(attemptNo, attemptId));
    }

    private static ExecuteAgentRunRequest request(
            long attemptNo,
            String attemptId,
            String commandId,
            String previousAttemptId) throws Exception {
        RoomGraphCommand fixture = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        InvocationContext context = fixture.invocationContext();
        InvocationContext attemptContext =
                new InvocationContext(
                        context.agentProfileId(),
                        context.promptProfileId(),
                        context.modelProfileId(),
                        context.outputSchemaVersion(),
                        context.policyVersion(),
                        context.guardrailVersion(),
                        context.toolCapabilities(),
                        context.envelopeKeyId(),
                        context.envelopeNonce() + "-" + attemptNo);
        RoomGraphCommand unsigned =
                new RoomGraphCommand(
                        fixture.schemaVersion(),
                        commandId,
                        fixture.logicalRunId(),
                        attemptId,
                        fixture.tenantSurrogate(),
                        fixture.caseId(),
                        fixture.roomType(),
                        fixture.roomEpoch(),
                        fixture.graphKey(),
                        fixture.graphVersion(),
                        fixture.checkpointSchemaVersion(),
                        fixture.threadId(),
                        fixture.actorScope(),
                        fixture.processRevision(),
                        fixture.stageCode(),
                        fixture.stageSequence(),
                        fixture.domainSnapshotRef(),
                        fixture.eventRef(),
                        attemptContext,
                        fixture.retryBudget(),
                        Instant.parse("2099-01-01T00:00:00Z"),
                        fixture.traceparent(),
                        "0".repeat(64));
        ObjectNode commandJson = COMMAND_MAPPER.valueToTree(unsigned);
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        RoomGraphCommand command = COMMAND_MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                attemptNo,
                "agent-stream.v2",
                LOGICAL_INPUT_HASH,
                previousAttemptId,
                false,
                0,
                command);
    }

    private static String predecessorId(long attemptNo, String attemptId) {
        if (attemptNo == 1) {
            return null;
        }
        int separator = attemptId.lastIndexOf('-');
        if (separator < 0) {
            throw new IllegalArgumentException("test attemptId has no numeric suffix");
        }
        return attemptId.substring(0, separator + 1) + "%03d".formatted(attemptNo - 1);
    }

    private static ExecuteAgentRunRequest copyLineage(
            ExecuteAgentRunRequest request,
            String logicalInputHash,
            String previousAttemptId) {
        return new ExecuteAgentRunRequest(
                request.schemaVersion(),
                request.agentRunId(),
                request.attemptNo(),
                request.streamProtocol(),
                logicalInputHash,
                previousAttemptId,
                request.resetRequired(),
                request.publicSequenceOffset(),
                request.command());
    }

    private static ExecuteAgentRunRequest withRetryBudget(
            ExecuteAgentRunRequest request,
            int providerAttempts,
            int activityAttempts,
            int repairs) throws Exception {
        ObjectNode commandJson = COMMAND_MAPPER.valueToTree(request.command());
        ObjectNode retryBudget = commandJson.withObject("retry_budget");
        retryBudget.put("provider_attempts_remaining", providerAttempts);
        retryBudget.put("activity_attempts_remaining", activityAttempts);
        retryBudget.put("repairs_remaining", repairs);
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        RoomGraphCommand command =
                COMMAND_MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                request.schemaVersion(),
                request.agentRunId(),
                request.attemptNo(),
                request.streamProtocol(),
                request.logicalInputHash(),
                request.previousAttemptId(),
                request.resetRequired(),
                request.publicSequenceOffset(),
                command);
    }

    private static ExecuteAgentRunResult completedResult(ExecuteAgentRunRequest request)
            throws Exception {
        RoomGraphResult graph = completedGraph(request);
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graph,
                graph.outputHash(),
                2,
                true,
                null,
                false,
                null,
                NOW.plusSeconds(request.attemptNo()));
    }

    private static ExecuteAgentRunResult failedResult(ExecuteAgentRunRequest request) {
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
                request.attemptNo() > 1,
                "PROVIDER_UNAVAILABLE",
                true,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                NOW.plusSeconds(request.attemptNo()));
    }

    private static ExecuteAgentRunResult terminalFailureResult(
            ExecuteAgentRunRequest request) {
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
                false,
                "GRAPH_COMMAND_HASH_CONFLICT",
                false,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                NOW.plusSeconds(request.attemptNo()));
    }

    private static RoomGraphResult completedGraph(ExecuteAgentRunRequest request) throws Exception {
        RoomGraphResult fixture = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        return new RoomGraphResult(
                fixture.schemaVersion(),
                request.command().commandId(),
                request.logicalRunId(),
                request.attemptId(),
                fixture.graphKey(),
                fixture.graphVersion(),
                fixture.checkpointId(),
                fixture.cognitiveRevision(),
                fixture.status(),
                fixture.publicEventProposals(),
                fixture.artifactOperations(),
                fixture.needsInput(),
                fixture.needsReview(),
                fixture.error(),
                fixture.outputHash(),
                fixture.usage(),
                fixture.executionMetadata());
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }

    private record RunningWorkflow(
            AgentRunWorkflow workflow,
            CompletableFuture<ExecuteAgentRunResult> result) {}

    private static final class RecordingActivities
            implements ExecuteAgentRunActivity, FinalizeAgentRunActivity {

        private final List<ExecuteAgentRunRequest> executedRequests =
                new CopyOnWriteArrayList<>();
        private final List<Long> finalizedAttempts = new CopyOnWriteArrayList<>();
        private final Set<Long> retryableAttempts = ConcurrentHashMap.newKeySet();
        private final Set<Long> terminalFailureAttempts = ConcurrentHashMap.newKeySet();
        private final CountDownLatch firstAttemptFinished = new CountDownLatch(1);
        private final CountDownLatch blockedAttemptEntered = new CountDownLatch(1);
        private final CountDownLatch releaseBlockedAttempt = new CountDownLatch(1);
        private final AtomicInteger concurrentExecutions = new AtomicInteger();
        private final AtomicInteger maximumConcurrentExecutions = new AtomicInteger();

        private volatile long blockAttemptNo = -1;
        private volatile long staleResultOnAttemptNo = -1;
        private volatile ExecuteAgentRunRequest staleResultSource;

        private RecordingActivities() {
            retryableAttempts.add(1L);
        }

        @Override
        public ExecuteAgentRunResult execute(ExecuteAgentRunRequest request) {
            int concurrent = concurrentExecutions.incrementAndGet();
            maximumConcurrentExecutions.accumulateAndGet(concurrent, Math::max);
            executedRequests.add(request);
            try {
                if (request.attemptNo() == 1) {
                    firstAttemptFinished.countDown();
                }
                if (request.attemptNo() == blockAttemptNo) {
                    blockedAttemptEntered.countDown();
                    await(releaseBlockedAttempt);
                }
                if (request.attemptNo() == staleResultOnAttemptNo) {
                    return completedResult(staleResultSource);
                }
                if (terminalFailureAttempts.contains(request.attemptNo())) {
                    return terminalFailureResult(request);
                }
                return retryableAttempts.contains(request.attemptNo())
                        ? failedResult(request)
                        : completedResult(request);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                concurrentExecutions.decrementAndGet();
            }
        }

        @Override
        public AgentRunFinalizationReceipt finalizeResult(
                ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
            finalizedAttempts.add(request.attemptNo());
            return new AgentRunFinalizationReceipt(
                    AgentRunFinalizationReceipt.SCHEMA_VERSION,
                    request.agentRunId(),
                    request.logicalRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    7,
                    result.resultHash(),
                    "manifest-logical-001",
                    "a".repeat(64),
                    result.lastSequenceNo(),
                    CommitStatus.COMMITTED,
                    result.completedAt());
        }

        private List<Long> executedAttemptNumbers() {
            return executedRequests.stream().map(ExecuteAgentRunRequest::attemptNo).toList();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for the test release");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test activity was interrupted", interrupted);
            }
        }
    }
}
