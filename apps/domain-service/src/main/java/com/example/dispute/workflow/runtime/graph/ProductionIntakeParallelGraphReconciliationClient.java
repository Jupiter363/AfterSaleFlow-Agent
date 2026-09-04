package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import java.util.Objects;

/** Immutable READY-only reconciliation for the explicit Intake parallel profile. */
public final class ProductionIntakeParallelGraphReconciliationClient
        implements AgentGraphReconciliationClient {

    private final ProductionIntakeParallelAssemblyCoordinator coordinator;

    public ProductionIntakeParallelGraphReconciliationClient(
            ProductionIntakeParallelAssemblyCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public GraphReconcileResponse reconcile(
            ExecuteAgentRunRequest request,
            AgentRunCancellationToken cancellationToken) {
        return coordinator.reconcileReady(request, cancellationToken);
    }
}
