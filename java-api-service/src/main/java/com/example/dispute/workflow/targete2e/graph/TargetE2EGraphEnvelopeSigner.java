package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Issues the short-lived per-command credential; this is never the startup activation JWS. */
@FunctionalInterface
public interface TargetE2EGraphEnvelopeSigner {

  SignedEnvelope sign(
      TargetE2EGraphCommandEnvelope envelope,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding);

  default SignedEnvelope signParallel(
      TargetE2EGraphCommandEnvelope envelope,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
      ParallelDeliveryBinding deliveryBinding) {
    throw new UnsupportedOperationException("parallel target Graph credential signing is unavailable");
  }

  record ParallelDeliveryBinding(String phase, String admissionReceiptSha256) {

    private static final Set<String> PHASES = Set.of("PREPARE", "EXECUTE");

    public ParallelDeliveryBinding {
      if (!PHASES.contains(phase)
          || ("PREPARE".equals(phase) && admissionReceiptSha256 != null)
          || ("EXECUTE".equals(phase)
              && (admissionReceiptSha256 == null
                  || !admissionReceiptSha256.matches("[0-9a-f]{64}")))) {
        throw new IllegalArgumentException("parallel delivery binding is invalid");
      }
    }

    public static ParallelDeliveryBinding prepare() {
      return new ParallelDeliveryBinding("PREPARE", null);
    }

    public static ParallelDeliveryBinding execute(String admissionReceiptSha256) {
      return new ParallelDeliveryBinding("EXECUTE", admissionReceiptSha256);
    }
  }

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
