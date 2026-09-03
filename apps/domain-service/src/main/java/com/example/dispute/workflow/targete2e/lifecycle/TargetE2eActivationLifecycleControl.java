package com.example.dispute.workflow.targete2e.lifecycle;

import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.DrainCompletionProof;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.TransitionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Target-artifact lifecycle capability contract.
 *
 * <p>The coordinator deliberately has no persistence implementation of its own. All lifecycle
 * locking, durable identity verification, accepted-work checks and timestamp ordering remain in
 * {@link TargetE2eActivationLifecycleStore}. The target-only HTTP adapter authenticates its caller
 * before invoking this contract.
 */
public interface TargetE2eActivationLifecycleControl {

  RefreshOutcome refreshToDrainOnly(RefreshCommand command);

  TransitionOutcome markDrained(DrainCommand command);

  TransitionOutcome revokeTerminal(RevokeCommand command);

  /** Binds one control instance to the immutable identity measured for this deployment. */
  static TargetE2eActivationLifecycleControl bind(
      TargetE2eActivationLifecycleStore store, DeploymentBinding binding, Clock clock) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(clock, "clock");
    return new TargetE2eActivationLifecycleControl() {
      @Override
      public RefreshOutcome refreshToDrainOnly(RefreshCommand command) {
        Objects.requireNonNull(command, "command");
        binding.requireExact(command.identity());
        Instant observedAt = clock.instant();
        var observation =
            store.refresh(command.identity(), command.expiresAt(), observedAt);
        return new RefreshOutcome(
            observation.state(), observation.effectiveAt(), observedAt);
      }

      @Override
      public TransitionOutcome markDrained(DrainCommand command) {
        Objects.requireNonNull(command, "command");
        binding.requireExact(command.identity());
        TransitionResult result = store.markDrained(command.identity(), command.proof());
        return new TransitionOutcome(result, LifecycleState.DRAINED, clock.instant());
      }

      @Override
      public TransitionOutcome revokeTerminal(RevokeCommand command) {
        Objects.requireNonNull(command, "command");
        binding.requireExact(command.identity());
        TransitionResult result =
            store.revokeTerminal(command.identity(), command.revokedAt());
        return new TransitionOutcome(result, LifecycleState.REVOKED_TERMINAL, clock.instant());
      }
    };
  }

  /** Constant-time service capability comparison with no credential-bearing result. */
  static boolean serviceCapabilityMatches(String expected, String supplied) {
    if (expected == null || expected.isBlank() || supplied == null) {
      return false;
    }
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
    try {
      return MessageDigest.isEqual(expectedBytes, suppliedBytes);
    } finally {
      Arrays.fill(expectedBytes, (byte) 0);
      Arrays.fill(suppliedBytes, (byte) 0);
    }
  }

  record DeploymentBinding(
      boolean targetProfileActive,
      String environmentId,
      long environmentGeneration,
      String activationId,
      String manifestHash,
      String runtimeContextHash) {

    private static final Pattern IDENTIFIER =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}");
    private static final Pattern ACTIVATION_ID =
        Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");

    public DeploymentBinding {
      if (!targetProfileActive) {
        throw new IllegalStateException("target E2E lifecycle requires the target-e2e profile");
      }
      if (environmentId == null || !IDENTIFIER.matcher(environmentId).matches()) {
        throw new IllegalArgumentException("target E2E lifecycle environment binding is invalid");
      }
      if (environmentGeneration < 1
          || environmentGeneration > 9_007_199_254_740_991L) {
        throw new IllegalArgumentException("target E2E lifecycle generation binding is invalid");
      }
      if (activationId == null || !ACTIVATION_ID.matcher(activationId).matches()) {
        throw new IllegalArgumentException("target E2E lifecycle activation binding is invalid");
      }
      if (manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target E2E lifecycle manifest binding is invalid");
      }
      if (runtimeContextHash == null || !runtimeContextHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target E2E lifecycle runtime context binding is invalid");
      }
    }

    void requireExact(ActivationIdentity identity) {
      Objects.requireNonNull(identity, "identity");
      if (environmentGeneration != identity.environmentGeneration()
          || !constantTimeSame(environmentId, identity.environmentId())
          || !constantTimeSame(activationId, identity.activationId())
          || !constantTimeSame(manifestHash, identity.manifestHash())) {
        throw new SecurityException("target E2E lifecycle binding mismatch");
      }
    }

    private static boolean constantTimeSame(String left, String right) {
      byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
      byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
      try {
        return MessageDigest.isEqual(leftBytes, rightBytes);
      } finally {
        Arrays.fill(leftBytes, (byte) 0);
        Arrays.fill(rightBytes, (byte) 0);
      }
    }
  }

  record RefreshCommand(ActivationIdentity identity, Instant expiresAt) {
    public RefreshCommand {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  record DrainCommand(ActivationIdentity identity, DrainCompletionProof proof) {
    public DrainCommand {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(proof, "proof");
    }
  }

  record RevokeCommand(ActivationIdentity identity, Instant revokedAt) {
    public RevokeCommand {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(revokedAt, "revokedAt");
    }
  }

  record RefreshOutcome(LifecycleState state, Instant effectiveAt, Instant observedAt) {
    public RefreshOutcome {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(effectiveAt, "effectiveAt");
      Objects.requireNonNull(observedAt, "observedAt");
    }
  }

  record TransitionOutcome(
      TransitionResult result, LifecycleState targetState, Instant observedAt) {
    public TransitionOutcome {
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(targetState, "targetState");
      Objects.requireNonNull(observedAt, "observedAt");
    }

    public boolean successful() {
      return result == TransitionResult.TRANSITIONED
          || result == TransitionResult.ALREADY_IN_TARGET_STATE;
    }
  }
}
