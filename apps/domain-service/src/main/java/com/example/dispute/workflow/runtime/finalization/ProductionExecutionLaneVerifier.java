package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.Lifecycle;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.RuntimeAttestation;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider.RuntimeContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Exact fail-closed verifier for the isolated production-runtime candidate lane. */
public final class ProductionExecutionLaneVerifier {

    public static final String EXECUTION_LANE = "PRODUCTION";
    public static final String GRAPH_KEY = TargetTypedRoomProtocol.GRAPH_KEY;
    public static final String GRAPH_VERSION = TargetTypedRoomProtocol.GRAPH_VERSION;
    public static final String CHECKPOINT_SCHEMA_VERSION =
            TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION;

    private final Clock clock;

    public ProductionExecutionLaneVerifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ActivationGrant requireAuthorized(
            AuthorizationDecision decision,
            AuthorizationRequest authorizationRequest,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            ProductionIntakeFinalizationState state,
            ProductionFinalizationBindingVerifier.VerifiedEvidence evidence) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        if (decision.decision()
                != ProductionFinalizationActivationPort.Decision.ALLOWED) {
            throw rejected(
                    "PRODUCTION_RUNTIME_ACTIVATION_DENIED",
                    "production-runtime activation was denied: " + decision.decision());
        }
        ActivationGrant grant = Objects.requireNonNull(decision.grant(), "activation grant");
        RuntimeAttestation runtimeAttestation = Objects.requireNonNull(
                decision.runtimeAttestation(), "runtime attestation");
        Instant now = clock.instant();
        if (!EXECUTION_LANE.equals(grant.executionLane())) {
            throw rejected(
                    "PRODUCTION_RUNTIME_LANE_MISMATCH",
                    "finalization requires the exact production-runtime candidate lane");
        }
        if (grant.revokedAt() != null || grant.lifecycle() == Lifecycle.REVOKED_TERMINAL) {
            throw rejected("PRODUCTION_RUNTIME_ACTIVATION_REVOKED", "production-runtime activation is revoked");
        }
        requireLifecycle(grant, authorizationRequest, now);
        requireSameActivationIdentity(runtimeAttestation, grant);
        requireRuntimeLifecycle(runtimeAttestation, grant, now);

        var run = state.run();
        var attempt = state.attempt();
        var epoch = state.epoch();
        var projection = state.projection();
        var registration = state.threadBinding().registration();
        var command = request.command();
        var graphResult = result.graphResult();

