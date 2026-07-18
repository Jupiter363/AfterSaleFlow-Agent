package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

/** Captures the current Temporal context before heartbeat work moves to its scheduler thread. */
public final class TemporalAgentRunActivityContextProvider
        implements AgentRunActivityContextProvider {

    @Override
    public AgentRunActivityContext current() {
        ActivityExecutionContext context = Activity.getExecutionContext();
        return new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return context.getInfo().getAttempt();
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {
                context.heartbeat(details);
            }
        };
    }
}
