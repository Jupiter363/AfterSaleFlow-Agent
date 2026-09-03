package com.example.dispute.agentstream.infrastructure.delivery;

/** Best-effort live-delivery hint. Implementations must never become replay storage. */
@FunctionalInterface
public interface AgentRunStreamWakeupPublisher {

    void publish(AgentRunStreamWakeup wakeup);
}
