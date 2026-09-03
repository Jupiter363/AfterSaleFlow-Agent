package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;

/** Reconstructs the formal Hearing command only from durable target-lane material and proposal. */
@FunctionalInterface
public interface TargetHearingFinalizationRequestResolver {
  TargetHearingFinalizationRequest resolve(CommitCommand command);
}
