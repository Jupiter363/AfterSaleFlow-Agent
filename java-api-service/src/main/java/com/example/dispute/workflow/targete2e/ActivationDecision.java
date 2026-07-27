package com.example.dispute.workflow.targete2e;

import java.util.Objects;
import java.util.Optional;

/** Immutable, non-sensitive outcome returned to every guarded call site. */
public record ActivationDecision(boolean allowed, Reason reason, Optional<ActivationGrant> grant) {

  public ActivationDecision {
    Objects.requireNonNull(reason, "reason");
    grant = Objects.requireNonNull(grant, "grant");
    if (allowed != (reason == Reason.ACTIVATED) || allowed != grant.isPresent()) {
      throw new IllegalArgumentException("activation decision state is inconsistent");
    }
  }

  public static ActivationDecision activated(ActivationGrant grant) {
    return new ActivationDecision(true, Reason.ACTIVATED, Optional.of(grant));
  }

  public static ActivationDecision denied(Reason reason) {
    if (reason == Reason.ACTIVATED) {
      throw new IllegalArgumentException("denied activation cannot be ACTIVATED");
    }
    return new ActivationDecision(false, reason, Optional.empty());
  }

  public Optional<String> activationId() {
    return grant.map(ActivationGrant::activationId);
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
    NOT_YET_VALID,
    EXPIRED,
    REPLAYED,
    REPLAY_STORE_FAILURE,
    WRONG_SCOPE,
    WRONG_TARGET,
    CASE_NOT_RESERVED,
    CASE_CAPACITY_EXHAUSTED,
    CASE_LEDGER_FAILURE
  }
}
