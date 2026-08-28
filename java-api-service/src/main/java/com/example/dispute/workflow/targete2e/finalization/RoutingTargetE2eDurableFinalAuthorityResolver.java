package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/** Selects the durable FINAL reader only from the sealed execution-profile discriminator. */
public final class RoutingTargetE2eDurableFinalAuthorityResolver
        implements TargetE2eDurableFinalAuthorityResolver {

    private final TargetE2eDurableFinalAuthorityResolver legacyV3;
    private final TargetE2eDurableFinalAuthorityResolver parallelV4;

    public RoutingTargetE2eDurableFinalAuthorityResolver(
            TargetE2eDurableFinalAuthorityResolver legacyV3,
            TargetE2eDurableFinalAuthorityResolver parallelV4) {
        this.legacyV3 = Objects.requireNonNull(legacyV3, "legacyV3");
        this.parallelV4 = Objects.requireNonNull(parallelV4, "parallelV4");
    }

    @Override
    public String requireResultRef(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        if (ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())) {
            if (!"agent-stream.v4".equals(request.streamProtocol())) {
                throw new IllegalStateException(
                        "parallel Intake durable final requires agent-stream.v4");
            }
            return parallelV4.requireResultRef(request, result);
        }
        if (!"agent-stream.v3".equals(request.streamProtocol())) {
            throw new IllegalStateException(
                    "legacy durable final requires agent-stream.v3");
        }
        return legacyV3.requireResultRef(request, result);
    }
}
