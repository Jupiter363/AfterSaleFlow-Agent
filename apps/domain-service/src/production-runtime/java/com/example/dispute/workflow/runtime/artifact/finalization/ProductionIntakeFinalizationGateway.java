package com.example.dispute.workflow.runtime.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionGraphOutputSnapshotMaterializer;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeOuterFinalizer;
import java.util.Objects;

/** Production-only Temporal Finalizer adapter backed by the outer transactional finalizer. */
public final class ProductionIntakeFinalizationGateway implements AgentRunFinalizationGateway {

    private final ProductionIntakeOuterFinalizer outerFinalizer;
    private final ProductionGraphOutputSnapshotMaterializer outputMaterializer;

    public ProductionIntakeFinalizationGateway(
            ProductionIntakeOuterFinalizer outerFinalizer,
            ProductionGraphOutputSnapshotMaterializer outputMaterializer) {
        this.outerFinalizer = Objects.requireNonNull(outerFinalizer, "outerFinalizer");
        this.outputMaterializer = Objects.requireNonNull(outputMaterializer, "outputMaterializer");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        return outputMaterializer.materializeThen(
                request,
                result,
                () -> outerFinalizer.finalizeAgentRunResult(request, result).agentRunReceipt());
    }
}
