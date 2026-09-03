package com.example.dispute.workflow.shadow.intake.admission;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Verification-only allowlist for retained synthetic admission public keys. */
public final class IntakeSyntheticAdmissionTrustSet {

    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final int MAXIMUM_KEYS = 16;

    private final Map<String, ECPublicKey> keys;

    public IntakeSyntheticAdmissionTrustSet(Map<String, ECPublicKey> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        if (keys.isEmpty() || keys.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException("admission trust set must contain between 1 and 16 keys");
        }
        for (Map.Entry<String, ECPublicKey> entry : keys.entrySet()) {
            requireKeyId(entry.getKey());
            requireP256(Objects.requireNonNull(entry.getValue(), "public key must not be null"));
        }
        this.keys = Map.copyOf(keys);
    }

    public ECPublicKey resolve(String keyId) {
        requireKeyId(keyId);
        ECPublicKey key = keys.get(keyId);
        if (key == null) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_KEY_REJECTED", "admission signing key is not allowlisted");
        }
        return key;
    }

    public int size() {
        return keys.size();
    }

    static String requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_KEY_REJECTED", "admission signing key ID is invalid");
        }
        return keyId;
    }

    private static void requireP256(ECPublicKey publicKey) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = publicKey.getParams();
            if (actual == null
                    || !actual.getCurve().equals(expected.getCurve())
                    || !actual.getGenerator().equals(expected.getGenerator())
                    || !actual.getOrder().equals(expected.getOrder())
                    || actual.getCofactor() != expected.getCofactor()) {
                throw new IllegalArgumentException("admission verification keys must use P-256");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 is unavailable", exception);
        }
    }
}