        requireEqual(authorizationRequest.tenantSurrogate(), run.tenantSurrogate(), "authorized tenant");
        requireEqual(authorizationRequest.caseId(), run.caseId(), "authorized case");
        requireEqual(authorizationRequest.roomId(), run.roomId(), "authorized room");
        requireEqual(authorizationRequest.roomType(), RoomType.INTAKE, "authorized room type");
        requireEqual(authorizationRequest.agentRunId(), run.agentRunId(), "authorized AgentRun");
        requireEqual(authorizationRequest.workflowId(), runtime.workflowId(), "authorized workflow");
        requireEqual(
                authorizationRequest.workflowRunId(),
                runtime.workflowRunId(),
                "authorized workflow run");
        requireEqual(
                authorizationRequest.workflowBuildId(),
                runtime.workflowBuildId(),
                "authorized workflow build");
        requireEqual(authorizationRequest.commandId(), command.commandId(), "authorized command");
        requireEqual(
                authorizationRequest.authorityActivationId(),
                grant.activationId(),
                "command authority activation");
        requireEqual(authorizationRequest.roomEpoch(), run.roomEpoch(), "authorized room epoch");
        requireEqual(
                authorizationRequest.roomFencingToken(),
                run.fencingToken(),
                "authorized room fence");
        requireEqual(grant.tenantSurrogate(), run.tenantSurrogate(), "activation tenant");
        if (!grant.allowedCaseIds().contains(run.caseId())
                || !grant.allowedRoomTypes().contains(RoomType.INTAKE)) {
            throw rejected(
                    "PRODUCTION_RUNTIME_SCOPE_MISMATCH",
                    "finalization is outside the activation case or room scope");
        }
        requireEqual(
                runtimeAttestation.authorityActivationId(),
                grant.activationId(),
                "runtime handoff authority");
        requireEqual(
                runtimeAttestation.activationId(),
                runtime.activationId(),
                "runtime activation");
        requireEqual(
                runtimeAttestation.executionLane(), EXECUTION_LANE, "runtime activation lane");
        requireEqual(
                runtimeAttestation.tenantSurrogate(), run.tenantSurrogate(), "runtime tenant");
        if (!runtimeAttestation.allowedRoomTypes().contains(RoomType.INTAKE)) {
            throw rejected(
                    "PRODUCTION_RUNTIME_SCOPE_MISMATCH",
                    "finalization room is outside the runtime activation scope");
        }
        requireEqual(
                runtimeAttestation.expectedAgentBuildId(),
                runtime.workflowBuildId(),
                "runtime agent build");
        requireEqual(
                runtimeAttestation.activationManifestHash(),
                runtime.activationManifestHash(),
                "runtime activation manifest");
        requireEqual(
                runtimeAttestation.isolatedDomainDbBindingHash(),
                runtime.isolatedDomainDbBindingHash(),
                "runtime isolated Domain DB binding");
        requireEqual(
                runtimeAttestation.graphKey(), GRAPH_KEY, "runtime activation graph key");
        requireEqual(
                runtimeAttestation.graphVersion(),
                GRAPH_VERSION,
                "runtime activation graph version");
        requireEqual(
                runtimeAttestation.checkpointSchemaVersion(),
                CHECKPOINT_SCHEMA_VERSION,
                "runtime activation checkpoint schema");
        requireEqual(grant.graphKey(), GRAPH_KEY, "activation graph key");
        requireEqual(grant.graphVersion(), GRAPH_VERSION, "activation graph version");
        requireEqual(
                grant.checkpointSchemaVersion(),
                CHECKPOINT_SCHEMA_VERSION,
                "activation checkpoint schema");
        requireEqual(
                    AgentRunWorkflowIds.forLogicalRun(request.logicalRunId()),
                runtime.workflowId(),
                "AgentRun workflow id");

        requireEqual(request.agentRunId(), run.agentRunId(), "AgentRun id");
        requireEqual(request.logicalRunId(), run.agentRunId(), "logical run id");
        requireEqual(request.attemptId(), attempt.attemptId(), "attempt id");
        requireEqual(request.attemptNo(), attempt.attemptNo(), "attempt number");
        String expectedAgentRunProtocol = expectedAgentRunProtocol(request);
        requireEqual(
                request.streamProtocol(),
                expectedAgentRunProtocol,
                "request stream protocol");
        requireEqual(
                run.protocol(), expectedAgentRunProtocol, "persisted stream protocol");
        requireEqual(run.roomType(), RoomType.INTAKE.name(), "run room type");
        requireEqual(run.executorKind(), AgentRunExecutorKind.TEMPORAL_ACTIVITY.name(), "run executor");
        requireEqual(
                attempt.executorKind(),
                AgentRunExecutorKind.TEMPORAL_ACTIVITY.name(),
                "attempt executor");
        boolean committedReplay = requireTerminalEligibility(run, attempt);

        requireEqual(run.tenantSurrogate(), command.tenantSurrogate(), "command tenant");
        requireEqual(run.caseId(), command.caseId(), "command case");
        requireEqual(run.roomEpoch(), command.roomEpoch(), "command room epoch");
        requireEqual(run.processRevision(), command.processRevision(), "command process revision");
        if (attempt.attemptNo() == 1) {
            requireEqual(run.requestHash(), command.requestHash(), "run request hash");
        }
        requireEqual(run.logicalInputHash(), request.logicalInputHash(), "logical input hash");
        requireEqual(attempt.commandId(), command.commandId(), "attempt command id");
        requireEqual(attempt.requestHash(), command.requestHash(), "attempt request hash");
        requireEqual(attempt.commandRequestHash(), command.requestHash(), "command request hash");
        requireEqual(attempt.logicalInputHash(), request.logicalInputHash(), "attempt logical input hash");
        requireEqual(attempt.persistedCommand(), command, "persisted graph command");
        requireEqual(attempt.persistedResult(), result, "persisted execution result");

