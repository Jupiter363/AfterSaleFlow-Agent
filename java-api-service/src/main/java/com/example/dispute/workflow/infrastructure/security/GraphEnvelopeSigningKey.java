package com.example.dispute.workflow.infrastructure.security;

/** KMS/HSM boundary that returns an IEEE P1363 {@code R || S} ES256 signature. */
public interface GraphEnvelopeSigningKey {

    String keyId();

    /** Signs the SHA-256 digest of {@code signingInput}; the result must contain exactly 64 bytes. */
    byte[] signSha256(byte[] signingInput);
}
