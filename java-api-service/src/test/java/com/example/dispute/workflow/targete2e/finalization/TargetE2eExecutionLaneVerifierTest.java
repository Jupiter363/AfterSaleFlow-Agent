package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AcceptedCommandProof;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.Lifecycle;
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
        var wrongLane = AuthorizationDecision.allowed(new ActivationGrant(
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

        var wrongGraph = AuthorizationDecision.allowed(new ActivationGrant(
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
        var expired = AuthorizationDecision.allowed(new ActivationGrant(
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

        var revoked = AuthorizationDecision.allowed(new ActivationGrant(
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
        var draining = AuthorizationDecision.allowed(new ActivationGrant(
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
        var wrongDrain = AuthorizationDecision.allowed(new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.graphKey(), active.graphVersion(), active.checkpointSchemaVersion(),
                active.activationManifestHash(), active.isolatedDomainDbBindingHash(),
                Lifecycle.DRAIN_ONLY, wrongProof, active.issuedAt(), drainExpiry, null));
        assertThatThrownBy(() -> verify(wrongDrain, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("pre-cutoff accepted work");

        var drained = AuthorizationDecision.allowed(new ActivationGrant(
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
