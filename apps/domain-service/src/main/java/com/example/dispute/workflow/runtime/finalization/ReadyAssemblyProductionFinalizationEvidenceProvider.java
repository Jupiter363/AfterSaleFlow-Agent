package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationEnvironmentSource.EnvironmentEvidence;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Objects;

/** Loads parallel Intake finalization evidence from the immutable Java READY assembly. */
public final class ReadyAssemblyProductionFinalizationEvidenceProvider
        implements ProductionFinalizationEvidenceProvider {

    private final IntakeParallelAssemblyStore assemblyStore;
    private final ProductionFinalizationEnvironmentSource environmentSource;
    private final ObjectMapper objectMapper;

    public ReadyAssemblyProductionFinalizationEvidenceProvider(
            IntakeParallelAssemblyStore assemblyStore,
            ProductionFinalizationEnvironmentSource environmentSource,
            ObjectMapper objectMapper) {
        this.assemblyStore = Objects.requireNonNull(assemblyStore, "assemblyStore");
        this.environmentSource = Objects.requireNonNull(environmentSource, "environmentSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ProductionFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            ProductionIntakeFinalizationState state) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || !"agent-stream.v4".equals(request.streamProtocol())) {
            throw rejected(
                    "PRODUCTION_RUNTIME_PARALLEL_EVIDENCE_PROFILE_INVALID",
                    "READY assembly evidence requires the exact parallel Intake profile");
        }
        ReadyArtifact artifact = assemblyStore
                .loadReady(new ReadyLookup(
                        request.logicalRunId(),
                        request.attemptId(),
                        request.command().commandId(),
                        request.command().requestHash()))
                .orElseThrow(() -> rejected(
                        "PRODUCTION_RUNTIME_PARALLEL_READY_MISSING",
                        "parallel Intake READY evidence is absent"));
        if (result.graphResult() == null
                || !artifact.graphResultSha256().equals(result.resultHash())) {
            throw rejected(
                    "PRODUCTION_RUNTIME_PARALLEL_RESULT_MISMATCH",
                    "parallel READY result differs from the persisted AgentRun result");
        }
        JsonNode commandEnvelope = readCanonicalObject(
                artifact.canonicalCommandEnvelopeBytes(), "command envelope");
        String authorityActivationId = authorityActivationId(commandEnvelope);
        EnvironmentEvidence environment = Objects.requireNonNull(
                environmentSource.loadEnvironmentEvidence(authorityActivationId),
                "environment evidence source returned null");
        return new ProductionFinalizationEvidence(
                environment.manifestHash(),
                commandEnvelope,
                readCanonicalObject(
                        artifact.canonicalResultEnvelopeBytes(), "result envelope"),
                readCanonicalObject(
                        artifact.canonicalProposalSourceBytes(), "proposal source"),
                environment.isolatedDomainDbBinding());
    }

    private static String authorityActivationId(JsonNode commandEnvelope) {
        JsonNode value = commandEnvelope.get("activation_id");
        if (value == null
                || !value.isTextual()
                || !value.textValue().matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw rejected(
                    "PRODUCTION_RUNTIME_PARALLEL_EVIDENCE_INVALID",
                    "command envelope activation is invalid");
        }
        return value.textValue();
    }

    private JsonNode readCanonicalObject(byte[] bytes, String label) {
        try {
            JsonNode value = objectMapper.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw rejected(
                        "PRODUCTION_RUNTIME_PARALLEL_EVIDENCE_INVALID", label + " is not an object");
            }
            if (!MessageDigest.isEqual(bytes, ContractJson.canonicalize(value))) {
                throw rejected(
                        "PRODUCTION_RUNTIME_PARALLEL_EVIDENCE_INVALID",
                        label + " bytes are not canonical");
            }
            return value;
        } catch (IOException failure) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_PARALLEL_EVIDENCE_INVALID",
                    label + " cannot be decoded",
                    failure);
        }
    }

    private static ProductionFinalizationRejectedException rejected(
            String code, String message) {
        return new ProductionFinalizationRejectedException(code, message);
    }
}
