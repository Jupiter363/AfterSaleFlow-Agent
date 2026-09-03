package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eMultiRoomOuterFinalizer;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eMultiRoomOuterFinalizer.FinalizationOutcome;
import java.util.Objects;

/** The sole target-artifact AgentRun finalization gateway, shared by all room strategies. */
public final class TargetE2eMultiRoomFinalizationGateway implements AgentRunFinalizationGateway {

    private final TargetE2eMultiRoomOuterFinalizer outerFinalizer;
    private final TargetE2eIntakeDomainEventLiveRelay liveRelay;

    public TargetE2eMultiRoomFinalizationGateway(
            TargetE2eMultiRoomOuterFinalizer outerFinalizer,
            TargetE2eIntakeDomainEventLiveRelay liveRelay) {
        this.outerFinalizer = Objects.requireNonNull(outerFinalizer, "outerFinalizer");
        this.liveRelay = Objects.requireNonNull(liveRelay, "liveRelay");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        FinalizationOutcome outcome = outerFinalizer.finalizeAgentRunResult(request, result);
        if (request.command().roomType()
                == com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE) {
            liveRelay.relay(
                    request,
                    result,
                    outcome.targetReceipt().receipt(),
                    outcome.agentRunReceipt());
        }
        return outcome.agentRunReceipt();
    }
}
