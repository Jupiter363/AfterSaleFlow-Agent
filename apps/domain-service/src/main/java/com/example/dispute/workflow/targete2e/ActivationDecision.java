package com.example.dispute.workflow.targete2e;

import java.util.Objects;
import java.util.Optional;

/** Immutable, non-sensitive outcome returned to every guarded call site. */
public record ActivationDecision(
    boolean allowed,
    Reason reason,
    Optional<ActivationGrant> grant,
    Optional<AuthorizationMode> authorizationMode) {

  public ActivationDecision {
    Objects.requireNonNull(reason, "reason");
    grant = Objects.requireNonNull(grant, "grant");
    authorizationMode = Objects.requireNonNull(authorizationMode, "authorizationMode");
    if (allowed != (reason == Reason.ACTIVATED)
        || allowed != grant.isPresent()
        || allowed != authorizationMode.isPresent()) {
      throw new IllegalArgumentException("activation decision state is inconsistent");
    }
  }

  public static ActivationDecision activated(ActivationGrant grant) {
    return activated(grant, AuthorizationMode.ACTIVE);
  }

  public static ActivationDecision activated(
      ActivationGrant grant, AuthorizationMode authorizationMode) {
    return new ActivationDecision(
        true, Reason.ACTIVATED, Optional.of(grant), Optional.of(authorizationMode));
  }

  public static ActivationDecision denied(Reason reason) {
    if (reason == Reason.ACTIVATED) {
      throw new IllegalArgumentException("denied activation cannot be ACTIVATED");
    }
    return new ActivationDecision(false, reason, Optional.empty(), Optional.empty());
  }

  public Optional<String> activationId() {
    return grant.map(ActivationGrant::activationId);
  }

  public enum AuthorizationMode {
    ACTIVE,
    DRAIN_ACCEPTED_COMMAND
  }

  public enum Reason {
    ACTIVATED,
    DEFAULT_DENY,
    MALFORMED_MANIFEST,
    NON_CANONICAL_MANIFEST,
    UNTRUSTED_KEY,
    INVALID_SIGNATURE,
    INVALID_MANIFEST_HASH,
    INVALID_GRAPH_BINDING_HASH,
    AUTHORITY_VIOLATION,
    WRONG_CONTRACT,
    WRONG_RUNTIME,
    RUNTIME_MEASUREMENT_FAILED,
    NOT_YET_VALID,
    EXPIRED,
    REPLAYED,
    ENVIRONMENT_GENERATION_STALE,
    ENVIRONMENT_GENERATION_CONFLICT,
    REPLAY_STORE_FAILURE,
    WRONG_SCOPE,
    WRONG_TARGET,
    CASE_NOT_RESERVED,
    CASE_CAPACITY_EXHAUSTED,
    GENERATED_CASE_ID_CONFLICT,
    DRAIN_PROOF_REQUIRED,
    DRAINED,
    REVOKED,
    CASE_LEDGER_FAILURE
  }
}
