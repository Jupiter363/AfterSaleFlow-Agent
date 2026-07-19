package com.example.dispute.workflow.agentrun;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivity;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivity;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflowImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunWorkerRecoveryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:05:00Z");

    private TestWorkflowEnvironment environment;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() throws Exception {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(AGENT_EXECUTION);
        worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
        activities = new RecordingActivities(request(), completedResult(request()));
        worker.registerActivitiesImplementations(activities);
        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void finalizerRetryDoesNotReexecuteTheCompletedModelActivity() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunWorkflow workflow = environment.getWorkflowClient()
                .newWorkflowStub(
                        AgentRunWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId("agent-run-v2:" + request.logicalRunId())
                                .setTaskQueue(AGENT_EXECUTION)
                                .build());

        ExecuteAgentRunResult result = workflow.run(request);

        assertThat(result).isEqualTo(activities.result);
        assertThat(activities.executeCalls).hasValue(1);
        assertThat(activities.finalizerCalls).hasValue(2);
        assertThat(activities.finalizationCommits).hasValue(1);
    }

    @Test
    void temporalActivityRecoveryKeepsTheAttemptAndCommandIdentityStable() throws Exception {
        activities.failFirstExecution = true;
        ExecuteAgentRunRequest request = request();
        AgentRunWorkflow workflow = environment.getWorkflowClient()
                .newWorkflowStub(
                        AgentRunWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId("agent-run-v2:" + request.logicalRunId())
                                .setTaskQueue(AGENT_EXECUTION)
                                .build());

        ExecuteAgentRunResult result = workflow.run(request);

        assertThat(result).isEqualTo(activities.result);
        assertThat(activities.executeCalls).hasValue(2);
        assertThat(activities.executionRequests)
                .allSatisfy(replayed -> {
                    assertThat(replayed.agentRunId()).isEqualTo(request.agentRunId());
                    assertThat(replayed.attemptId()).isEqualTo(request.attemptId());
                    assertThat(replayed.command().commandId())
                            .isEqualTo(request.command().commandId());
                });
        assertThat(activities.finalizerCalls).hasValue(2);
        assertThat(activities.finalizationCommits).hasValue(1);
    }

    private static final class RecordingActivities
            implements ExecuteAgentRunActivity, FinalizeAgentRunActivity {

        private final ExecuteAgentRunRequest request;
        private final ExecuteAgentRunResult result;
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger finalizerCalls = new AtomicInteger();
        private final AtomicInteger finalizationCommits = new AtomicInteger();
        private final List<ExecuteAgentRunRequest> executionRequests =
                new CopyOnWriteArrayList<>();
        private volatile boolean failFirstExecution;

        private RecordingActivities(
                ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
            this.request = request;
            this.result = result;
        }

        @Override
        public ExecuteAgentRunResult execute(ExecuteAgentRunRequest actualRequest) {
            assertThat(actualRequest).isEqualTo(request);
            executionRequests.add(actualRequest);
            if (executeCalls.incrementAndGet() == 1 && failFirstExecution) {
                throw ApplicationFailure.newFailure(
                        "worker response was lost",
                        "AgentRunRetryableFailure");
            }
            return result;
        }

        @Override
        public AgentRunFinalizationReceipt finalizeResult(
                ExecuteAgentRunRequest actualRequest, ExecuteAgentRunResult actualResult) {
            assertThat(actualRequest).isEqualTo(request);
            assertThat(actualResult).isEqualTo(result);
            if (finalizerCalls.incrementAndGet() == 1) {
                finalizationCommits.incrementAndGet();
                throw ApplicationFailure.newFailure(
                        "completion was lost after the idempotent commit",
                        "AgentRunFinalizationInfrastructure");
            }
            return new AgentRunFinalizationReceipt(
                    AgentRunFinalizationReceipt.SCHEMA_VERSION,
                    request.agentRunId(),
                    request.logicalRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    7,
                    result.resultHash(),
                    "manifest-001",
                    "a".repeat(64),
                    result.lastSequenceNo(),
                    CommitStatus.COMMITTED,
                    NOW);
        }
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                command);
    }

    private static ExecuteAgentRunResult completedResult(ExecuteAgentRunRequest request)
            throws Exception {
        RoomGraphResult graphResult = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                2,
                true,
                null,
                false,
                null,
                NOW);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
