package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Optional;

/** Loads one authoritative, internally consistent finalization snapshot from Domain PostgreSQL. */
@FunctionalInterface
public interface TargetE2eIntakeFinalizationStateReader {

    Optional<TargetE2eIntakeFinalizationState> load(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
