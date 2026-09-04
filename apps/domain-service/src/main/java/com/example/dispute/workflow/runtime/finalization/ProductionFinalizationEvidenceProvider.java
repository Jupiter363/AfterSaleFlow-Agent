package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;

/** Loads immutable target-lane envelopes and normalized hash-source documents. */
@FunctionalInterface
public interface ProductionFinalizationEvidenceProvider {

    ProductionFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            ProductionIntakeFinalizationState state);
}
