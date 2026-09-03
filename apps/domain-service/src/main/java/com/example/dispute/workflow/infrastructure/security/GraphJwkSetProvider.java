package com.example.dispute.workflow.infrastructure.security;

import java.util.List;
import java.util.Objects;

/** Publishes the verification-only projection of the retained Graph signing keys. */
@FunctionalInterface
public interface GraphJwkSetProvider {

    JwkSet jwkSet();

    record JwkSet(List<PublicJwk> keys) {

        public JwkSet {
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("Graph JWKS cannot be empty");
            }
        }
    }

    record PublicJwk(
            String kty,
            String use,
            String alg,
            String crv,
            String kid,
            String x,
            String y) {

        public PublicJwk {
            if (!"EC".equals(kty)
                    || !"sig".equals(use)
                    || !"ES256".equals(alg)
                    || !"P-256".equals(crv)
                    || kid == null
                    || kid.isBlank()
                    || x == null
                    || x.isBlank()
                    || y == null
                    || y.isBlank()) {
                throw new IllegalArgumentException("Graph public JWK is invalid");
            }
        }
    }
}
