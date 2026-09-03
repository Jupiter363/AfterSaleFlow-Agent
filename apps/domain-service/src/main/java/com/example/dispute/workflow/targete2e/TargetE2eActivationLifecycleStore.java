package com.example.dispute.workflow.targete2e;

import java.time.Instant;
import java.util.Objects;

/** Durable activation lifecycle and pre-expiry command-admission authority. */
public interface TargetE2eActivationLifecycleStore {

  /**
   * Returns the durable current state after a successful replay-ledger registration or attach.
   * Implementations must never create an identity absent that registration. Before expiry they
   * atomically advance REGISTERED to ACTIVE; at or after expiry they preserve the exact order by
   * advancing REGISTERED through ACTIVE to DRAIN_ONLY, or ACTIVE to DRAIN_ONLY. They never move a
   * later state backward.
   */
  LifecycleObservation refresh(ActivationIdentity identity, Instant expiresAt, Instant now);

  /**
   * Proves an immutable admission row with every supplied command/fence/hash field and an admission
   * timestamp strictly before {@code expiresAt}.
   */
  boolean hasAcceptedCommandBeforeExpiry(
      ActivationIdentity identity, DrainAcceptedCommand command, Instant expiresAt);

  /**
   * Transitions DRAIN_ONLY to DRAINED only when the supplied closure proof is durable and exact.
   * An idempotent replay must carry the exact timestamp persisted by the original transition.
   */
  TransitionResult markDrained(ActivationIdentity identity, DrainCompletionProof proof);

  /**
   * Transitions DRAINED to REVOKED_TERMINAL; no earlier state may be revoked terminal and {@code
   * revokedAt} must be strictly after the durable drained timestamp. An idempotent replay must
   * carry the exact timestamp persisted by the original transition.
   */
  TransitionResult revokeTerminal(ActivationIdentity identity, Instant revokedAt);

  static TargetE2eActivationLifecycleStore denyAll() {
    return new TargetE2eActivationLifecycleStore() {
      @Override
      public LifecycleObservation refresh(
          ActivationIdentity identity, Instant expiresAt, Instant now) {
        return new LifecycleObservation(LifecycleState.REVOKED_TERMINAL, now);
      }

      @Override
      public boolean hasAcceptedCommandBeforeExpiry(
          ActivationIdentity identity, DrainAcceptedCommand command, Instant expiresAt) {
        return false;
      }

      @Override
      public TransitionResult markDrained(ActivationIdentity identity, DrainCompletionProof proof) {
        return TransitionResult.REJECTED_WRONG_STATE;
      }

      @Override
      public TransitionResult revokeTerminal(ActivationIdentity identity, Instant revokedAt) {
        return TransitionResult.REJECTED_WRONG_STATE;
      }
    };
  }

  enum LifecycleState {
    REGISTERED,
    ACTIVE,
    DRAIN_ONLY,
    DRAINED,
    REVOKED_TERMINAL
  }

  enum TransitionResult {
    TRANSITIONED,
    ALREADY_IN_TARGET_STATE,
    REJECTED_WRONG_STATE,
    REJECTED_UNRESOLVED_WORK,
    REJECTED_REPLICAS_ATTACHED,
    REJECTED_EVIDENCE_NOT_SEALED,
    REJECTED_TIMESTAMP_ORDER
  }

  record LifecycleObservation(LifecycleState state, Instant effectiveAt) {
    public LifecycleObservation {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(effectiveAt, "effectiveAt");
    }
  }

  record ActivationIdentity(
      String environmentId, long environmentGeneration, String activationId, String manifestHash) {

    public ActivationIdentity {
      TargetE2eActivationContract.identifier(environmentId, "environmentId");
      TargetE2eActivationContract.generation(environmentGeneration);
      TargetE2eActivationContract.activationId(activationId);
      TargetE2eActivationContract.sha256(manifestHash, "manifestHash");
    }
  }

  record DrainCompletionProof(
      long unresolvedAcceptedWork,
      long attachedReplicas,
      boolean evidenceSealed,
      Instant completedAt,
      String proofHash,
      String evidenceLedgerHeadHash,
      String forensicManifestHash,
      String attestationKeySha256) {

    public DrainCompletionProof {
      if (unresolvedAcceptedWork < 0 || attachedReplicas < 0) {
        throw new IllegalArgumentException("drain counters cannot be negative");
      }
      Objects.requireNonNull(completedAt, "completedAt");
      TargetE2eActivationContract.sha256(proofHash, "proofHash");
      TargetE2eActivationContract.sha256(
          evidenceLedgerHeadHash, "evidenceLedgerHeadHash");
      TargetE2eActivationContract.sha256(forensicManifestHash, "forensicManifestHash");
      TargetE2eActivationContract.sha256(attestationKeySha256, "attestationKeySha256");
    }

    public boolean complete() {
      return unresolvedAcceptedWork == 0 && attachedReplicas == 0 && evidenceSealed;
    }
  }
}
