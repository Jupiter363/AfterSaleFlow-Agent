package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;

/**
 * Process-local relay for v3 public frame events. These events are delivered immediately to an
 * attached SSE subscriber but are deliberately not used as a durable cursor. A completed frame
 * is later replayed from the durable stream store.
 */
@FunctionalInterface
public interface AgentRunTransientStreamPublisher {
    void publish(AgentStreamEvent event);

    static AgentRunTransientStreamPublisher noOp() {
        return event -> { };
    }
}
