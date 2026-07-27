package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import java.time.Instant;
import java.util.Objects;

/** Issues the short-lived per-command credential; this is never the startup activation JWS. */
@FunctionalInterface
public interface TargetE2EGraphEnvelopeSigner {

  SignedEnvelope sign(
      TargetE2EGraphCommandEnvelope envelope,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding);

  record SignedEnvelope(
      String compactJws, String keyId, String jti, Instant issuedAt, Instant expiresAt) {

    public SignedEnvelope {
      Objects.requireNonNull(issuedAt, "issuedAt");
      Objects.requireNonNull(expiresAt, "expiresAt");
      if (!GraphCommandEnvelopeSigner.SignedEnvelope.isWellFormedCompactJws(compactJws)
          || !GraphCommandEnvelopeSigner.SignedEnvelope.isBoundedIdentifier(keyId)
          || !GraphCommandEnvelopeSigner.SignedEnvelope.isBoundedIdentifier(jti)
          || !expiresAt.isAfter(issuedAt)
          || expiresAt.isAfter(issuedAt.plusSeconds(60))) {
        throw new IllegalArgumentException("target Graph signed envelope is invalid");
      }
    }
  }
}
