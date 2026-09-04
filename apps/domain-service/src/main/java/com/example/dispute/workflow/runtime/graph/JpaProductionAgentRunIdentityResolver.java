package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Loads the Java-owned AgentRun lineage and room fence before sealing a target Graph command. */
public final class JpaProductionAgentRunIdentityResolver
    implements ProductionAgentRunIdentityResolver {

  private final AgentRunRepository runRepository;
  private final AgentRunAttemptRepository attemptRepository;

  public JpaProductionAgentRunIdentityResolver(
      AgentRunRepository runRepository, AgentRunAttemptRepository attemptRepository) {
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
  }

  @Override
  public DurableIdentity resolve(ExecuteAgentRunRequest request) {
    Objects.requireNonNull(request, "request");
    AgentRunEntity run =
        runRepository
            .findById(request.agentRunId())
            .orElseThrow(() -> new IllegalStateException("target AgentRun does not exist"));
    AgentRunAttemptEntity attempt =
        attemptRepository
            .findById(request.attemptId())
            .orElseThrow(() -> new IllegalStateException("target AgentRun attempt does not exist"));

    run.requireAttemptRequest(request);
    attempt.requireAllocatedRequest(request);
    if (run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY
        || attempt.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
      throw new IllegalStateException("target Graph execution requires Temporal AgentRun lineage");
    }
    return DurableIdentity.from(request, run.getFencingToken());
  }
}
