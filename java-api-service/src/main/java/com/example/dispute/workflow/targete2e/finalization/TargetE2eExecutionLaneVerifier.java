package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.Lifecycle;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Exact fail-closed verifier for the isolated target-E2E candidate lane. */
public final class TargetE2eExecutionLaneVerifier {

    public static final String EXECUTION_LANE = "TARGET_E2E_CANDIDATE";
    public static final String GRAPH_KEY = "all-rooms.target-e2e.v2";
    public static final String GRAPH_VERSION = "target-e2e-graph.2026-08-18.1";
    public static final String CHECKPOINT_SCHEMA_VERSION = "target-e2e-checkpoint.v2";

    private final Clock clock;

    public TargetE2eExecutionLaneVerifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ActivationGrant requireAuthorized(
            AuthorizationDecision decision,
            AuthorizationRequest authorizationRequest,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            RuntimeContext runtime,
            TargetE2eIntakeFinalizationState state,
            TargetE2eFinalizationBindingVerifier.VerifiedEvidence evidence) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        if (decision.decision()
                != TargetE2eFinalizationActivationPort.Decision.ALLOWED) {
            throw rejected(
                    "TARGET_E2E_ACTIVATION_DENIED",
                    "target-E2E activation was denied: " + decision.decision());
        }
        ActivationGrant grant = Objects.requireNonNull(decision.grant(), "activation grant");
        Instant now = clock.instant();
        if (!EXECUTION_LANE.equals(grant.executionLane())) {
            throw rejected(
                    "TARGET_E2E_LANE_MISMATCH",
                    "finalization requires the exact target-E2E candidate lane");
        }
        if (grant.revokedAt() != null || grant.lifecycle() == Lifecycle.REVOKED_TERMINAL) {
            throw rejected("TARGET_E2E_ACTIVATION_REVOKED", "target-E2E activation is revoked");
        }
        requireLifecycle(grant, authorizationRequest, now);

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
        requireEqual(authorizationRequest.roomEpoch(), run.roomEpoch(), "authorized room epoch");
        requireEqual(
                authorizationRequest.roomFencingToken(),
                run.fencingToken(),
                "authorized room fence");
        requireEqual(grant.tenantSurrogate(), run.tenantSurrogate(), "activation tenant");
        if (!grant.allowedCaseIds().contains(run.caseId())
                || !grant.allowedRoomTypes().contains(RoomType.INTAKE)) {
            throw rejected(
                    "TARGET_E2E_SCOPE_MISMATCH",
                    "finalization is outside the activation case or room scope");
        }
        requireEqual(grant.expectedAgentBuildId(), runtime.workflowBuildId(), "agent build");
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
        requireEqual(request.streamProtocol(), "agent-stream.v3", "request stream protocol");
        requireEqual(run.protocol(), "agent-stream.v3", "persisted stream protocol");
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
                    "TARGET_E2E_FINAL_FRAME_MISSING",
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
                    "TARGET_E2E_ATTEMPT_FACTS_MISSING",
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

    private static void requireLifecycle(
            ActivationGrant grant, AuthorizationRequest request, Instant now) {
        if (now.isBefore(grant.issuedAt())) {
            throw rejected("TARGET_E2E_ACTIVATION_EXPIRED", "target-E2E activation is not active");
        }
        if (grant.lifecycle() == Lifecycle.ACTIVE) {
            if (!now.isBefore(grant.expiresAt())) {
                throw rejected(
                        "TARGET_E2E_ACTIVATION_EXPIRED",
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
                        "TARGET_E2E_DRAIN_PROOF_MISMATCH",
                        "DRAIN_ONLY finalization is not bound to pre-cutoff accepted work");
            }
            return;
        }
        throw rejected(
                "TARGET_E2E_ACTIVATION_DRAINED",
                "DRAINED activation cannot finalize additional work");
    }

    private static boolean requireTerminalEligibility(
            TargetE2eIntakeFinalizationState.LogicalRun run,
            TargetE2eIntakeFinalizationState.Attempt attempt) {
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
                    "TARGET_E2E_AGENT_RUN_NOT_FINALIZABLE",
                    "AgentRun is neither result-ready nor an exact committed replay");
        }
        requireEqual(run.resultReadyAttemptId(), attempt.attemptId(), "result-ready attempt");
        return committedReplay;
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_FACT_MISMATCH",
                    field + " conflicts with authoritative target-E2E state");
        }
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }
}
