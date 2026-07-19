package com.example.dispute.workflow.infrastructure.security;

/** Resolves an active or retained Graph signing key by its bounded key ID. */
@FunctionalInterface
public interface GraphEnvelopeSigningKeyResolver {

    GraphEnvelopeSigningKey resolve(String keyId);
}
