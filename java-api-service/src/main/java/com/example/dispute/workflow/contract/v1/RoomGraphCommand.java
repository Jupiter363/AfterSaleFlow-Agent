package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableList;
import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoomGraphCommand(
        String schemaVersion,
        String commandId,
        String logicalRunId,
        String attemptId,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String threadId,
        ActorScope actorScope,
        long processRevision,
        String stageCode,
        long stageSequence,
        SnapshotRef domainSnapshotRef,
        SnapshotRef eventRef,
        InvocationContext invocationContext,
        RetryBudget retryBudget,
        Instant deadlineAt,
        String traceparent,
        String requestHash) {

    public RoomGraphCommand {
        schemaVersion = version(schemaVersion, "room-graph-command.v1");
        required(commandId, "commandId");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        required(roomType, "roomType");
        required(graphKey, "graphKey");
        required(graphVersion, "graphVersion");
        required(checkpointSchemaVersion, "checkpointSchemaVersion");
        required(threadId, "threadId");
        required(actorScope, "actorScope");
        required(stageCode, "stageCode");
        required(domainSnapshotRef, "domainSnapshotRef");
        required(invocationContext, "invocationContext");
        required(retryBudget, "retryBudget");
        required(deadlineAt, "deadlineAt");
        required(traceparent, "traceparent");
        required(requestHash, "requestHash");
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActorScope(
            String actorId, ActorRole actorRole, Audience audience, List<String> capabilities) {
        public ActorScope {
            required(actorId, "actorId");
            required(actorRole, "actorRole");
            required(audience, "audience");
            capabilities = immutableList(capabilities, "capabilities");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotRef(
            String artifactId, String schemaVersion, String uri, String sha256, long sizeBytes) {
        public SnapshotRef {
            required(artifactId, "artifactId");
            required(schemaVersion, "schemaVersion");
            required(uri, "uri");
            required(sha256, "sha256");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InvocationContext(
            String agentProfileId,
            String promptProfileId,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            List<String> toolCapabilities,
            String envelopeKeyId,
            String envelopeNonce) {
        public InvocationContext {
            required(agentProfileId, "agentProfileId");
            required(promptProfileId, "promptProfileId");
            required(modelProfileId, "modelProfileId");
            required(outputSchemaVersion, "outputSchemaVersion");
            required(policyVersion, "policyVersion");
            required(guardrailVersion, "guardrailVersion");
            toolCapabilities = immutableList(toolCapabilities, "toolCapabilities");
            required(envelopeKeyId, "envelopeKeyId");
            required(envelopeNonce, "envelopeNonce");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetryBudget(
            int providerAttemptsRemaining,
            int activityAttemptsRemaining,
            int repairsRemaining) {}
}
