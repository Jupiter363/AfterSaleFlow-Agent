package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.util.Objects;

/** Loads authoritative state, then obtains and verifies one request-scoped activation grant. */
public final class TargetE2eAuthorizedIntakeFinalizationSource {

    private final TargetE2eIntakeFinalizationStateReader stateReader;
    private final TargetE2eFinalizationActivationPort activation;
    private final TargetE2eFinalizationRuntimeContextProvider runtimeContext;
    private final TargetE2eExecutionLaneVerifier verifier;

    public TargetE2eAuthorizedIntakeFinalizationSource(
            TargetE2eIntakeFinalizationStateReader stateReader,
            TargetE2eFinalizationActivationPort activation,
            TargetE2eFinalizationRuntimeContextProvider runtimeContext,
            TargetE2eExecutionLaneVerifier verifier) {
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public AuthorizedState resolve(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        TargetE2eIntakeFinalizationState state = stateReader.load(request, result)
                .orElseThrow(() -> new TargetE2eFinalizationRejectedException(
                        "TARGET_E2E_FINALIZATION_FACTS_MISSING",
                        "authoritative Intake finalization facts were not found"));
        RuntimeContext runtime = Objects.requireNonNull(
                runtimeContext.current(), "runtime context provider returned null");
        AuthorizationRequest authorizationRequest = new AuthorizationRequest(
                state.run().tenantSurrogate(),
                state.run().caseId(),
                state.run().roomId(),
                RoomType.INTAKE,
                request.agentRunId(),
                runtime.workflowId(),
                runtime.workflowRunId(),
                runtime.workflowBuildId());
        var decision = Objects.requireNonNull(
                activation.authorize(authorizationRequest),
                "activation authority returned null");
        ActivationGrant grant = verifier.requireAuthorized(
                decision, request, result, runtime, state);
        return new AuthorizedState(grant, runtime, state);
    }

    public record AuthorizedState(
            ActivationGrant activation,
            RuntimeContext runtime,
            TargetE2eIntakeFinalizationState state) {
        public AuthorizedState {
            Objects.requireNonNull(activation, "activation");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(state, "state");
        }
    }
}
