package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableList;
import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableMap;
import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentExecutionManifest(
        String schemaVersion,
        String manifestId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long processRevision,
        long fencingToken,
        WorkflowRef workflow,
        AgentRunRef agentRun,
        GraphRef graph,
        ModelRef model,
        Map<String, String> contractVersions,
        String policyVersion,
        String guardrailVersion,
        List<String> toolVersions,
        List<ArtifactPointer> inputs,
        ArtifactPointer output,
        ManifestUsage usage,
        String traceparent,
        Instant finalizedAt) {

    public AgentExecutionManifest {
        schemaVersion = version(schemaVersion, "agent-execution-manifest.v1");
        required(manifestId, "manifestId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        required(workflow, "workflow");
        required(agentRun, "agentRun");
        required(graph, "graph");
        required(model, "model");
        contractVersions = immutableMap(contractVersions, "contractVersions");
        required(policyVersion, "policyVersion");
        required(guardrailVersion, "guardrailVersion");
        toolVersions = immutableList(toolVersions, "toolVersions");
        inputs = immutableList(inputs, "inputs");
        required(output, "output");
        required(usage, "usage");
        required(traceparent, "traceparent");
        required(finalizedAt, "finalizedAt");
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkflowRef(
            String workflowId, String runId, String workflowType, String buildId) {
        public WorkflowRef {
            required(workflowId, "workflowId");
            required(runId, "runId");
            required(workflowType, "workflowType");
            required(buildId, "buildId");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentRunRef(
            String logicalRunId, String attemptId, String logicalIdempotencyKey) {
        public AgentRunRef {
            required(logicalRunId, "logicalRunId");
            required(attemptId, "attemptId");
            required(logicalIdempotencyKey, "logicalIdempotencyKey");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GraphRef(
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            long cognitiveRevision) {
        public GraphRef {
            required(graphKey, "graphKey");
            required(graphVersion, "graphVersion");
            required(checkpointSchemaVersion, "checkpointSchemaVersion");
            required(checkpointId, "checkpointId");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelRef(
            String promptVersion,
            String modelProfileId,
            String provider,
            String model,
            String requestHash,
            String responseHash) {
        public ModelRef {
            required(promptVersion, "promptVersion");
            required(modelProfileId, "modelProfileId");
            required(provider, "provider");
            required(model, "model");
            required(requestHash, "requestHash");
            required(responseHash, "responseHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManifestUsage(
            long inputTokens, long outputTokens, long totalTokens, long latencyMs) {}
}
