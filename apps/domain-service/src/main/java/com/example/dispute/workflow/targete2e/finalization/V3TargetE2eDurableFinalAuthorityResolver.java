package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.List;
import java.util.Objects;

/** Existing agent-stream.v3 FINAL authority reader, kept isolated from the V4 lane. */
public final class V3TargetE2eDurableFinalAuthorityResolver
        implements TargetE2eDurableFinalAuthorityResolver {

    private final AgentRunV2StreamStore streamStore;

    public V3TargetE2eDurableFinalAuthorityResolver(AgentRunV2StreamStore streamStore) {
        this.streamStore = Objects.requireNonNull(streamStore, "streamStore");
    }

    @Override
    public String requireResultRef(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        if (!"agent-stream.v3".equals(request.streamProtocol())
                || ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())) {
            throw new IllegalStateException(
                    "agent-stream.v3 durable final requires the legacy execution profile");
        }
        long previous = Math.subtractExact(result.lastSequenceNo(), 1L);
        List<AgentStreamEvent> events = streamStore.replay(
                request.agentRunId(), request.attemptId(), previous, 2);
        if (events.size() != 1) {
            throw new IllegalStateException("target AgentRun durable final is absent or ambiguous");
        }
        AgentStreamEvent terminal = events.getFirst();
        if (terminal.eventType() != StreamEventType.FINAL
                || terminal.sequenceNo() != result.lastSequenceNo()
                || !request.agentRunId().equals(terminal.runId())
                || !request.attemptId().equals(terminal.attemptId())
                || request.command().actorScope().audience() != terminal.audience()
                || terminal.payload() == null
                || !result.resultHash().equals(terminal.payload().finalResultHash())) {
            throw new IllegalStateException(
                    "target AgentRun durable final conflicts with completed result");
        }
        return terminal.payload().finalResultRef();
    }
}
