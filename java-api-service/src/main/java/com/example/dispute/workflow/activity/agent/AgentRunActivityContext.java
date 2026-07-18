package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;

/** Minimal Activity runtime surface kept injectable for pure tests. */
public interface AgentRunActivityContext {

    int temporalAttempt();

    void heartbeat(AgentRunAttemptHeartbeat details);
}
