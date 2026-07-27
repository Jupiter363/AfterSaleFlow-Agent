package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;

/** Loads immutable target-lane envelopes and normalized hash-source documents. */
@FunctionalInterface
public interface TargetE2eFinalizationEvidenceProvider {

    TargetE2eFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            TargetE2eIntakeFinalizationState state);
}
