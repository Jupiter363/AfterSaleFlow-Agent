package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;
import java.util.Optional;

/**
 * Routes the explicit Intake parallel profile without changing the legacy V3 execution lane.
 *
 * <p>The discriminator is part of the sealed {@link ExecuteAgentRunRequest}; missing or mixed
 * profile/protocol authority fails before either delegate is invoked.
 */
public final class ProfileSelectingAgentRunExecutionGateway implements AgentRunExecutionGateway {

    private final AgentRunExecutionGateway legacyGateway;
    private final AgentRunExecutionGateway parallelIntakeGateway;

    public ProfileSelectingAgentRunExecutionGateway(
            AgentRunExecutionGateway legacyGateway,
            AgentRunExecutionGateway parallelIntakeGateway) {
        this.legacyGateway = Objects.requireNonNull(legacyGateway, "legacyGateway");
        this.parallelIntakeGateway =
                Objects.requireNonNull(parallelIntakeGateway, "parallelIntakeGateway");
        if (legacyGateway == parallelIntakeGateway) {
            throw new IllegalArgumentException("execution gateway delegates must be distinct");
        }
    }

    @Override
    public Completion execute(
            ExecuteAgentRunRequest request,
            ExecutionMode executionMode,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return select(request).execute(
                request, executionMode, progressListener, cancellationToken);
    }

    @Override
    public Optional<FailureTerminationReceipt> terminateUncommittedFailure(
            ExecuteAgentRunRequest request,
            String failureCode,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return select(request).terminateUncommittedFailure(
                request, failureCode, cancellationToken);
    }

    private AgentRunExecutionGateway select(ExecuteAgentRunRequest request) {
        boolean parallel = ExecuteAgentRunRequest.isParallelIntakeCommand(request.command());
        var invocation = request.command().invocationContext();
        boolean reservedParallelMarker = invocation != null
                && ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID.equals(
                        invocation.agentProfileId());
        reservedParallelMarker =
                reservedParallelMarker || request.command().roomId() != null;
        if (!parallel && reservedParallelMarker) {
            throw new IllegalArgumentException(
                    "incomplete or mixed parallel Intake authority cannot use the legacy lane");
        }
        String expectedProtocol = parallel ? "agent-stream.v4" : "agent-stream.v3";
        if (!expectedProtocol.equals(request.streamProtocol())) {
            throw new IllegalArgumentException(
                    "stream protocol differs from the authoritative execution profile");
        }
        return parallel ? parallelIntakeGateway : legacyGateway;
    }
}
