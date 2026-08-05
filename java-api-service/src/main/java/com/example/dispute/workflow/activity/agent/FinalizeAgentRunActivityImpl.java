package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Validates the immutable execution result around the retryable Java-domain commit. */
public final class FinalizeAgentRunActivityImpl implements FinalizeAgentRunActivity {

    public static final String NON_RETRYABLE_FAILURE_TYPE =
            AgentRunFinalizationFailureClassifier.GENERIC_REJECTION;
    public static final String RECORDING_FAILURE_TYPE =
            "AgentRunFinalizationFailureRecordingFailed";
    public static final String RECORDING_FAILURE_MESSAGE =
            "agent run finalization failure could not be durably recorded";

    private final AgentRunFinalizationGateway gateway;
    private final AgentRunFinalizationFailureRecorder failureRecorder;

    public FinalizeAgentRunActivityImpl(
            AgentRunFinalizationGateway gateway,
            AgentRunFinalizationFailureRecorder failureRecorder) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.failureRecorder = Objects.requireNonNull(failureRecorder, "failureRecorder");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        try {
            validateRequestAndResult(request, result);
        } catch (RuntimeException validationFailure) {
            throw AgentRunFinalizationFailureClassifier.classify(request, validationFailure);
        }

        AgentRunFinalizationReceipt receipt;
        try {
            receipt = gateway.finalizeResult(request, result);
        } catch (RuntimeException gatewayFailure) {
            RuntimeException classified =
                    AgentRunFinalizationFailureClassifier.classify(request, gatewayFailure);
            if (isNonRetryable(classified)) {
                recordNonRetryableFailure(
                        request,
                        result,
                        safeErrorCode(request, gatewayFailure));
            }
            throw classified;
        }

        try {
            validateReceipt(request, result, receipt);
            return receipt;
        } catch (RuntimeException postCommitValidationFailure) {
            throw AgentRunFinalizationFailureClassifier.classify(
                    request, postCommitValidationFailure);
        }
    }

    private void recordNonRetryableFailure(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            String safeErrorCode) {
        try {
            AgentRunFinalizationFailureRecorder.Command command =
                    new AgentRunFinalizationFailureRecorder.Command(
                            request.agentRunId(),
                            request.logicalRunId(),
                            request.attemptId(),
                            request.attemptNo(),
                            request.command().commandId(),
                            request.command().requestHash(),
                            result.resultHash(),
                            result.lastSequenceNo(),
                            result.publicOutputEmitted(),
                            safeErrorCode);
            AgentRunFinalizationFailureRecorder.Receipt recorded =
                    failureRecorder.record(command);
            validateFailureReceipt(command, recorded);
        } catch (RuntimeException recordingFailure) {
            throw ApplicationFailure.newFailure(
                    RECORDING_FAILURE_MESSAGE,
                    RECORDING_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId());
        }
    }

    private static void validateFailureReceipt(
            AgentRunFinalizationFailureRecorder.Command command,
            AgentRunFinalizationFailureRecorder.Receipt receipt) {
        AgentRunAttemptStatus expectedStatus = command.publicOutputEmitted()
                ? AgentRunAttemptStatus.ABORTED
                : AgentRunAttemptStatus.FAILED;
        long expectedSequence = Math.addExact(command.finalSequenceNo(), 1L);
        if (receipt == null
                || !command.agentRunId().equals(receipt.agentRunId())
                || !command.attemptId().equals(receipt.attemptId())
                || !command.resultHash().equals(receipt.resultHash())
                || expectedSequence != receipt.terminalSequenceNo()
                || expectedStatus != receipt.attemptStatus()
                || !command.safeErrorCode().equals(receipt.safeErrorCode())) {
            throw new IllegalStateException(
                    "durable finalization failure receipt conflicts with its command");
        }
    }

    private static boolean isNonRetryable(RuntimeException failure) {
        return failure instanceof ApplicationFailure applicationFailure
                && applicationFailure.isNonRetryable();
    }

    private static String safeErrorCode(
            ExecuteAgentRunRequest request, RuntimeException gatewayFailure) {
        if (gatewayFailure instanceof AgentRunFinalizationFailure typed
                && !typed.retryable()) {
            return typed.code();
        }
        if (request.command().roomType() == RoomType.INTAKE
                && "intake.v2".equals(request.command().graphKey())) {
            return "INTAKE_FINALIZATION_UNCLASSIFIED";
        }
        return "AGENT_RUN_FINALIZATION_REJECTED";
    }

    private static void validateRequestAndResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (request == null || result == null) {
            throw new IllegalArgumentException("request and result are required");
        }
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || !request.agentRunId().equals(result.agentRunId())
                || !request.logicalRunId().equals(result.logicalRunId())
                || !request.attemptId().equals(result.attemptId())
                || request.attemptNo() != result.attemptNo()
                || !request.command().commandId().equals(result.graphResult().commandId())
                || !result.resultHash().equals(result.graphResult().outputHash())) {
            throw new IllegalArgumentException("execution result cannot be finalized");
        }
    }

    private static void validateReceipt(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            AgentRunFinalizationReceipt receipt) {
        if (receipt == null
                || !request.agentRunId().equals(receipt.agentRunId())
                || !request.logicalRunId().equals(receipt.logicalRunId())
                || !request.attemptId().equals(receipt.attemptId())
                || request.attemptNo() != receipt.attemptNo()
                || !result.resultHash().equals(receipt.finalResultHash())
                || result.lastSequenceNo() != receipt.finalStreamSequenceNo()) {
            throw new IllegalStateException("formal commit receipt does not match execution result");
        }
    }
}