        requireEqual(result.agentRunId(), run.agentRunId(), "result AgentRun id");
        requireEqual(result.attemptId(), attempt.attemptId(), "result attempt id");
        requireEqual(result.resultHash(), attempt.resultHash(), "attempt result hash");
        requireEqual(result.resultHash(), run.finalResultHash(), "logical result hash");
        requireEqual(result.lastSequenceNo(), attempt.lastSequenceNo(), "final stream sequence");
        if (!attempt.finalFrameObserved()) {
            throw rejected(
                    "PRODUCTION_RUNTIME_FINAL_FRAME_MISSING",
                    "the persisted attempt has not observed its final frame");
        }
        requireEqual(state.graphOutput().sha256(), result.resultHash(), "output snapshot hash");
        requireEqual(
                state.graphOutput().schemaVersion(),
                graphResult.schemaVersion(),
                "output snapshot schema");
        requireEqual(attempt.graphKey(), graphResult.graphKey(), "graph key");
        requireEqual(attempt.graphVersion(), graphResult.graphVersion(), "graph version");
        requireEqual(attempt.checkpointId(), graphResult.checkpointId(), "checkpoint id");
        if (committedReplay) {
            requireEqual(
                    attempt.provider(), evidence.executionProvider(), "committed execution provider");
            requireEqual(
                    attempt.modelVersion(), evidence.executionModel(), "committed execution model");
        }
        requireEqual(
                attempt.modelProfileId(),
                graphResult.executionMetadata().modelProfileId(),
                "model profile");
        requireEqual(
                attempt.promptVersion(),
                graphResult.executionMetadata().promptVersion(),
                "prompt version");
        requireEqual(
                attempt.outputSchemaVersion(),
                graphResult.executionMetadata().schemaVersion(),
                "output schema");
        requireEqual(
                attempt.policyVersion(),
                graphResult.executionMetadata().policyVersion(),
                "policy version");
        requireEqual(
                attempt.guardrailVersion(),
                graphResult.executionMetadata().guardrailVersion(),
                "guardrail version");
        if (attempt.completedAt() == null || attempt.latencyMs() < 0) {
            throw rejected(
                    "PRODUCTION_RUNTIME_ATTEMPT_FACTS_MISSING",
                    "completed-at and non-negative latency are required");
        }

        requireEqual(epoch.epochId(), run.roomEpochId(), "room epoch id");
        requireEqual(epoch.tenantSurrogate(), run.tenantSurrogate(), "epoch tenant");
        requireEqual(epoch.caseId(), run.caseId(), "epoch case");
        requireEqual(epoch.roomId(), run.roomId(), "epoch room");
        requireEqual(epoch.roomType(), RoomType.INTAKE.name(), "epoch room type");
        requireEqual(epoch.writerMode(), WriterMode.TEMPORAL.name(), "epoch writer mode");
        requireEqual(epoch.lifecycleStatus(), "ACTIVE", "epoch lifecycle");
        requireEqual(epoch.provisioningStatus(), "READY", "epoch provisioning");
        requireEqual(epoch.roomEpoch(), run.roomEpoch(), "epoch number");
        requireEqual(epoch.processRevision(), run.processRevision(), "epoch process revision");
        requireEqual(epoch.fencingToken(), run.fencingToken(), "epoch fence");
        requireEqual(epoch.graphKey(), GRAPH_KEY, "epoch graph key");
        requireEqual(epoch.graphVersion(), GRAPH_VERSION, "epoch graph version");
        requireEqual(
                epoch.checkpointSchemaVersion(),
                CHECKPOINT_SCHEMA_VERSION,
                "epoch checkpoint schema");
        requireEqual(command.graphKey(), GRAPH_KEY, "command graph key");
        requireEqual(command.graphVersion(), GRAPH_VERSION, "command graph version");
        requireEqual(
                command.checkpointSchemaVersion(),
                CHECKPOINT_SCHEMA_VERSION,
                "command checkpoint schema");
        requireEqual(epoch.graphKey(), command.graphKey(), "command graph key");
        requireEqual(epoch.graphVersion(), command.graphVersion(), "command graph version");
        requireEqual(
                epoch.checkpointSchemaVersion(),
                command.checkpointSchemaVersion(),
                "command checkpoint schema");
        requireEqual(epoch.streamProtocol(), "agent-stream.v3", "epoch stream protocol");

