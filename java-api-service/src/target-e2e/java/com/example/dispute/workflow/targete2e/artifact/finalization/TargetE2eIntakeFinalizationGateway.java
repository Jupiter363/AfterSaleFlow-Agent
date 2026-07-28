package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeOuterFinalizer;
import java.util.Objects;

/** Target-only Temporal Finalizer adapter backed by the outer transactional finalizer. */
public final class TargetE2eIntakeFinalizationGateway implements AgentRunFinalizationGateway {

    private final TargetE2eIntakeOuterFinalizer outerFinalizer;
    private final TargetE2eGraphOutputSnapshotMaterializer outputMaterializer;

    public TargetE2eIntakeFinalizationGateway(
            TargetE2eIntakeOuterFinalizer outerFinalizer,
            TargetE2eGraphOutputSnapshotMaterializer outputMaterializer) {
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
