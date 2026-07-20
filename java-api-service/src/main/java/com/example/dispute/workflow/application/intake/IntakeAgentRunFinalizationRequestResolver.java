package com.example.dispute.workflow.application.intake;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;

/** Resolves Java-trusted Intake references for one outer AgentRun formal commit. */
@FunctionalInterface
public interface IntakeAgentRunFinalizationRequestResolver {

    IntakeGraphFinalizationRequest resolve(
            AgentRunDomainResultCommitter.CommitCommand command);
}
