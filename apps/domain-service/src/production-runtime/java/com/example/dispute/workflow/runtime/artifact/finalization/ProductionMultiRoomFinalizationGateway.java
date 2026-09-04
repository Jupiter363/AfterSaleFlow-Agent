package com.example.dispute.workflow.runtime.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionMultiRoomOuterFinalizer;
import com.example.dispute.workflow.runtime.finalization.ProductionMultiRoomOuterFinalizer.FinalizationOutcome;
import java.util.Objects;

/** The sole target-artifact AgentRun finalization gateway, shared by all room strategies. */
public final class ProductionMultiRoomFinalizationGateway implements AgentRunFinalizationGateway {

    private final ProductionMultiRoomOuterFinalizer outerFinalizer;
    private final ProductionIntakeDomainEventLiveRelay liveRelay;

    public ProductionMultiRoomFinalizationGateway(
            ProductionMultiRoomOuterFinalizer outerFinalizer,
            ProductionIntakeDomainEventLiveRelay liveRelay) {
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
