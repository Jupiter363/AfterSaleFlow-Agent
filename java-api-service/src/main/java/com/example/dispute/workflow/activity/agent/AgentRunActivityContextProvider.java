package com.example.dispute.workflow.activity.agent;

@FunctionalInterface
public interface AgentRunActivityContextProvider {

    AgentRunActivityContext current();
}
