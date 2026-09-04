package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;
import java.util.Objects;

/** Routes only the exact parallel Intake profile to Java READY artifacts. */
public final class RoutingProductionFinalizationEvidenceProvider
        implements ProductionFinalizationEvidenceProvider {

    private final ProductionFinalizationEvidenceProvider legacy;
    private final ProductionFinalizationEvidenceProvider parallel;

    public RoutingProductionFinalizationEvidenceProvider(
            ProductionFinalizationEvidenceProvider legacy,
            ProductionFinalizationEvidenceProvider parallel) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.parallel = Objects.requireNonNull(parallel, "parallel");
    }

    @Override
    public ProductionFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            ProductionIntakeFinalizationState state) {
        Objects.requireNonNull(request, "request");
        var command = request.command();
        boolean exactParallel = ExecuteAgentRunRequest.isParallelIntakeCommand(command);
        boolean profileMarker = command != null
                && command.invocationContext() != null
                && ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID.equals(
                        command.invocationContext().agentProfileId());
        boolean roomMarker = command != null && command.roomId() != null;
        boolean v4Marker = "agent-stream.v4".equals(request.streamProtocol());
        if (exactParallel) {
            if (!v4Marker) {
                throw mixedProfile();
            }
        } else if (profileMarker || roomMarker || v4Marker || command == null) {
            throw mixedProfile();
        } else if (!"agent-stream.v3".equals(request.streamProtocol())) {
            throw mixedProfile();
        }
        ProductionFinalizationEvidenceProvider selected = exactParallel ? parallel : legacy;
        return selected.resolve(request, result, runtime, state);
    }

    private static ProductionFinalizationRejectedException mixedProfile() {
        return new ProductionFinalizationRejectedException(
                "PRODUCTION_RUNTIME_FINALIZATION_PROFILE_MIXED",
                "finalization evidence profile markers are incomplete or inconsistent");
    }
}
