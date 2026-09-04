package com.example.dispute.workflow.runtime.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationEvidence;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationEvidenceProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationEnvironmentSource;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeFinalizationState;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphProposalSourceClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** Reloads the exact immutable Graph envelopes instead of fabricating finalizer evidence. */
public final class ReconciledProductionFinalizationEvidenceProvider
        implements ProductionFinalizationEvidenceProvider {

    private final ProductionFinalizationEnvironmentSource environmentSource;
    private final ProductionGraphEnvelopeCodec codec;
    private final ProductionGraphEnvelopeSigner signer;
    private final HttpProductionGraphReconciliationClient reconciliation;
    private final HttpProductionGraphProposalSourceClient proposalSource;
    private final GraphRegistryBindingPolicy registryBindings;
    private final ObjectMapper objectMapper;

    public ReconciledProductionFinalizationEvidenceProvider(
            ProductionFinalizationEnvironmentSource environmentSource,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            HttpProductionGraphReconciliationClient reconciliation,
            HttpProductionGraphProposalSourceClient proposalSource,
            GraphRegistryBindingPolicy registryBindings,
            ObjectMapper objectMapper) {
        this.environmentSource = Objects.requireNonNull(environmentSource, "environmentSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.proposalSource = Objects.requireNonNull(proposalSource, "proposalSource");
        this.registryBindings = Objects.requireNonNull(registryBindings, "registryBindings");
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
        var bindings = environmentSource.loadEnvironmentEvidence();
        var registryBinding = GraphRegistryBindingPolicy.requireExpected(
                registryBindings, GraphStreamVisibilityPolicy.Binding.from(request.command()));
        var sealed = codec.sealCommand(
                bindings.activationId(),
                state.run().fencingToken(),
                request.command(),
                registryBinding,
                signer);
        var cancellation = new AgentRunCancellationToken();
        var reconciled = reconciliation.reconcileAvailable(sealed, cancellation);
        if (!reconciled.envelope().result().equals(result.graphResult())
                || !reconciled.envelope().resultHash().equals(result.resultHash())) {
            throw new IllegalStateException(
                    "reconciled target Graph result differs from the persisted AgentRun result");
        }
        byte[] proposalBytes = proposalSource.loadSchemaValidatedProposalSource(
                sealed,
                reconciled.resultRef(),
                reconciled.envelope().proposalHash(),
                cancellation);
        JsonNode proposal = readObject(proposalBytes, "proposal source");
        return new ProductionFinalizationEvidence(
                bindings.manifestHash(),
                readObject(codec.encodeCommand(sealed.envelope()), "command envelope"),
                readObject(
                        codec.encodeResult(reconciled.envelope(), sealed.envelope(), proposal),
                        "result envelope"),
                proposal,
                bindings.isolatedDomainDbBinding());
    }

    private JsonNode readObject(byte[] bytes, String label) {
        try {
            JsonNode value = objectMapper.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException(label + " is not an object");
            }
            return value;
        } catch (IOException failure) {
            throw new IllegalArgumentException(label + " cannot be decoded", failure);
        }
    }
}
