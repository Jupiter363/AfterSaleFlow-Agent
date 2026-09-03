package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AcceptedCommandProof;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.Lifecycle;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.RuntimeAttestation;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetE2eExecutionLaneVerifierTest {

    private final TargetE2eExecutionLaneVerifier verifier = new TargetE2eExecutionLaneVerifier(
            Clock.fixed(TargetE2eFinalizationFixture.NOW, ZoneOffset.UTC));

    @Test
    void validCandidateAndCommittedReplayAreAcceptedDeterministically() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var grant = verifier.requireAuthorized(
                TargetE2eFinalizationFixture.activeDecision(fixture),
                fixture.authorizationRequest(),
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                fixture.state(),
                verified(fixture));

        var replayState = withTerminalState(fixture, true);
        var replay = verifier.requireAuthorized(
                TargetE2eFinalizationFixture.activeDecision(fixture),
                fixture.authorizationRequest(),
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                replayState,
                verified(fixture));

        assertThat(grant).isEqualTo(replay);
    }

    @Test
    void retryAttemptUsesCurrentAuthorityWithoutWeakeningOriginOrLogicalLineage() {
        var retry = TargetE2eFinalizationFixture.validRetry();
        assertThat(retry.state().run().requestHash())
                .isNotEqualTo(retry.request().command().requestHash());
        assertThat(retry.state().run().logicalInputHash())
                .isEqualTo(retry.request().logicalInputHash());
        assertThat(verifier.requireAuthorized(
                        TargetE2eFinalizationFixture.activeDecision(retry),
                        retry.authorizationRequest(),
                        retry.request(),
                        retry.result(),
                        retry.runtime(),
                        retry.state(),
                        verified(retry)))
                .isNotNull();

        var current = retry.state().attempt();
        assertRejected(
                withAttempt(
                        retry.state(),
                        copyAttemptAuthority(
                                current,
                                "f".repeat(64),
                                current.commandRequestHash(),
                                current.logicalInputHash(),
                                current.persistedCommand())),
                retry,
                "attempt request hash");
        assertRejected(
                withAttempt(
                        retry.state(),
                        copyAttemptAuthority(
                                current,
                                current.requestHash(),
                                current.commandRequestHash(),
                                current.logicalInputHash(),
                                TargetE2eFinalizationFixture.valid().request().command())),
                retry,
                "persisted graph command");
        assertRejected(
                withRun(
                        retry.state(),
                        copyRunHashes(
                                retry.state().run(),
                                retry.state().run().requestHash(),
                                "f".repeat(64))),
                retry,
                "logical input hash");

        var initial = TargetE2eFinalizationFixture.valid();
        assertRejected(
                withRun(
                        initial.state(),
                        copyRunHashes(
                                initial.state().run(),
                                "f".repeat(64),
                                initial.state().run().logicalInputHash())),
                initial,
                "run request hash");
    }

    @Test
    void committedReplayMustRetainTheVerifiedExecutionIdentity() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var replay = withTerminalState(fixture, true);
        var attempt = replay.attempt();
        var mismatched = withAttempt(
                replay,
                copyAttempt(
                        attempt,
                        attempt.executorKind(),
                        attempt.resultHash(),
                        attempt.attemptStatus(),
                        "other-provider",
                        attempt.modelVersion()));

        assertThatThrownBy(() -> verify(
                        TargetE2eFinalizationFixture.activeDecision(fixture), fixture, mismatched))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("committed execution provider");
    }

    @Test
    void staleFenceAndHashMismatchAreRejected() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var projection = fixture.state().projection();
        var stale = withProjection(
                fixture.state(),
                new TargetE2eIntakeFinalizationState.Projection(
                        projection.tenantSurrogate(),
                        projection.caseId(),
                        projection.currentRoom(),
                        projection.roomPhase(),
                        projection.writerMode(),
                        projection.writerActivationStatus(),
                        projection.processRevision(),
                        projection.roomEpoch(),
                        projection.fencingToken() + 1,
                        projection.lastCommandSequence()));
        assertRejected(stale, fixture, "projection fence");

        var attempt = fixture.state().attempt();
        var mismatched = withAttempt(
                fixture.state(),
                copyAttempt(attempt, attempt.executorKind(), "f".repeat(64), attempt.attemptStatus()));
        assertRejected(mismatched, fixture, "attempt result hash");
    }

    @Test
    void wrongLaneWriterModeAndExecutorAreRejected() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        var wrongLane = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(),
                "SHADOW",
                active.tenantSurrogate(),
                active.allowedCaseIds(),
                active.allowedRoomTypes(),
                active.expectedAgentBuildId(),
                active.graphKey(),
                active.graphVersion(),
                active.checkpointSchemaVersion(),
                active.activationManifestHash(),
                active.isolatedDomainDbBindingHash(),
                active.lifecycle(),
                active.acceptedCommandProof(),
                active.issuedAt(),
                active.expiresAt(),
                null));
        assertThatThrownBy(() -> verifier.requireAuthorized(
                        wrongLane,
                        fixture.authorizationRequest(),
                        fixture.request(),
                        fixture.result(),
                        fixture.runtime(),
                        fixture.state(),
                        verified(fixture)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("exact target-E2E candidate lane");

        var wrongGraph = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(),
                active.executionLane(),
                active.tenantSurrogate(),
                active.allowedCaseIds(),
                active.allowedRoomTypes(),
                active.expectedAgentBuildId(),
                "intake.v2",
                active.graphVersion(),
                active.checkpointSchemaVersion(),
                active.activationManifestHash(),
                active.isolatedDomainDbBindingHash(),
                active.lifecycle(),
                active.acceptedCommandProof(),
                active.issuedAt(),
                active.expiresAt(),
                null));
        assertThatThrownBy(() -> verify(wrongGraph, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("activation graph key");

        var epoch = fixture.state().epoch();
        var wrongMode = withEpoch(
                fixture.state(),
                new TargetE2eIntakeFinalizationState.Epoch(
                        epoch.epochId(), epoch.tenantSurrogate(), epoch.caseId(), epoch.roomId(),
                        epoch.roomType(), "SHADOW", epoch.lifecycleStatus(),
                        epoch.provisioningStatus(), epoch.roomEpoch(), epoch.processRevision(),
                        epoch.roomRevision(), epoch.fencingToken(), epoch.graphKey(),
                        epoch.graphVersion(), epoch.checkpointSchemaVersion(), epoch.streamProtocol()));
        assertRejected(wrongMode, fixture, "epoch writer mode");

        var attempt = fixture.state().attempt();
        var wrongExecutor = withAttempt(
                fixture.state(),
                copyAttempt(
                        attempt,
                        "LEGACY_WORKER",
                        attempt.resultHash(),
                        attempt.attemptStatus()));
        assertRejected(wrongExecutor, fixture, "attempt executor");
    }

    @Test
    void expiredAndRevokedActivationAreRejected() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        var expired = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.ACTIVE, null,
                active.issuedAt().minusSeconds(300),
                TargetE2eFinalizationFixture.NOW,
                null));
        assertThatThrownBy(() -> verify(expired, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("expired activation");

        var revoked = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.REVOKED_TERMINAL, null,
                active.issuedAt(), active.expiresAt(),
                TargetE2eFinalizationFixture.NOW.minusSeconds(1)));
        assertThatThrownBy(() -> verify(revoked, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void drainOnlyAcceptsOnlyTheExactPreCutoffCommandAndDrainedRejects() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        var request = fixture.authorizationRequest();
        var drainExpiry = TargetE2eFinalizationFixture.NOW.minusSeconds(1);
        var proof = new AcceptedCommandProof(
                request.commandId(),
                request.commandHash(),
                request.commandEnvelopeHash(),
                request.roomEpoch(),
                request.roomFencingToken(),
                drainExpiry.minusSeconds(1));
        var draining = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.DRAIN_ONLY, proof, active.issuedAt(), drainExpiry, null));
        assertThat(verifier.requireAuthorized(
                        draining, request, fixture.request(), fixture.result(),
                        fixture.runtime(), fixture.state(), verified(fixture)))
                .isEqualTo(draining.grant());

        var wrongProof = new AcceptedCommandProof(
                request.commandId(), "f".repeat(64), request.commandEnvelopeHash(),
                request.roomEpoch(), request.roomFencingToken(), proof.admittedAt());
        var wrongDrain = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.DRAIN_ONLY, wrongProof, active.issuedAt(), drainExpiry, null));
        assertThatThrownBy(() -> verify(wrongDrain, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("pre-cutoff accepted work");

        var drained = TargetE2eFinalizationFixture.decision(fixture, new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.DRAINED, null, active.issuedAt(), active.expiresAt(), null));
        assertThatThrownBy(() -> verify(drained, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("DRAINED");
    }

    @Test
    void sameActivationRequiresOneImmutableIdentity() {
        var fixture = TargetE2eFinalizationFixture.validParallel();
        ActivationGrant authority = TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        RuntimeAttestation exact =
                TargetE2eFinalizationFixture.runtimeAttestation(fixture, authority);

        assertRuntimeRejected(
                fixture,
                authority,
                copyRuntime(exact, exact.expectedAgentBuildId(), exact.activationManifestHash(),
                        exact.isolatedDomainDbBindingHash(), Lifecycle.DRAIN_ONLY),
                fixture.runtime(),
                "same-activation lifecycle");
        assertRuntimeRejected(
                fixture,
                authority,
                copyRuntime(exact, "other-build", exact.activationManifestHash(),
                        exact.isolatedDomainDbBindingHash(), exact.lifecycle()),
                fixture.runtime(),
                "same-activation agent build");
        assertRuntimeRejected(
                fixture,
                authority,
                copyRuntime(exact, exact.expectedAgentBuildId(), "f".repeat(64),
                        exact.isolatedDomainDbBindingHash(), exact.lifecycle()),
                fixture.runtime(),
                "same-activation manifest");
        assertRuntimeRejected(
                fixture,
                authority,
                copyRuntime(exact, exact.expectedAgentBuildId(), exact.activationManifestHash(),
                        "e".repeat(64), exact.lifecycle()),
                fixture.runtime(),
                "same-activation isolated Domain DB binding");
    }

    @Test
    void historicalDrainAuthorityRequiresADistinctActiveRuntime() {
        var fixture = TargetE2eFinalizationFixture.validParallel();
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        var request = fixture.authorizationRequest();
        var drainExpiry = TargetE2eFinalizationFixture.NOW.minusSeconds(1);
        var proof = new AcceptedCommandProof(
                request.commandId(),
                request.commandHash(),
                request.commandEnvelopeHash(),
                request.roomEpoch(),
                request.roomFencingToken(),
                drainExpiry.minusSeconds(1));
        var historical = new ActivationGrant(
                active.activationId(),
                active.executionLane(),
                active.tenantSurrogate(),
                active.allowedCaseIds(),
                active.allowedRoomTypes(),
                active.expectedAgentBuildId(),
                active.graphKey(),
                active.graphVersion(),
                active.checkpointSchemaVersion(),
                active.activationManifestHash(),
                active.isolatedDomainDbBindingHash(),
                Lifecycle.DRAIN_ONLY,
                proof,
                active.issuedAt(),
                drainExpiry,
                null);
        String currentActivationId = "p9act.v1." + "2".repeat(32);
        String currentManifestHash = "8".repeat(64);
        String currentDbBindingHash = "7".repeat(64);
        var currentRuntime = new RuntimeContext(
                fixture.runtime().workflowId(),
                fixture.runtime().workflowRunId(),
                fixture.runtime().workflowBuildId(),
                currentActivationId,
                currentManifestHash,
                currentDbBindingHash);
        var activeCurrent = new RuntimeAttestation(
                currentActivationId,
                historical.activationId(),
                historical.executionLane(),
                historical.tenantSurrogate(),
                historical.allowedRoomTypes(),
                currentRuntime.workflowBuildId(),
                historical.graphKey(),
                historical.graphVersion(),
                historical.checkpointSchemaVersion(),
                currentManifestHash,
                currentDbBindingHash,
                Lifecycle.ACTIVE,
                TargetE2eFinalizationFixture.NOW.minusSeconds(120),
                TargetE2eFinalizationFixture.NOW.plusSeconds(120),
                null);

        assertThat(verifier.requireAuthorized(
                        AuthorizationDecision.allowed(historical, activeCurrent),
                        request,
                        fixture.request(),
                        fixture.result(),
                        currentRuntime,
                        fixture.state(),
                        verified(fixture)))
                .isEqualTo(historical);

        assertRuntimeRejected(
                fixture,
                historical,
                copyRuntime(activeCurrent, "other-build", currentManifestHash,
                        currentDbBindingHash, Lifecycle.ACTIVE),
                currentRuntime,
                "runtime agent build");
        assertRuntimeRejected(
                fixture,
                historical,
                copyRuntime(activeCurrent, currentRuntime.workflowBuildId(), "f".repeat(64),
                        currentDbBindingHash, Lifecycle.ACTIVE),
                currentRuntime,
                "runtime activation manifest");
        assertRuntimeRejected(
                fixture,
                historical,
                copyRuntime(activeCurrent, currentRuntime.workflowBuildId(), currentManifestHash,
                        "e".repeat(64), Lifecycle.ACTIVE),
                currentRuntime,
                "runtime isolated Domain DB binding");

        var drainingCurrent = new RuntimeAttestation(
                activeCurrent.activationId(),
                activeCurrent.authorityActivationId(),
                activeCurrent.executionLane(),
                activeCurrent.tenantSurrogate(),
                activeCurrent.allowedRoomTypes(),
                activeCurrent.expectedAgentBuildId(),
                activeCurrent.graphKey(),
                activeCurrent.graphVersion(),
                activeCurrent.checkpointSchemaVersion(),
                activeCurrent.activationManifestHash(),
                activeCurrent.isolatedDomainDbBindingHash(),
                Lifecycle.DRAIN_ONLY,
                activeCurrent.issuedAt(),
                drainExpiry,
                null);
        assertThatThrownBy(() -> verifier.requireAuthorized(
                        AuthorizationDecision.allowed(historical, drainingCurrent),
                        request,
                        fixture.request(),
                        fixture.result(),
                        currentRuntime,
                        fixture.state(),
                        verified(fixture)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("runtime activation cannot execute");
    }

    @Test
    void missingFactsAreRejectedBeforeActivation() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var source = new TargetE2eAuthorizedIntakeFinalizationSource(
                (request, result) -> Optional.empty(),
                request -> {
                    throw new AssertionError("activation must not run without persisted facts");
                },
                () -> fixture.runtime(),
                verifier,
                (request, result, runtime, state) -> fixture.evidence(),
                new TargetE2eFinalizationBindingVerifier(
                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                .findAndAddModules()
                                .build()));

        assertThatThrownBy(() -> source.resolve(fixture.request(), fixture.result()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("facts were not found");
    }

    private void assertRejected(
            TargetE2eIntakeFinalizationState state,
            TargetE2eFinalizationFixture.Fixture fixture,
            String field) {
        assertThatThrownBy(() -> verify(
                        TargetE2eFinalizationFixture.activeDecision(fixture), fixture, state))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining(field);
    }

    private void verify(
            AuthorizationDecision decision,
            TargetE2eFinalizationFixture.Fixture fixture,
            TargetE2eIntakeFinalizationState state) {
        verifier.requireAuthorized(
                decision,
                fixture.authorizationRequest(),
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                state,
                verified(fixture));
    }

    private void assertRuntimeRejected(
            TargetE2eFinalizationFixture.Fixture fixture,
            ActivationGrant authority,
            RuntimeAttestation runtimeAttestation,
            RuntimeContext runtime,
            String field) {
        assertThatThrownBy(() -> verifier.requireAuthorized(
                        AuthorizationDecision.allowed(authority, runtimeAttestation),
                        fixture.authorizationRequest(),
                        fixture.request(),
                        fixture.result(),
                        runtime,
                        fixture.state(),
                        verified(fixture)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining(field);
    }

    private static RuntimeAttestation copyRuntime(
            RuntimeAttestation value,
            String buildId,
            String manifestHash,
            String dbBindingHash,
            Lifecycle lifecycle) {
        return new RuntimeAttestation(
                value.activationId(),
                value.authorityActivationId(),
                value.executionLane(),
                value.tenantSurrogate(),
                value.allowedRoomTypes(),
                buildId,
                value.graphKey(),
                value.graphVersion(),
                value.checkpointSchemaVersion(),
                manifestHash,
                dbBindingHash,
                lifecycle,
                value.issuedAt(),
                value.expiresAt(),
                value.revokedAt());
    }

    private static TargetE2eFinalizationBindingVerifier.VerifiedEvidence verified(
            TargetE2eFinalizationFixture.Fixture fixture) {
        return new TargetE2eFinalizationBindingVerifier(
                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                .findAndAddModules()
                                .build())
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());
    }

    private static TargetE2eIntakeFinalizationState withTerminalState(
            TargetE2eFinalizationFixture.Fixture fixture, boolean committed) {
        var state = fixture.state();
        var run = state.run();
        var terminalRun = new TargetE2eIntakeFinalizationState.LogicalRun(
                run.agentRunId(), run.tenantSurrogate(), run.caseId(), run.roomId(),
                run.roomEpochId(), run.roomType(), run.logicalIdempotencyKey(), run.protocol(),
                run.executorKind(), committed ? "COMPLETED" : run.runStatus(),
                committed ? "COMMITTED" : run.finalizationStatus(), run.roomEpoch(),
                run.processRevision(), run.fencingToken(), run.requestHash(),
                run.logicalInputHash(), run.resultReadyAttemptId(),
                committed ? state.attempt().attemptId() : run.committedAttemptId(),
                run.finalResultHash());
        var terminalAttempt = copyAttempt(
                state.attempt(),
                state.attempt().executorKind(),
                state.attempt().resultHash(),
                committed ? "COMPLETED" : state.attempt().attemptStatus(),
                committed ? verified(fixture).executionProvider() : state.attempt().provider(),
                committed ? verified(fixture).executionModel() : state.attempt().modelVersion());
        return new TargetE2eIntakeFinalizationState(
                terminalRun, terminalAttempt, state.epoch(), state.projection(),
                state.threadRegistrationStatus(),
                state.participantStatus(), state.accessSessionStatus(), state.agentSessionStatus(),
                state.threadBinding(), state.initialSnapshot(), state.event(), state.graphOutput());
    }

    private static TargetE2eIntakeFinalizationState withProjection(
            TargetE2eIntakeFinalizationState state,
            TargetE2eIntakeFinalizationState.Projection projection) {
        return new TargetE2eIntakeFinalizationState(
                state.run(), state.attempt(), state.epoch(), projection,
                state.threadRegistrationStatus(), state.participantStatus(),
                state.accessSessionStatus(), state.agentSessionStatus(), state.threadBinding(),
                state.initialSnapshot(), state.event(), state.graphOutput());
    }

    private static TargetE2eIntakeFinalizationState withEpoch(
            TargetE2eIntakeFinalizationState state,
            TargetE2eIntakeFinalizationState.Epoch epoch) {
        return new TargetE2eIntakeFinalizationState(
                state.run(), state.attempt(), epoch, state.projection(),
                state.threadRegistrationStatus(), state.participantStatus(),
                state.accessSessionStatus(), state.agentSessionStatus(), state.threadBinding(),
                state.initialSnapshot(), state.event(), state.graphOutput());
    }

    private static TargetE2eIntakeFinalizationState withAttempt(
            TargetE2eIntakeFinalizationState state,
            TargetE2eIntakeFinalizationState.Attempt attempt) {
        return new TargetE2eIntakeFinalizationState(
                state.run(), attempt, state.epoch(), state.projection(),
                state.threadRegistrationStatus(), state.participantStatus(),
                state.accessSessionStatus(), state.agentSessionStatus(), state.threadBinding(),
                state.initialSnapshot(), state.event(), state.graphOutput());
    }

    private static TargetE2eIntakeFinalizationState withRun(
            TargetE2eIntakeFinalizationState state,
            TargetE2eIntakeFinalizationState.LogicalRun run) {
        return new TargetE2eIntakeFinalizationState(
                run, state.attempt(), state.epoch(), state.projection(),
                state.threadRegistrationStatus(), state.participantStatus(),
                state.accessSessionStatus(), state.agentSessionStatus(), state.threadBinding(),
                state.initialSnapshot(), state.event(), state.graphOutput());
    }

    private static TargetE2eIntakeFinalizationState.LogicalRun copyRunHashes(
            TargetE2eIntakeFinalizationState.LogicalRun value,
            String requestHash,
            String logicalInputHash) {
        return new TargetE2eIntakeFinalizationState.LogicalRun(
                value.agentRunId(), value.tenantSurrogate(), value.caseId(), value.roomId(),
                value.roomEpochId(), value.roomType(), value.logicalIdempotencyKey(),
                value.protocol(), value.executorKind(), value.runStatus(),
                value.finalizationStatus(), value.roomEpoch(), value.processRevision(),
                value.fencingToken(), requestHash, logicalInputHash,
                value.resultReadyAttemptId(), value.committedAttemptId(),
                value.finalResultHash());
    }

    private static TargetE2eIntakeFinalizationState.Attempt copyAttemptAuthority(
            TargetE2eIntakeFinalizationState.Attempt value,
            String requestHash,
            String commandRequestHash,
            String logicalInputHash,
            RoomGraphCommand persistedCommand) {
        return new TargetE2eIntakeFinalizationState.Attempt(
                value.attemptId(), value.agentRunId(), value.attemptNo(), value.attemptStatus(),
                value.executorKind(), value.provider(), value.modelProfileId(),
                value.modelVersion(), value.graphKey(), value.graphVersion(),
                value.checkpointSchemaVersion(), value.checkpointId(), value.promptVersion(),
                value.outputSchemaVersion(), value.policyVersion(), value.guardrailVersion(),
                requestHash, value.commandId(), commandRequestHash, logicalInputHash,
                value.resultHash(), value.finalFrameObserved(), value.lastSequenceNo(),
                value.latencyMs(), value.completedAt(), persistedCommand,
                value.persistedResult());
    }

    private static TargetE2eIntakeFinalizationState.Attempt copyAttempt(
            TargetE2eIntakeFinalizationState.Attempt value,
            String executor,
            String resultHash,
            String status) {
        return copyAttempt(
                value, executor, resultHash, status, value.provider(), value.modelVersion());
    }

    private static TargetE2eIntakeFinalizationState.Attempt copyAttempt(
            TargetE2eIntakeFinalizationState.Attempt value,
            String executor,
            String resultHash,
            String status,
            String provider,
            String modelVersion) {
        return new TargetE2eIntakeFinalizationState.Attempt(
                value.attemptId(), value.agentRunId(), value.attemptNo(), status, executor,
                provider, value.modelProfileId(), modelVersion, value.graphKey(),
                value.graphVersion(), value.checkpointSchemaVersion(), value.checkpointId(),
                value.promptVersion(), value.outputSchemaVersion(), value.policyVersion(),
                value.guardrailVersion(), value.requestHash(), value.commandId(),
                value.commandRequestHash(), value.logicalInputHash(), resultHash,
                value.finalFrameObserved(), value.lastSequenceNo(), value.latencyMs(),
                value.completedAt(), value.persistedCommand(), value.persistedResult());
    }
}
