package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Target-artifact boundary for re-reading durable FINAL/proposal/runtime evidence.
 *
 * <p>The implementation must read the authoritative AgentRun attempt, immutable FINAL output,
 * reconciled proposal source, and isolated Domain binding. It is intentionally separate from the
 * domain committer so no finalization path can turn an in-memory result into receipt evidence.
 */
@FunctionalInterface
public interface TargetEvidenceFinalizationEvidenceSource {
  Evidence resolve(
      TargetEvidenceCommandMaterialStore.MaterialSnapshot material,
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result);

  record Evidence(
      String roomId,
      long roomFencingToken,
      String isolatedDomainDbBindingHash,
      String proposalHash,
      String resultEnvelopeHash,
      String logicalIdempotencyKey,
      String provider,
      String model,
      String workflowId,
      String workflowRunId,
      String workflowBuildId,
      ArtifactPointer durableFinalOutput,
      List<String> toolVersions,
      long latencyMs,
      Instant completedAt) {
    public Evidence {
      required(roomId, "roomId");
      if (roomFencingToken < 1) throw new IllegalArgumentException("roomFencingToken must be positive");
      hash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
      hash(proposalHash, "proposalHash");
      hash(resultEnvelopeHash, "resultEnvelopeHash");
      required(logicalIdempotencyKey, "logicalIdempotencyKey");
      required(provider, "provider");
      required(model, "model");
      required(workflowId, "workflowId");
      required(workflowRunId, "workflowRunId");
      required(workflowBuildId, "workflowBuildId");
      durableFinalOutput = Objects.requireNonNull(durableFinalOutput, "durableFinalOutput");
      toolVersions = List.copyOf(Objects.requireNonNull(toolVersions, "toolVersions"));
      if (latencyMs < 0) throw new IllegalArgumentException("latencyMs must be non-negative");
      completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    AgentRunV2ManifestFactory.FinalizationFacts manifestFacts(
        long fencingToken, String activationId, ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
      if (!result.resultHash().equals(durableFinalOutput.sha256())) {
        throw new IllegalStateException("durable Evidence FINAL output does not bind result hash");
      }
      return new AgentRunV2ManifestFactory.FinalizationFacts(
          fencingToken, logicalIdempotencyKey, workflowId, workflowRunId, workflowBuildId,
          provider, model,
          "urn:production-runtime:agent-manifest:" + activationId + ":" + request.agentRunId()
              + ":" + request.attemptId() + ":" + result.resultHash(),
          durableFinalOutput, List.of(), toolVersions, latencyMs, completedAt);
    }

    private static void required(String value, String field) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static void hash(String value, String field) {
      if (value == null || !value.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
      }
    }
  }
}
