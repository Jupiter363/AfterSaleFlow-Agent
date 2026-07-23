package com.example.dispute.workflow.shadow.hearing;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Map;
import java.util.Objects;

/** Verification-only allowlist. Private signing material is deliberately outside this runtime. */
public final class HearingSyntheticAdmissionTrustSet {

    private final Map<String, ECPublicKey> keys;

    public HearingSyntheticAdmissionTrustSet(Map<String, ECPublicKey> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        if (keys.isEmpty() || keys.size() > 16) {
            throw new IllegalArgumentException("Hearing trust set must contain between 1 and 16 keys");
        }
        keys.forEach((keyId, key) -> {
            requireKeyId(keyId);
            requireP256(Objects.requireNonNull(key, "public key must not be null"));
        });
        this.keys = Map.copyOf(keys);
    }

    public ECPublicKey resolve(String keyId) {
        requireKeyId(keyId);
        ECPublicKey key = keys.get(keyId);
        if (key == null) {
            throw rejected("ADMISSION_KEY_REJECTED", "signing key is not allowlisted");
        }
        return key;
    }

    private static void requireKeyId(String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected("ADMISSION_KEY_REJECTED", "signing key ID is invalid");
        }
    }

    private static void requireP256(ECPublicKey key) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = key.getParams();
            if (actual == null
                    || !actual.getCurve().equals(expected.getCurve())
                    || !actual.getGenerator().equals(expected.getGenerator())
                    || !actual.getOrder().equals(expected.getOrder())
                    || actual.getCofactor() != expected.getCofactor()) {
                throw new IllegalArgumentException("Hearing admission keys must use P-256");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 is unavailable", exception);
        }
    }

    private static HearingSyntheticAdmissionException rejected(String code, String message) {
        return new HearingSyntheticAdmissionException(code, message);
    }
}
