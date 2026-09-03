package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.util.Objects;

/** Routes only the exact parallel Intake profile to Java READY artifacts. */
public final class RoutingTargetE2eFinalizationEvidenceProvider
        implements TargetE2eFinalizationEvidenceProvider {

    private final TargetE2eFinalizationEvidenceProvider legacy;
    private final TargetE2eFinalizationEvidenceProvider parallel;

    public RoutingTargetE2eFinalizationEvidenceProvider(
            TargetE2eFinalizationEvidenceProvider legacy,
            TargetE2eFinalizationEvidenceProvider parallel) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.parallel = Objects.requireNonNull(parallel, "parallel");
    }

    @Override
    public TargetE2eFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            TargetE2eIntakeFinalizationState state) {
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
        TargetE2eFinalizationEvidenceProvider selected = exactParallel ? parallel : legacy;
        return selected.resolve(request, result, runtime, state);
    }

    private static TargetE2eFinalizationRejectedException mixedProfile() {
        return new TargetE2eFinalizationRejectedException(
                "TARGET_E2E_FINALIZATION_PROFILE_MIXED",
                "finalization evidence profile markers are incomplete or inconsistent");
    }
}
