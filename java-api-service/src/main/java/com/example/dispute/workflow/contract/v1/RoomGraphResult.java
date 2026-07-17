package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableList;
import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RiskLevel;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoomGraphResult(
        String schemaVersion,
        String commandId,
        String logicalRunId,
        String attemptId,
        String graphKey,
        String graphVersion,
        String checkpointId,
        long cognitiveRevision,
        GraphStatus status,
        List<EventProposal> publicEventProposals,
        List<ArtifactOperation> artifactOperations,
        NeedsInput needsInput,
        NeedsReview needsReview,
        ContractError error,
        String outputHash,
        Usage usage,
        ExecutionMetadata executionMetadata) {

    public RoomGraphResult {
        schemaVersion = version(schemaVersion, "room-graph-result.v1");
        required(commandId, "commandId");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        required(graphKey, "graphKey");
        required(graphVersion, "graphVersion");
        required(checkpointId, "checkpointId");
        required(status, "status");
        publicEventProposals = immutableList(publicEventProposals, "publicEventProposals");
        artifactOperations = immutableList(artifactOperations, "artifactOperations");
        required(outputHash, "outputHash");
        required(usage, "usage");
        required(executionMetadata, "executionMetadata");
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventProposal(
            String eventType, Audience audience, String payloadRef, String payloadHash) {
        public EventProposal {
            required(eventType, "eventType");
            required(audience, "audience");
            required(payloadRef, "payloadRef");
            required(payloadHash, "payloadHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtifactOperation(
            ArtifactOperationType operation, ArtifactPointer artifact) {
        public ArtifactOperation {
            required(operation, "operation");
            required(artifact, "artifact");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NeedsInput(String reasonCode, List<String> requiredActorScopes) {
        public NeedsInput {
            required(reasonCode, "reasonCode");
            requiredActorScopes = immutableList(requiredActorScopes, "requiredActorScopes");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NeedsReview(String reasonCode, RiskLevel riskLevel) {
        public NeedsReview {
            required(reasonCode, "reasonCode");
            required(riskLevel, "riskLevel");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContractError(String code, boolean retryable) {
        public ContractError {
            required(code, "code");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionMetadata(
            String promptVersion,
            String modelProfileId,
            String schemaVersion,
            String policyVersion,
            String guardrailVersion) {
        public ExecutionMetadata {
            required(promptVersion, "promptVersion");
            required(modelProfileId, "modelProfileId");
            required(schemaVersion, "schemaVersion");
            required(policyVersion, "policyVersion");
            required(guardrailVersion, "guardrailVersion");
        }
    }
}
