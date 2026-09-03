package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Maps room-owned typed failures to Temporal semantics without changing other room runtimes. */
public final class AgentRunFinalizationFailureClassifier {

    public static final String GENERIC_REJECTION = "AgentRunFinalizationRejected";
    public static final String INTAKE_UNCLASSIFIED = "IntakeFinalizationUnclassified";

    private AgentRunFinalizationFailureClassifier() {}

    public static RuntimeException classify(
            ExecuteAgentRunRequest request, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof ApplicationFailure) {
            return failure;
        }
        if (failure instanceof AgentRunFinalizationFailure typed) {
            return typed.retryable()
                    ? ApplicationFailure.newFailureWithCause(
                            "agent run finalization is temporarily unavailable",
                            typed.code(),
                            failure,
                            runId(request),
                            attemptId(request),
                            typed.code())
                    : ApplicationFailure.newNonRetryableFailureWithCause(
                            failure.getMessage(),
                            typed.code(),
                            failure,
                            runId(request),
                            attemptId(request),
                            typed.code());
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException) {
            return ApplicationFailure.newNonRetryableFailureWithCause(
                    "agent run finalization was rejected",
                    GENERIC_REJECTION,
                    failure,
                    runId(request),
                    attemptId(request),
                    "AGENT_RUN_FINALIZATION_REJECTED");
        }
        if (isIntakeV2(request)) {
            return ApplicationFailure.newNonRetryableFailureWithCause(
                    "Intake finalization failed without an explicit retry classification",
                    INTAKE_UNCLASSIFIED,
                    failure,
                    runId(request),
                    attemptId(request),
                    failure.getClass().getName());
        }
        return failure;
    }

    private static boolean isIntakeV2(ExecuteAgentRunRequest request) {
        return request != null
                && request.command().roomType() == RoomType.INTAKE
                && "intake.v2".equals(request.command().graphKey());
    }

    private static String runId(ExecuteAgentRunRequest request) {
        return request == null ? "unknown-run" : request.agentRunId();
    }

    private static String attemptId(ExecuteAgentRunRequest request) {
        return request == null ? "unknown-attempt" : request.attemptId();
    }
}
