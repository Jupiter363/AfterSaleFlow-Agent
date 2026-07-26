package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureClassifier;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivityImpl;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalizeAgentRunActivityTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:05:00Z");

    @Test
    void returnsCommittedOrReplayReceiptFromTheDomainGateway() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationReceipt receipt = receipt(request, result, CommitStatus.ALREADY_COMMITTED);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenReturn(receipt);

        var activity = new FinalizeAgentRunActivityImpl(gateway);

        assertThat(activity.finalizeResult(request, result)).isEqualTo(receipt);
        verify(gateway).finalizeResult(request, result);
    }

    @Test
    void deterministicFenceRejectionIsNonRetryable() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result))
                .thenThrow(new IllegalStateException("stale room fence"));

        assertThatThrownBy(() ->
                        new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure applicationFailure = (ApplicationFailure) failure;
                    assertThat(applicationFailure.getType())
                            .isEqualTo(FinalizeAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
                    assertThat(applicationFailure.isNonRetryable()).isTrue();
                });
    }

    @Test
    void nonIntakeInfrastructureFailureEscapesForTemporalFinalizerOnlyRetry() throws Exception {
        ExecuteAgentRunRequest request = nonIntakeRequest(request());
        ExecuteAgentRunResult result = result(request);
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenThrow(databaseFailure);

        assertThatThrownBy(() ->
                        new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result))
                .isSameAs(databaseFailure);
    }

    @Test
    void intakeTypedRejectionsPreserveTheirExactNonRetryableCodes() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ExecuteAgentRunResult result = result(request);

        for (String code : List.of(
                "INTAKE_STALE_FENCE",
                "INTAKE_AGENT_SESSION_REVOKED",
                "INTAKE_PROPOSAL_SCHEMA_INVALID",
                "INTAKE_FINALIZATION_OPERATION_CONFLICT")) {
            ApplicationFailure failure = invokeFailure(
                    request,
                    result,
                    new IntakeFinalizationRejectedException(code, "typed rejection"));

            assertThat(failure.getType()).isEqualTo(code);
            assertThat(failure.isNonRetryable()).isTrue();
        }
    }

    @Test
    void intakeTypedResourceFailuresRemainRetryable() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ExecuteAgentRunResult result = result(request);

        ApplicationFailure proposal = invokeFailure(
                request,
                result,
                new IntakeProposalLoadException(
                        "object store temporarily unavailable", new RuntimeException("timeout")));
        ApplicationFailure database = invokeFailure(
                request,
                result,
                new IntakeFinalizationPersistenceException(
                        "database temporarily unavailable", new RuntimeException("connection")));

        assertThat(proposal.getType()).isEqualTo(IntakeProposalLoadException.CODE);
        assertThat(proposal.isNonRetryable()).isFalse();
        assertThat(database.getType()).isEqualTo(IntakeFinalizationPersistenceException.CODE);
        assertThat(database.isNonRetryable()).isFalse();
    }

    @Test
    void unknownIntakeRuntimeFailsClosedAtTheActivityBoundary() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ApplicationFailure failure = invokeFailure(
                request, result(request), new RuntimeException("unclassified Intake failure"));

        assertThat(failure.getType())
                .isEqualTo(AgentRunFinalizationFailureClassifier.INTAKE_UNCLASSIFIED);
        assertThat(failure.isNonRetryable()).isTrue();
    }

    @Test
    void unknownNonIntakeRuntimeKeepsTheExistingEscapeSemantics() throws Exception {
        ExecuteAgentRunRequest request = nonIntakeRequest(request());
        ExecuteAgentRunResult result = result(request);
        RuntimeException unknown = new RuntimeException("unclassified Evidence failure");
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenThrow(unknown);

        assertThatThrownBy(() ->
                        new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result))
                .isSameAs(unknown);
    }

    @Test
    void finalizerPolicyRetriesIndependentlyWithoutAnExecutionAttemptLimit() {
        var options = AgentRunTemporalPolicy.finalizerActivityOptions();

        assertThat(options.getStartToCloseTimeout())
                .isEqualTo(AgentRunTemporalPolicy.FINALIZER_START_TO_CLOSE_TIMEOUT);
        assertThat(options.getRetryOptions().getMaximumAttempts()).isZero();
        assertThat(options.getRetryOptions().getDoNotRetry())
                .containsExactly(FinalizeAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
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

    private static ExecuteAgentRunResult result(ExecuteAgentRunRequest request) throws Exception {
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

    private static AgentRunFinalizationReceipt receipt(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            CommitStatus status) {
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
                status,
                NOW);
    }

    private static ApplicationFailure invokeFailure(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeException failure) {
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenThrow(failure);
        try {
            new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result);
            throw new AssertionError("finalization failure was not propagated");
        } catch (ApplicationFailure applicationFailure) {
            return applicationFailure;
        }
    }

    private static ExecuteAgentRunRequest intakeRequest(ExecuteAgentRunRequest source) {
        return roomRequest(source, RoomType.INTAKE, "intake.v2");
    }

    private static ExecuteAgentRunRequest nonIntakeRequest(ExecuteAgentRunRequest source) {
        return roomRequest(source, RoomType.EVIDENCE, "evidence.v2");
    }

    private static ExecuteAgentRunRequest roomRequest(
            ExecuteAgentRunRequest source, RoomType roomType, String graphKey) {
        RoomGraphCommand command = source.command();
        RoomGraphCommand roomCommand = new RoomGraphCommand(
                command.schemaVersion(),
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.tenantSurrogate(),
                command.caseId(),
                roomType,
                command.roomEpoch(),
                graphKey,
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                command.threadId(),
                command.actorScope(),
                command.processRevision(),
                command.stageCode(),
                command.stageSequence(),
                command.domainSnapshotRef(),
                command.eventRef(),
                command.invocationContext(),
                command.retryBudget(),
                command.deadlineAt(),
                command.traceparent(),
                command.requestHash());
        return new ExecuteAgentRunRequest(
                source.schemaVersion(),
                source.agentRunId(),
                source.attemptNo(),
                source.attemptLimit(),
                source.streamProtocol(),
                source.logicalInputHash(),
                source.previousAttemptId(),
                source.resetRequired(),
                source.publicSequenceOffset(),
                roomCommand);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