        requireEqual(projection.tenantSurrogate(), run.tenantSurrogate(), "projection tenant");
        requireEqual(projection.caseId(), run.caseId(), "projection case");
        requireEqual(projection.currentRoom(), RoomType.INTAKE.name(), "projection room");
        requireEqual(projection.writerMode(), WriterMode.TEMPORAL.name(), "projection writer mode");
        requireEqual(projection.writerActivationStatus(), "READY", "writer activation");
        requireEqual(projection.processRevision(), run.processRevision(), "projection revision");
        requireEqual(projection.roomEpoch(), run.roomEpoch(), "projection room epoch");
        requireEqual(projection.fencingToken(), run.fencingToken(), "projection fence");
        requireEqual(projection.roomPhase(), command.stageCode(), "projection stage");
        requireEqual(projection.lastCommandSequence(), command.stageSequence(), "stage sequence");

        requireEqual(registration.writerMode(), WriterMode.TEMPORAL, "thread writer mode");
        requireEqual(state.threadRegistrationStatus(), "REGISTERED", "thread registration status");
        requireEqual(state.participantStatus(), "ACTIVE", "participant status");
        requireEqual(state.accessSessionStatus(), "ACTIVE", "access session status");
        requireEqual(state.agentSessionStatus(), "ACTIVE", "agent session status");
        requireEqual(state.threadBinding().fencingToken(), run.fencingToken(), "thread fence");
        requireEqual(registration.tenantSurrogate(), run.tenantSurrogate(), "thread tenant");
        requireEqual(registration.caseId(), run.caseId(), "thread case");
        requireEqual(registration.roomEpoch(), run.roomEpoch(), "thread room epoch");
        requireEqual(registration.threadId(), command.threadId(), "thread id");
        requireEqual(registration.graphKey(), GRAPH_KEY, "thread graph key");
        requireEqual(registration.graphVersion(), GRAPH_VERSION, "thread graph version");
        requireEqual(
                registration.checkpointSchemaVersion(),
                CHECKPOINT_SCHEMA_VERSION,
                "thread checkpoint schema");
        requireEqual(registration.graphKey(), command.graphKey(), "thread graph key");
        requireEqual(registration.graphVersion(), command.graphVersion(), "thread graph version");
        requireEqual(
                registration.checkpointSchemaVersion(),
                command.checkpointSchemaVersion(),
                "thread checkpoint schema");
        return grant;
    }

    static String expectedAgentRunProtocol(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        return ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                ? "agent-stream.v4"
                : "agent-stream.v3";
    }

    private static void requireLifecycle(
            ActivationGrant grant, AuthorizationRequest request, Instant now) {
        if (now.isBefore(grant.issuedAt())) {
            throw rejected("PRODUCTION_RUNTIME_ACTIVATION_EXPIRED", "production-runtime activation is not active");
        }
        if (grant.lifecycle() == Lifecycle.ACTIVE) {
            if (!now.isBefore(grant.expiresAt())) {
                throw rejected(
                        "PRODUCTION_RUNTIME_ACTIVATION_EXPIRED",
                        "expired activation must enter DRAIN_ONLY before finalization");
            }
            return;
        }
        if (grant.lifecycle() == Lifecycle.DRAIN_ONLY) {
            var proof = Objects.requireNonNull(
                    grant.acceptedCommandProof(), "DRAIN_ONLY accepted command proof");
            if (!proof.admittedAt().isBefore(grant.expiresAt())
                    || !proof.commandId().equals(request.commandId())
                    || !proof.commandHash().equals(request.commandHash())
                    || !proof.commandEnvelopeHash().equals(request.commandEnvelopeHash())
                    || proof.roomEpoch() != request.roomEpoch()
                    || proof.roomFencingToken() != request.roomFencingToken()) {
                throw rejected(
                        "PRODUCTION_RUNTIME_DRAIN_PROOF_MISMATCH",
                        "DRAIN_ONLY finalization is not bound to pre-cutoff accepted work");
            }
            return;
        }
        throw rejected(
                "PRODUCTION_RUNTIME_ACTIVATION_DRAINED",
                "DRAINED activation cannot finalize additional work");
    }

    private static void requireRuntimeLifecycle(
            RuntimeAttestation runtime, ActivationGrant authority, Instant now) {
        if (runtime.revokedAt() != null || runtime.lifecycle() == Lifecycle.REVOKED_TERMINAL) {
            throw rejected(
                    "PRODUCTION_RUNTIME_ACTIVATION_REVOKED",
                    "production-runtime runtime activation is revoked");
        }
        if (now.isBefore(runtime.issuedAt())) {
            throw rejected(
                    "PRODUCTION_RUNTIME_ACTIVATION_EXPIRED",
                    "production-runtime runtime activation is not active");
        }
        if (runtime.lifecycle() == Lifecycle.ACTIVE) {
            if (!now.isBefore(runtime.expiresAt())) {
                throw rejected(
                        "PRODUCTION_RUNTIME_ACTIVATION_EXPIRED",
                        "production-runtime runtime activation is expired");
            }
            return;
        }
        if (runtime.lifecycle() == Lifecycle.DRAIN_ONLY
                && runtime.activationId().equals(authority.activationId())
                && runtime.authorityActivationId().equals(authority.activationId())
                && authority.lifecycle() == Lifecycle.DRAIN_ONLY) {
            return;
        }
        throw rejected(
                "PRODUCTION_RUNTIME_ACTIVATION_DRAINED",
                "runtime activation cannot execute this authority finalizer");
    }

    private static void requireSameActivationIdentity(
            RuntimeAttestation runtime, ActivationGrant authority) {
        if (!runtime.activationId().equals(authority.activationId())) {
            return;
        }
        requireEqual(
                runtime.authorityActivationId(),
                authority.activationId(),
                "same-activation authority link");
        requireEqual(
                runtime.executionLane(),
                authority.executionLane(),
                "same-activation execution lane");
        requireEqual(
                runtime.tenantSurrogate(),
                authority.tenantSurrogate(),
                "same-activation tenant");
        requireEqual(
                runtime.allowedRoomTypes(),
                authority.allowedRoomTypes(),
                "same-activation room scope");
        requireEqual(
                runtime.expectedAgentBuildId(),
                authority.expectedAgentBuildId(),
                "same-activation agent build");
        requireEqual(runtime.graphKey(), authority.graphKey(), "same-activation graph key");
        requireEqual(
                runtime.graphVersion(),
                authority.graphVersion(),
                "same-activation graph version");
        requireEqual(
                runtime.checkpointSchemaVersion(),
                authority.checkpointSchemaVersion(),
                "same-activation checkpoint schema");
        requireEqual(
                runtime.activationManifestHash(),
                authority.activationManifestHash(),
                "same-activation manifest");
        requireEqual(
                runtime.isolatedDomainDbBindingHash(),
                authority.isolatedDomainDbBindingHash(),
                "same-activation isolated Domain DB binding");
        requireEqual(runtime.lifecycle(), authority.lifecycle(), "same-activation lifecycle");
        requireEqual(runtime.issuedAt(), authority.issuedAt(), "same-activation issuance");
        requireEqual(runtime.expiresAt(), authority.expiresAt(), "same-activation expiry");
        requireEqual(runtime.revokedAt(), authority.revokedAt(), "same-activation revocation");
    }

    private static boolean requireTerminalEligibility(
            ProductionIntakeFinalizationState.LogicalRun run,
            ProductionIntakeFinalizationState.Attempt attempt) {
        boolean resultReady = "RESULT_READY".equals(run.runStatus())
                && "UNCOMMITTED".equals(run.finalizationStatus())
                && "RESULT_READY".equals(attempt.attemptStatus())
                && run.committedAttemptId() == null;
        boolean committedReplay = "COMPLETED".equals(run.runStatus())
                && "COMMITTED".equals(run.finalizationStatus())
                && "COMPLETED".equals(attempt.attemptStatus())
                && attempt.attemptId().equals(run.committedAttemptId());
        if (!resultReady && !committedReplay) {
            throw rejected(
                    "PRODUCTION_RUNTIME_AGENT_RUN_NOT_FINALIZABLE",
                    "AgentRun is neither result-ready nor an exact committed replay");
        }
        requireEqual(run.resultReadyAttemptId(), attempt.attemptId(), "result-ready attempt");
        return committedReplay;
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw rejected(
                    "PRODUCTION_RUNTIME_FINALIZATION_FACT_MISMATCH",
                    field + " conflicts with authoritative production-runtime state");
        }
    }

    private static ProductionFinalizationRejectedException rejected(
            String code, String message) {
        return new ProductionFinalizationRejectedException(code, message);
    }
}
