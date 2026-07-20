package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/** Validates the immutable execution result around the retryable Java-domain commit. */
public final class FinalizeAgentRunActivityImpl implements FinalizeAgentRunActivity {

    public static final String NON_RETRYABLE_FAILURE_TYPE =
            AgentRunFinalizationFailureClassifier.GENERIC_REJECTION;

    private final AgentRunFinalizationGateway gateway;

    public FinalizeAgentRunActivityImpl(AgentRunFinalizationGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        try {
            validateRequestAndResult(request, result);
            AgentRunFinalizationReceipt receipt = gateway.finalizeResult(request, result);
            validateReceipt(request, result, receipt);
            return receipt;
        } catch (RuntimeException failure) {
            throw AgentRunFinalizationFailureClassifier.classify(request, failure);
        }
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
