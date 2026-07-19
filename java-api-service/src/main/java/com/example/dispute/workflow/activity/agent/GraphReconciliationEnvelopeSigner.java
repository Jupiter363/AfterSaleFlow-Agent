package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Objects;

/** Issues one short-lived, result-only delivery credential for an immutable Graph command. */
public interface GraphReconciliationEnvelopeSigner {

    SignedEnvelope sign(
            RoomGraphCommand command,
            GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding);

    record SignedEnvelope(
            String compactJws,
            String keyId,
            String jti,
            Instant issuedAt,
            Instant expiresAt) {

        public SignedEnvelope {
            compactJws = requireText(compactJws, "compactJws");
            keyId = requireText(keyId, "keyId");
            jti = requireText(jti, "jti");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!GraphCommandEnvelopeSigner.SignedEnvelope.isWellFormedCompactJws(compactJws)) {
                throw new IllegalArgumentException(
                        "compactJws must be a bounded ES256 compact JWS");
            }
            if (!expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plusSeconds(60))) {
                throw new IllegalArgumentException("reconciliation credential lifetime is invalid");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
