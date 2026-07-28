package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/** The sole target-artifact AgentRun finalization gateway, shared by all room strategies. */
public final class TargetE2eMultiRoomFinalizationGateway implements AgentRunFinalizationGateway {

    private final TargetE2eMultiRoomOuterFinalizer outerFinalizer;

    public TargetE2eMultiRoomFinalizationGateway(TargetE2eMultiRoomOuterFinalizer outerFinalizer) {
        this.outerFinalizer = Objects.requireNonNull(outerFinalizer, "outerFinalizer");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        return outerFinalizer.finalizeAgentRunResult(request, result).agentRunReceipt();
    }
}
