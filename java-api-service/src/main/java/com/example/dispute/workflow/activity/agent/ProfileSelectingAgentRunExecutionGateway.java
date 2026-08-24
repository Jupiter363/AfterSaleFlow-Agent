package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

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
        boolean parallel = ExecuteAgentRunRequest.isParallelIntakeCommand(request.command());
        String expectedProtocol = parallel ? "agent-stream.v4" : "agent-stream.v3";
        if (!expectedProtocol.equals(request.streamProtocol())) {
            throw new IllegalArgumentException(
                    "stream protocol differs from the authoritative execution profile");
        }
        AgentRunExecutionGateway selected = parallel ? parallelIntakeGateway : legacyGateway;
        return selected.execute(request, executionMode, progressListener, cancellationToken);
    }
}
