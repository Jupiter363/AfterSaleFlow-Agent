package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Resolves exactly one room-owned formal writer for a graph result. */
@Component
public final class AgentRunDomainResultCommitterRegistry {

    private final List<AgentRunDomainResultCommitter> committers;

    public AgentRunDomainResultCommitterRegistry(List<AgentRunDomainResultCommitter> committers) {
        this.committers = List.copyOf(committers);
    }

    public AgentRunDomainResultCommitter require(ExecuteAgentRunRequest request) {
        var command = request.command();
        List<AgentRunDomainResultCommitter> matches = committers.stream()
                .filter(committer -> committer.supports(command.roomType(), command.graphKey()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one formal result committer for room and graph");
        }
        return matches.getFirst();
    }
}
