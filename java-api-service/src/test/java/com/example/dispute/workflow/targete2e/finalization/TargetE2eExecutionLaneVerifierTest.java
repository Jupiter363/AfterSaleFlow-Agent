package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
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
                TargetE2eFinalizationFixture.activeDecision(),
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                fixture.state());

        var replayState = withTerminalState(fixture.state(), true);
        var replay = verifier.requireAuthorized(
                TargetE2eFinalizationFixture.activeDecision(),
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                replayState);

        assertThat(grant).isEqualTo(replay);
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
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision().grant();
        var wrongLane = AuthorizationDecision.allowed(new ActivationGrant(
                active.activationId(),
                "SHADOW",
                active.tenantSurrogate(),
                active.allowedCaseIds(),
                active.allowedRoomTypes(),
                active.expectedAgentBuildId(),
                active.issuedAt(),
                active.expiresAt(),
                null));
        assertThatThrownBy(() -> verifier.requireAuthorized(
                        wrongLane,
                        fixture.request(),
                        fixture.result(),
                        fixture.runtime(),
                        fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("exact target-E2E candidate lane");

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
        ActivationGrant active = TargetE2eFinalizationFixture.activeDecision().grant();
        var expired = AuthorizationDecision.allowed(new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.issuedAt().minusSeconds(300),
                TargetE2eFinalizationFixture.NOW,
                null));
        assertThatThrownBy(() -> verify(expired, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("not active");

        var revoked = AuthorizationDecision.allowed(new ActivationGrant(
                active.activationId(), active.executionLane(), active.tenantSurrogate(),
                active.allowedCaseIds(), active.allowedRoomTypes(), active.expectedAgentBuildId(),
                active.issuedAt(), active.expiresAt(),
                TargetE2eFinalizationFixture.NOW.minusSeconds(1)));
        assertThatThrownBy(() -> verify(revoked, fixture, fixture.state()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("revoked");
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
                verifier);

        assertThatThrownBy(() -> source.resolve(fixture.request(), fixture.result()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("facts were not found");
    }

    private void assertRejected(
            TargetE2eIntakeFinalizationState state,
            TargetE2eFinalizationFixture.Fixture fixture,
            String field) {
        assertThatThrownBy(() -> verify(
                        TargetE2eFinalizationFixture.activeDecision(), fixture, state))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining(field);
    }

    private void verify(
            AuthorizationDecision decision,
            TargetE2eFinalizationFixture.Fixture fixture,
            TargetE2eIntakeFinalizationState state) {
        verifier.requireAuthorized(
                decision,
                fixture.request(),
                fixture.result(),
                fixture.runtime(),
                state);
    }

    private static TargetE2eIntakeFinalizationState withTerminalState(
            TargetE2eIntakeFinalizationState state, boolean committed) {
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
                committed ? "COMPLETED" : state.attempt().attemptStatus());
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
        return new TargetE2eIntakeFinalizationState.Attempt(
                value.attemptId(), value.agentRunId(), value.attemptNo(), status, executor,
                value.provider(), value.modelProfileId(), value.modelVersion(), value.graphKey(),
                value.graphVersion(), value.checkpointSchemaVersion(), value.checkpointId(),
                value.promptVersion(), value.outputSchemaVersion(), value.policyVersion(),
                value.guardrailVersion(), value.requestHash(), value.commandId(),
                value.commandRequestHash(), value.logicalInputHash(), resultHash,
                value.finalFrameObserved(), value.lastSequenceNo(), value.latencyMs(),
                value.completedAt(), value.persistedCommand(), value.persistedResult());
    }
}
