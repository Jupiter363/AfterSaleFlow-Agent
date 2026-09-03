package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;

/**
 * Decodes the immutable Hearing proposal into Java formal facts after the target source verifier
 * has validated its content address and schema. It cannot perform writes.
 */
@FunctionalInterface
public interface TargetHearingFormalCommandMapper {
  TargetHearingFinalizationRequest map(
      CommitCommand command,
      TargetHearingCommandMaterialStore.Snapshot material,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding authority);
}
