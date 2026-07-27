package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.AgentRunRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.GraphRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ManifestUsage;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ModelRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.WorkflowRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the canonical execution manifest only from contract fields and explicit runtime facts. */
@Component
public final class AgentRunV2ManifestFactory {

    private static final String MANIFEST_SCHEMA_VERSION = "agent-execution-manifest.v1";
    private static final String MANIFEST_ID_PREFIX = "agent-manifest-v2-";
    private static final int MANIFEST_ID_MAX_LENGTH = 64;

    private final ObjectMapper objectMapper;

    public AgentRunV2ManifestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ManifestCommit create(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result, FinalizationFacts facts) {
        validate(request, result, facts);
        RoomGraphCommand command = request.command();
        RoomGraphResult graph = result.graphResult();
        RoomGraphResult.ExecutionMetadata metadata = graph.executionMetadata();
        String manifestId = manifestId(request, result, facts);
        AgentExecutionManifest manifest =
                new AgentExecutionManifest(
                        MANIFEST_SCHEMA_VERSION,
                        manifestId,
                        command.tenantSurrogate(),
                        command.caseId(),
                        command.roomEpoch(),
                        command.processRevision(),
                        facts.fencingToken(),
                        new WorkflowRef(
                                facts.workflowId(),
                                facts.workflowRunId(),
                                AgentRunWorkflow.WORKFLOW_TYPE,
                                facts.workflowBuildId()),
                        new AgentRunRef(
                                request.logicalRunId(),
                                request.attemptId(),
                                facts.logicalIdempotencyKey()),
                        new GraphRef(
                                graph.graphKey(),
                                graph.graphVersion(),
                                command.checkpointSchemaVersion(),
                                graph.checkpointId(),
                                graph.cognitiveRevision()),
                        new ModelRef(
                                metadata.promptVersion(),
                                metadata.modelProfileId(),
                                facts.provider(),
                                facts.model(),
                                command.requestHash(),
                                result.resultHash()),
                        Map.of(
                                "graph_command", command.schemaVersion(),
                                "graph_result", graph.schemaVersion(),
                                "output_schema", metadata.schemaVersion(),
                                "stream", request.streamProtocol(),
                                "execute_agent_run_request", request.schemaVersion(),
                                "execute_agent_run_result", result.schemaVersion()),
                        metadata.policyVersion(),
                        metadata.guardrailVersion(),
                        facts.toolVersions(),
                        inputs(command, facts.additionalInputs()),
                        facts.output(),
                        new ManifestUsage(
                                graph.usage().inputTokens(),
                                graph.usage().outputTokens(),
                                graph.usage().totalTokens(),
                                facts.latencyMs()),
                        command.traceparent(),
                        facts.finalizedAt());
        String manifestHash = ContractJson.sha256Hex(objectMapper.valueToTree(manifest));
        return new ManifestCommit(
                manifest,
                command.roomType(),
                facts.manifestUri(),
                manifestHash,
                result.resultHash(),
                result.lastSequenceNo());
    }

    private String manifestId(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result, FinalizationFacts facts) {
        String identityHash =
                ContractJson.sha256Hex(
                        objectMapper.valueToTree(
                                List.of(
                                        request.logicalRunId(),
                                        request.attemptId(),
                                        request.attemptNo(),
                                        facts.fencingToken(),
                                        result.resultHash())));
        return MANIFEST_ID_PREFIX
                + identityHash.substring(0, MANIFEST_ID_MAX_LENGTH - MANIFEST_ID_PREFIX.length());
    }

    private static List<ArtifactPointer> inputs(
            RoomGraphCommand command, List<ArtifactPointer> additionalInputs) {
        List<ArtifactPointer> inputs = new ArrayList<>();
        inputs.add(pointer(command.domainSnapshotRef()));
        if (command.eventRef() != null) {
            inputs.add(pointer(command.eventRef()));
        }
        inputs.addAll(additionalInputs);
        return List.copyOf(inputs);
    }

    private static ArtifactPointer pointer(RoomGraphCommand.SnapshotRef ref) {
        return new ArtifactPointer(ref.artifactId(), ref.schemaVersion(), ref.uri(), ref.sha256());
    }

    private static void validate(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result, FinalizationFacts facts) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(facts, "facts");
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || result.graphResult() == null
                || !request.agentRunId().equals(result.agentRunId())
                || !request.attemptId().equals(result.attemptId())
                || request.attemptNo() != result.attemptNo()) {
            throw new IllegalArgumentException(
                    "only the matching completed attempt can be manifested");
        }
        if (!facts.workflowId()
                .equals(
                        com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher
                                .workflowId(request.logicalRunId()))) {
            throw new IllegalArgumentException("workflowId is not bound to the logical run");
        }
        if (!result.resultHash().equals(facts.output().sha256())) {
            throw new IllegalArgumentException(
                    "output artifact hash does not match the formal result");
        }
    }

    public record FinalizationFacts(
            long fencingToken,
            String logicalIdempotencyKey,
            String workflowId,
            String workflowRunId,
            String workflowBuildId,
            String provider,
            String model,
            String manifestUri,
            ArtifactPointer output,
            List<ArtifactPointer> additionalInputs,
            List<String> toolVersions,
            long latencyMs,
            Instant finalizedAt) {
        public FinalizationFacts {
            if (fencingToken < 1 || latencyMs < 0) {
                throw new IllegalArgumentException(
                        "fencingToken must be positive and latencyMs must not be negative");
            }
            required(logicalIdempotencyKey, "logicalIdempotencyKey");
            required(workflowId, "workflowId");
            required(workflowRunId, "workflowRunId");
            required(workflowBuildId, "workflowBuildId");
            required(provider, "provider");
            required(model, "model");
            required(manifestUri, "manifestUri");
            if (!(manifestUri.startsWith("s3:")
                    || manifestUri.startsWith("minio:")
                    || manifestUri.startsWith("urn:"))) {
                throw new IllegalArgumentException(
                        "manifestUri must be an immutable artifact reference");
            }
            output = Objects.requireNonNull(output, "output");
            additionalInputs =
                    List.copyOf(Objects.requireNonNull(additionalInputs, "additionalInputs"));
            toolVersions = List.copyOf(Objects.requireNonNull(toolVersions, "toolVersions"));
            finalizedAt = Objects.requireNonNull(finalizedAt, "finalizedAt");
        }

        private static void required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
        }
    }
}
