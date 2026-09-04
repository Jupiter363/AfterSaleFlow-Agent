package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationBindingVerifier.VerifiedEvidence;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;
import java.util.Objects;

/** Loads authoritative state, then obtains and verifies one request-scoped activation grant. */
public final class ProductionAuthorizedIntakeFinalizationSource {

    private final ProductionIntakeFinalizationStateReader stateReader;
    private final ProductionFinalizationActivationPort activation;
    private final ProductionFinalizationRuntimeContextProvider runtimeContext;
    private final ProductionExecutionLaneVerifier verifier;
    private final ProductionFinalizationEvidenceProvider evidenceProvider;
    private final ProductionFinalizationBindingVerifier bindingVerifier;

    public ProductionAuthorizedIntakeFinalizationSource(
            ProductionIntakeFinalizationStateReader stateReader,
            ProductionFinalizationActivationPort activation,
            ProductionFinalizationRuntimeContextProvider runtimeContext,
            ProductionExecutionLaneVerifier verifier,
            ProductionFinalizationEvidenceProvider evidenceProvider,
            ProductionFinalizationBindingVerifier bindingVerifier) {
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.evidenceProvider = Objects.requireNonNull(evidenceProvider, "evidenceProvider");
        this.bindingVerifier = Objects.requireNonNull(bindingVerifier, "bindingVerifier");
    }

    public AuthorizedState resolve(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        ProductionIntakeFinalizationState state = stateReader.load(request, result)
                .orElseThrow(() -> new ProductionFinalizationRejectedException(
                        "PRODUCTION_RUNTIME_FINALIZATION_FACTS_MISSING",
                        "authoritative Intake finalization facts were not found"));
        RuntimeContext runtime = Objects.requireNonNull(
                runtimeContext.current(), "runtime context provider returned null");
        ProductionFinalizationEvidence rawEvidence = Objects.requireNonNull(
                evidenceProvider.resolve(request, result, runtime, state),
                "finalization evidence provider returned null");
        VerifiedEvidence evidence = bindingVerifier.verify(request, result, state, rawEvidence);
        AuthorizationRequest authorizationRequest = new AuthorizationRequest(
                state.run().tenantSurrogate(),
                state.run().caseId(),
                state.run().roomId(),
                RoomType.INTAKE,
                request.agentRunId(),
                runtime.workflowId(),
                runtime.workflowRunId(),
                runtime.workflowBuildId(),
                request.command().commandId(),
                evidence.commandHash(),
                evidence.commandEnvelopeHash(),
                request.command().roomEpoch(),
                state.run().fencingToken(),
                evidence.graphActivationId());
        var decision = Objects.requireNonNull(
                activation.authorize(authorizationRequest),
                "activation authority returned null");
        ActivationGrant grant = verifier.requireAuthorized(
                decision, authorizationRequest, request, result, runtime, state, evidence);
        bindingVerifier.requireGrantBindings(grant, evidence);
        return new AuthorizedState(grant, runtime, state, evidence);
    }

    public record AuthorizedState(
            ActivationGrant activation,
            RuntimeContext runtime,
            ProductionIntakeFinalizationState state,
            VerifiedEvidence evidence) {
        public AuthorizedState {
            Objects.requireNonNull(activation, "activation");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
