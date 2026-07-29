package com.example.dispute.workflow.targete2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.targete2e.DrainAcceptedCommand;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.DrainCompletionProof;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleObservation;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.TransitionResult;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DeploymentBinding;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DrainCommand;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.RefreshCommand;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.RevokeCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TargetE2eActivationLifecycleControlTest {

  private static final String ENVIRONMENT = "target-preprod-a";
  private static final long GENERATION = 41;
  private static final String ACTIVATION = "p9act.v1.0123456789abcdef0123456789abcdef";
  private static final String MANIFEST_HASH = "a".repeat(64);
  private static final String RUNTIME_CONTEXT_HASH = "c".repeat(64);
  private static final Instant EXPIRES_AT = Instant.parse("2026-07-30T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-07-30T10:00:01Z");
  private static final ActivationIdentity IDENTITY =
      new ActivationIdentity(ENVIRONMENT, GENERATION, ACTIVATION, MANIFEST_HASH);

  @Test
  void serviceCapabilityAuthenticationIsFailClosedAndExact() {
    assertThat(
            TargetE2eActivationLifecycleControl.serviceCapabilityMatches(
                "batch4-private-capability", "batch4-private-capability"))
        .isTrue();
    assertThat(
            TargetE2eActivationLifecycleControl.serviceCapabilityMatches(
                "batch4-private-capability", "batch4-private-capabilitx"))
        .isFalse();
    assertThat(
            TargetE2eActivationLifecycleControl.serviceCapabilityMatches(
                "batch4-private-capability", null))
        .isFalse();
    assertThat(TargetE2eActivationLifecycleControl.serviceCapabilityMatches("", ""))
        .isFalse();
  }

  @Test
  void targetProfileAndExactDeploymentBindingFailClosedBeforeStoreAccess() {
    assertThatThrownBy(
            () -> new DeploymentBinding(
                false, ENVIRONMENT, GENERATION, ACTIVATION, MANIFEST_HASH, RUNTIME_CONTEXT_HASH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("target-e2e profile");

    StatefulStore store = new StatefulStore(LifecycleState.ACTIVE);
    TargetE2eActivationLifecycleControl control = control(store);
    ActivationIdentity wrongGeneration =
        new ActivationIdentity(ENVIRONMENT, GENERATION + 1, ACTIVATION, MANIFEST_HASH);
    ActivationIdentity wrongManifest =
        new ActivationIdentity(ENVIRONMENT, GENERATION, ACTIVATION, "b".repeat(64));

    assertThatThrownBy(
            () -> control.refreshToDrainOnly(new RefreshCommand(wrongGeneration, EXPIRES_AT)))
        .isInstanceOf(SecurityException.class)
        .hasMessage("target E2E lifecycle binding mismatch");
    assertThat(store.calls).isZero();

    assertThatThrownBy(
            () -> control.refreshToDrainOnly(new RefreshCommand(wrongManifest, EXPIRES_AT)))
        .isInstanceOf(SecurityException.class)
        .hasMessage("target E2E lifecycle binding mismatch");
    assertThat(store.calls).isZero();
  }

  @Test
  void illegalTransitionsAndIncompleteDrainProofAreRejectedWithoutAdvancing() {
    StatefulStore store = new StatefulStore(LifecycleState.ACTIVE);
    TargetE2eActivationLifecycleControl control = control(store);

    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY, drainProof(0, 0, true, NOW.plusSeconds(1))))
                .result())
        .isEqualTo(TransitionResult.REJECTED_WRONG_STATE);
    assertThat(
            control.revokeTerminal(new RevokeCommand(IDENTITY, NOW.plusSeconds(2))).result())
        .isEqualTo(TransitionResult.REJECTED_WRONG_STATE);

    control.refreshToDrainOnly(new RefreshCommand(IDENTITY, EXPIRES_AT));
    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY, drainProof(1, 0, true, NOW.plusSeconds(1))))
                .result())
        .isEqualTo(TransitionResult.REJECTED_UNRESOLVED_WORK);
    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY, drainProof(0, 1, true, NOW.plusSeconds(1))))
                .result())
        .isEqualTo(TransitionResult.REJECTED_REPLICAS_ATTACHED);
    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY, drainProof(0, 0, false, NOW.plusSeconds(1))))
                .result())
        .isEqualTo(TransitionResult.REJECTED_EVIDENCE_NOT_SEALED);
    assertThat(store.state).isEqualTo(LifecycleState.DRAIN_ONLY);
  }

  @Test
  void exactSuccessfulReplayIsIdempotent() {
    StatefulStore store = new StatefulStore(LifecycleState.DRAIN_ONLY);
    TargetE2eActivationLifecycleControl control = control(store);
    DrainCommand drain =
        new DrainCommand(
            IDENTITY, drainProof(0, 0, true, NOW.plusSeconds(1)));
    RevokeCommand revoke = new RevokeCommand(IDENTITY, NOW.plusSeconds(2));

    assertThat(control.markDrained(drain).result()).isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(control.markDrained(drain).result())
        .isEqualTo(TransitionResult.ALREADY_IN_TARGET_STATE);
    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY,
                        drainProof(0, 0, true, NOW.plusSeconds(3))))
                .result())
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    assertThat(control.revokeTerminal(revoke).result())
        .isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(control.revokeTerminal(revoke).result())
        .isEqualTo(TransitionResult.ALREADY_IN_TARGET_STATE);
    assertThat(
            control
                .revokeTerminal(new RevokeCommand(IDENTITY, NOW.plusSeconds(4)))
                .result())
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    assertThat(store.state).isEqualTo(LifecycleState.REVOKED_TERMINAL);
  }

  @Test
  void happyPathRefreshesToDrainThenSealsAndRevokesInTimestampOrder() {
    StatefulStore store = new StatefulStore(LifecycleState.REGISTERED);
    TargetE2eActivationLifecycleControl control = control(store);

    assertThat(control.refreshToDrainOnly(new RefreshCommand(IDENTITY, EXPIRES_AT)).state())
        .isEqualTo(LifecycleState.DRAIN_ONLY);
    assertThat(
            control
                .markDrained(
                    new DrainCommand(
                        IDENTITY, drainProof(0, 0, true, NOW.plusSeconds(1))))
                .result())
        .isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(
            control.revokeTerminal(new RevokeCommand(IDENTITY, NOW.plusSeconds(1))).result())
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    assertThat(
            control.revokeTerminal(new RevokeCommand(IDENTITY, NOW.plusSeconds(2))).result())
        .isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(store.state).isEqualTo(LifecycleState.REVOKED_TERMINAL);
  }

  private static TargetE2eActivationLifecycleControl control(StatefulStore store) {
    return TargetE2eActivationLifecycleControl.bind(
        store,
        new DeploymentBinding(
            true, ENVIRONMENT, GENERATION, ACTIVATION, MANIFEST_HASH, RUNTIME_CONTEXT_HASH),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static DrainCompletionProof drainProof(
      long unresolved, long replicas, boolean sealed, Instant completedAt) {
    return new DrainCompletionProof(
        unresolved,
        replicas,
        sealed,
        completedAt,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64));
  }

  private static final class StatefulStore implements TargetE2eActivationLifecycleStore {
    private LifecycleState state;
    private Instant drainOnlyAt;
    private Instant drainedAt;
    private Instant revokedAt;
    private int calls;

    private StatefulStore(LifecycleState state) {
      this.state = state;
      this.drainOnlyAt = state == LifecycleState.DRAIN_ONLY ? NOW : null;
    }

    @Override
    public LifecycleObservation refresh(
        ActivationIdentity identity, Instant expiresAt, Instant now) {
      calls++;
      if (state == LifecycleState.REGISTERED) {
        state = LifecycleState.ACTIVE;
      }
      if (state == LifecycleState.ACTIVE && !now.isBefore(expiresAt)) {
        state = LifecycleState.DRAIN_ONLY;
        drainOnlyAt = now;
      }
      Instant effectiveAt = state == LifecycleState.DRAIN_ONLY ? drainOnlyAt : now;
      return new LifecycleObservation(state, effectiveAt);
    }

    @Override
    public boolean hasAcceptedCommandBeforeExpiry(
        ActivationIdentity identity, DrainAcceptedCommand command, Instant expiresAt) {
      calls++;
      return false;
    }

    @Override
    public TransitionResult markDrained(
        ActivationIdentity identity, DrainCompletionProof proof) {
      calls++;
      if (proof.unresolvedAcceptedWork() != 0) {
        return TransitionResult.REJECTED_UNRESOLVED_WORK;
      }
      if (proof.attachedReplicas() != 0) {
        return TransitionResult.REJECTED_REPLICAS_ATTACHED;
      }
      if (!proof.evidenceSealed()) {
        return TransitionResult.REJECTED_EVIDENCE_NOT_SEALED;
      }
      if (state == LifecycleState.DRAINED) {
        return proof.completedAt().equals(drainedAt)
            ? TransitionResult.ALREADY_IN_TARGET_STATE
            : TransitionResult.REJECTED_TIMESTAMP_ORDER;
      }
      if (state != LifecycleState.DRAIN_ONLY) {
        return TransitionResult.REJECTED_WRONG_STATE;
      }
      if (proof.completedAt().isBefore(drainOnlyAt)) {
        return TransitionResult.REJECTED_TIMESTAMP_ORDER;
      }
      state = LifecycleState.DRAINED;
      drainedAt = proof.completedAt();
      return TransitionResult.TRANSITIONED;
    }

    @Override
    public TransitionResult revokeTerminal(ActivationIdentity identity, Instant revokedAt) {
      calls++;
      if (state == LifecycleState.REVOKED_TERMINAL) {
        return revokedAt.equals(this.revokedAt)
            ? TransitionResult.ALREADY_IN_TARGET_STATE
            : TransitionResult.REJECTED_TIMESTAMP_ORDER;
      }
      if (state != LifecycleState.DRAINED) {
        return TransitionResult.REJECTED_WRONG_STATE;
      }
      if (!revokedAt.isAfter(drainedAt)) {
        return TransitionResult.REJECTED_TIMESTAMP_ORDER;
      }
      state = LifecycleState.REVOKED_TERMINAL;
      this.revokedAt = revokedAt;
      return TransitionResult.TRANSITIONED;
    }
  }
}
