package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunV2FinalizationFactsProvider;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Builds manifest facts only after target-lane and persisted-state authorization succeeds. */
public final class TargetE2eAgentRunV2FinalizationFactsProvider
        implements AgentRunV2FinalizationFactsProvider {

    private final TargetE2eAuthorizedIntakeFinalizationSource source;

    public TargetE2eAgentRunV2FinalizationFactsProvider(
            TargetE2eAuthorizedIntakeFinalizationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public AgentRunV2ManifestFactory.FinalizationFacts resolve(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        var authorized = source.resolve(request, result);
        return create(authorized, request, result);
    }

    public AgentRunV2ManifestFactory.FinalizationFacts create(
            TargetE2eAuthorizedIntakeFinalizationSource.AuthorizedState authorized,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result) {
        Objects.requireNonNull(authorized, "authorized");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        var state = authorized.state();
        ArtifactPointer proposal = proposal(result);
        return new AgentRunV2ManifestFactory.FinalizationFacts(
                state.run().fencingToken(),
                state.run().logicalIdempotencyKey(),
                authorized.runtime().workflowId(),
                authorized.runtime().workflowRunId(),
                authorized.runtime().workflowBuildId(),
                authorized.evidence().executionProvider(),
                authorized.evidence().executionModel(),
                manifestUri(
                        authorized.activation().activationId(),
                        request.agentRunId(),
                        request.attemptId(),
                        result.resultHash()),
                state.graphOutput(),
                List.of(proposal),
                List.of(),
                state.attempt().latencyMs(),
                state.attempt().completedAt());
    }

    static ArtifactPointer proposal(ExecuteAgentRunResult result) {
        if (result == null
                || result.graphResult() == null
                || result.graphResult().artifactOperations().size() != 1
                || result.graphResult().artifactOperations().getFirst().operation()
                        != ArtifactOperationType.PROPOSE_PATCH) {
            throw new TargetE2eFinalizationRejectedException(
                    "TARGET_E2E_PROPOSAL_REFERENCE_MISSING",
                    "completed Intake result must contain one proposal-only artifact");
        }
        return result.graphResult().artifactOperations().getFirst().artifact();
    }

    private static String manifestUri(
            String activationId, String agentRunId, String attemptId, String resultHash) {
        String identity = activationId + ':' + agentRunId + ':' + attemptId + ':' + resultHash;
        return "urn:target-e2e:agent-manifest:" + sha256(identity);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
