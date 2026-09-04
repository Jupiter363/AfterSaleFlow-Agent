package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Optional;

/** Loads one authoritative, internally consistent finalization snapshot from Domain PostgreSQL. */
@FunctionalInterface
public interface ProductionIntakeFinalizationStateReader {

    Optional<ProductionIntakeFinalizationState> load(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
