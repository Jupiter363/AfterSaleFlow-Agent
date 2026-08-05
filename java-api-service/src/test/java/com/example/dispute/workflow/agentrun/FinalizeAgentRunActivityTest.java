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
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

        var activity = activity(gateway, RecorderProbe.success());

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
                        activity(gateway, RecorderProbe.success()).finalizeResult(request, result))
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
                        activity(gateway, RecorderProbe.success()).finalizeResult(request, result))
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
                        activity(gateway, RecorderProbe.success()).finalizeResult(request, result))
                .isSameAs(unknown);
    }

    @Test
    void nonRetryableGatewayRejectionAwaitsDurableRecorderBeforeRethrow() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ExecuteAgentRunResult result = result(request);
        var rejection = new IntakeFinalizationRejectedException(
                "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID", "private rejection detail");
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenThrow(rejection);
        RecorderProbe recorder = RecorderProbe.success();

        assertThatThrownBy(() -> activity(gateway, recorder).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure classified = (ApplicationFailure) failure;
                    assertThat(classified.getType())
                            .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
                    assertThat(classified.isNonRetryable()).isTrue();
                    assertThat(classified.getCause()).isSameAs(rejection);
                });

        assertThat(recorder.calls()).isEqualTo(1);
        assertThat(recorder.returnedReceipt()).isTrue();
        assertThat(recorder.command("agentRunId")).isEqualTo(request.agentRunId());
        assertThat(recorder.command("logicalRunId")).isEqualTo(request.logicalRunId());
        assertThat(recorder.command("attemptId")).isEqualTo(request.attemptId());
        assertThat(recorder.command("attemptNo")).isEqualTo(request.attemptNo());
        assertThat(recorder.command("commandId")).isEqualTo(request.command().commandId());
        assertThat(recorder.command("commandRequestHash"))
                .isEqualTo(request.command().requestHash());
        assertThat(recorder.command("resultHash")).isEqualTo(result.resultHash());
        assertThat(recorder.command("finalSequenceNo")).isEqualTo(result.lastSequenceNo());
        assertThat(recorder.command("publicOutputEmitted"))
                .isEqualTo(result.publicOutputEmitted());
        assertThat(recorder.command("safeErrorCode"))
                .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
    }

    @Test
    void retryableGatewayFailureDoesNotRecordFinalizationFailure() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result))
                .thenThrow(new IntakeProposalLoadException(
                        "private object-store detail", new RuntimeException("private timeout")));
        RecorderProbe recorder = RecorderProbe.success();

        assertThatThrownBy(() -> activity(gateway, recorder).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure classified = (ApplicationFailure) failure;
                    assertThat(classified.getType()).isEqualTo(IntakeProposalLoadException.CODE);
                    assertThat(classified.isNonRetryable()).isFalse();
                });
        assertThat(recorder.calls()).isZero();
    }

    @Test
    void recorderFailureKeepsFinalizeActivityRetryable() throws Exception {
        ExecuteAgentRunRequest request = intakeRequest(request());
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result))
                .thenThrow(new IntakeFinalizationRejectedException(
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                        "private finalizer rejection"));
        RecorderProbe recorder = RecorderProbe.throwing();

        assertThatThrownBy(() -> activity(gateway, recorder).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure recordingFailure = (ApplicationFailure) failure;
                    assertThat(recordingFailure.getType())
                            .isEqualTo("AgentRunFinalizationFailureRecordingFailed");
                    assertThat(recordingFailure.isNonRetryable()).isFalse();
                    assertThat(recordingFailure.getMessage())
                            .contains("agent run finalization failure could not be durably recorded")
                            .doesNotContain("private finalizer rejection")
                            .doesNotContain("private recorder detail");
                });
        assertThat(recorder.calls()).isEqualTo(1);
        assertThat(recorder.returnedReceipt()).isFalse();
    }

    @Test
    void postCommitReceiptMismatchDoesNotRecordFailure() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationReceipt mismatch = new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                7,
                result.resultHash().equals("0".repeat(64))
                        ? "1".repeat(64)
                        : "0".repeat(64),
                "manifest-001",
                "a".repeat(64),
                result.lastSequenceNo(),
                CommitStatus.COMMITTED,
                NOW);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenReturn(mismatch);
        RecorderProbe recorder = RecorderProbe.success();

        assertThatThrownBy(() -> activity(gateway, recorder).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure classified = (ApplicationFailure) failure;
                    assertThat(classified.isNonRetryable()).isTrue();
                });
        assertThat(recorder.calls()).isZero();
    }

    @Test
    void recorderReceiptMismatchKeepsFinalizeActivityRetryable() throws Exception {
        for (ReceiptMode mode : List.of(
                ReceiptMode.NULL,
                ReceiptMode.WRONG_AGENT_RUN_ID,
                ReceiptMode.WRONG_ATTEMPT_ID,
                ReceiptMode.WRONG_RESULT_HASH,
                ReceiptMode.WRONG_SEQUENCE,
                ReceiptMode.WRONG_STATUS,
                ReceiptMode.WRONG_CODE)) {
            ExecuteAgentRunRequest request = intakeRequest(request());
            ExecuteAgentRunResult result = result(request);
            AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
            when(gateway.finalizeResult(request, result))
                    .thenThrow(new IntakeFinalizationRejectedException(
                            "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                            "private finalizer rejection"));
            RecorderProbe recorder = new RecorderProbe(mode);

            assertThatThrownBy(() -> activity(gateway, recorder).finalizeResult(request, result))
                    .as("recorder receipt mode %s", mode)
                    .isInstanceOf(ApplicationFailure.class)
                    .satisfies(failure -> {
                        ApplicationFailure recordingFailure = (ApplicationFailure) failure;
                        assertThat(recordingFailure.getType())
                                .isEqualTo("AgentRunFinalizationFailureRecordingFailed");
                        assertThat(recordingFailure.isNonRetryable()).isFalse();
                        assertThat(recordingFailure.getMessage())
                                .contains("agent run finalization failure could not be durably recorded")
                                .doesNotContain("private finalizer rejection");
                    });
            assertThat(recorder.calls()).isEqualTo(1);
        }
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
            activity(gateway, RecorderProbe.success()).finalizeResult(request, result);
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

    private static FinalizeAgentRunActivityImpl activity(
            AgentRunFinalizationGateway gateway, RecorderProbe probe) {
        try {
            Class<?> recorderType = Class.forName(
                    "com.example.dispute.workflow.activity.agent."
                            + "AgentRunFinalizationFailureRecorder");
            Object recorder = Proxy.newProxyInstance(
                    recorderType.getClassLoader(),
                    new Class<?>[] {recorderType},
                    probe);
            Constructor<FinalizeAgentRunActivityImpl> constructor =
                    FinalizeAgentRunActivityImpl.class.getConstructor(
                            AgentRunFinalizationGateway.class, recorderType);
            return constructor.newInstance(gateway, recorder);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "expected AgentRunFinalizationFailureRecorder API and activity constructor are missing",
                    failure);
        }
    }

    private enum ReceiptMode {
        SUCCESS,
        THROW,
        NULL,
        WRONG_AGENT_RUN_ID,
        WRONG_ATTEMPT_ID,
        WRONG_RESULT_HASH,
        WRONG_SEQUENCE,
        WRONG_STATUS,
        WRONG_CODE
    }

    private static final class RecorderProbe implements InvocationHandler {

        private final ReceiptMode mode;
        private int calls;
        private Object lastCommand;
        private boolean returnedReceipt;

        private RecorderProbe(ReceiptMode mode) {
            this.mode = mode;
        }

        static RecorderProbe success() {
            return new RecorderProbe(ReceiptMode.SUCCESS);
        }

        static RecorderProbe throwing() {
            return new RecorderProbe(ReceiptMode.THROW);
        }

        int calls() {
            return calls;
        }

        boolean returnedReceipt() {
            return returnedReceipt;
        }

        Object command(String accessor) {
            if (lastCommand == null) {
                throw new AssertionError("recorder command was not captured");
            }
            return invokeAccessor(lastCommand, accessor);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "RecorderProbe";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (!"record".equals(method.getName())
                    || arguments == null
                    || arguments.length != 1) {
                throw new AssertionError("unexpected recorder invocation: " + method);
            }
            calls++;
            lastCommand = arguments[0];
            if (mode == ReceiptMode.THROW) {
                throw new IllegalStateException("private recorder detail");
            }
            if (mode == ReceiptMode.NULL) {
                return null;
            }
            Object receipt = receipt(method.getReturnType(), lastCommand, mode);
            returnedReceipt = true;
            return receipt;
        }

        private static Object receipt(
                Class<?> receiptType, Object command, ReceiptMode mode) throws Exception {
            String agentRunId = string(command, "agentRunId");
            String attemptId = string(command, "attemptId");
            String resultHash = string(command, "resultHash");
            String safeErrorCode = string(command, "safeErrorCode");
            long finalSequenceNo = number(command, "finalSequenceNo");
            boolean publicOutputEmitted = (boolean) invokeAccessor(
                    command, "publicOutputEmitted");
            AgentRunAttemptStatus expectedStatus = publicOutputEmitted
                    ? AgentRunAttemptStatus.ABORTED
                    : AgentRunAttemptStatus.FAILED;
            return receiptType
                    .getConstructor(
                            String.class,
                            String.class,
                            String.class,
                            long.class,
                            AgentRunAttemptStatus.class,
                            String.class,
                            boolean.class)
                    .newInstance(
                            mode == ReceiptMode.WRONG_AGENT_RUN_ID ? "RUN_STALE" : agentRunId,
                            mode == ReceiptMode.WRONG_ATTEMPT_ID ? "ATTEMPT_STALE" : attemptId,
                            mode == ReceiptMode.WRONG_RESULT_HASH ? "e".repeat(64) : resultHash,
                            mode == ReceiptMode.WRONG_SEQUENCE
                                    ? Math.addExact(finalSequenceNo, 2)
                                    : Math.addExact(finalSequenceNo, 1),
                            mode == ReceiptMode.WRONG_STATUS
                                    ? (expectedStatus == AgentRunAttemptStatus.ABORTED
                                            ? AgentRunAttemptStatus.FAILED
                                            : AgentRunAttemptStatus.ABORTED)
                                    : expectedStatus,
                            mode == ReceiptMode.WRONG_CODE
                                    ? "AGENT_RUN_FINALIZATION_REJECTED"
                                    : safeErrorCode,
                            false);
        }

        private static String string(Object record, String accessor) {
            return (String) invokeAccessor(record, accessor);
        }

        private static long number(Object record, String accessor) {
            return ((Number) invokeAccessor(record, accessor)).longValue();
        }

        private static Object invokeAccessor(Object record, String accessor) {
            try {
                return record.getClass().getMethod(accessor).invoke(record);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("recorder record is missing accessor " + accessor, failure);
            }
        }
    }
}
