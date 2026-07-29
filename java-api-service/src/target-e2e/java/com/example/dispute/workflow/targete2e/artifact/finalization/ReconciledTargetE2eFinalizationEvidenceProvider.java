package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.TargetE2eIsolatedDomainDbBinding;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEvidence;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEvidenceProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeFinalizationState;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalSourceClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** Reloads the exact immutable Graph envelopes instead of fabricating finalizer evidence. */
public final class ReconciledTargetE2eFinalizationEvidenceProvider
        implements TargetE2eFinalizationEvidenceProvider {

    private final JdbcTargetE2eFinalizationAuthority authority;
    private final TargetE2EGraphEnvelopeCodec codec;
    private final TargetE2EGraphEnvelopeSigner signer;
    private final HttpTargetE2EGraphReconciliationClient reconciliation;
    private final HttpTargetE2EGraphProposalSourceClient proposalSource;
    private final GraphRegistryBindingPolicy registryBindings;
    private final ObjectMapper objectMapper;

    public ReconciledTargetE2eFinalizationEvidenceProvider(
            JdbcTargetE2eFinalizationAuthority authority,
            TargetE2EGraphEnvelopeCodec codec,
            TargetE2EGraphEnvelopeSigner signer,
            HttpTargetE2EGraphReconciliationClient reconciliation,
            HttpTargetE2EGraphProposalSourceClient proposalSource,
            GraphRegistryBindingPolicy registryBindings,
            ObjectMapper objectMapper) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.proposalSource = Objects.requireNonNull(proposalSource, "proposalSource");
        this.registryBindings = Objects.requireNonNull(registryBindings, "registryBindings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public TargetE2eFinalizationEvidence resolve(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            TargetE2eIntakeFinalizationState state) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        var bindings = authority.evidenceBindings();
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
        var domainBinding = TargetE2eIsolatedDomainDbBinding.document(
                bindings.environmentId(),
                bindings.environmentGeneration(),
                bindings.activationId(),
                bindings.domainClusterIdentity(),
                bindings.domainDatabaseIdentity(),
                bindings.domainRuntimePrincipalIdentity());
        if (!bindings.domainDbBindingHash().equals(domainBinding.required("binding_hash").textValue())) {
            throw new IllegalStateException("stored target Domain DB binding hash is inconsistent");
        }
        return new TargetE2eFinalizationEvidence(
                bindings.manifestHash(),
                readObject(codec.encodeCommand(sealed.envelope()), "command envelope"),
                readObject(
                        codec.encodeResult(reconciled.envelope(), sealed.envelope(), proposal),
                        "result envelope"),
                proposal,
                domainBinding);
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
