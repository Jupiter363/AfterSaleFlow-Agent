package com.example.dispute.workflow.targete2e;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Independent public-only P-256 trust set for runtime isolation attestations. */
public final class TargetE2eIsolationAttestationPublicKeySet {

  private static final int MAXIMUM_KEYS = 16;

  private final Map<String, ECPublicKey> keys;

  private TargetE2eIsolationAttestationPublicKeySet(Map<String, ECPublicKey> keys) {
    this.keys = Map.copyOf(keys);
  }

  public static TargetE2eIsolationAttestationPublicKeySet allowlisted(
      Map<String, ? extends ECPublicKey> source) {
    Objects.requireNonNull(source, "source");
    if (source.isEmpty() || source.size() > MAXIMUM_KEYS) {
      throw new IllegalArgumentException("isolation attestation key count must be inside 1..16");
    }
    Map<String, ECPublicKey> snapshot = new LinkedHashMap<>();
    source.forEach(
        (keyId, publicKey) ->
            snapshot.put(TargetE2eActivationContract.keyId(keyId), copyPublicKey(publicKey)));
    return new TargetE2eIsolationAttestationPublicKeySet(snapshot);
  }

  Optional<ECPublicKey> resolve(String keyId) {
    return Optional.ofNullable(keys.get(keyId));
  }

  private static ECPublicKey copyPublicKey(ECPublicKey source) {
    Objects.requireNonNull(source, "publicKey");
    byte[] encoded = Objects.requireNonNull(source.getEncoded(), "encoded public key").clone();
    try {
      ECPublicKey copy =
          (ECPublicKey)
              KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
      return TargetE2eActivationContract.requireP256(copy);
    } catch (ClassCastException | GeneralSecurityException failure) {
      throw new IllegalArgumentException("isolation attestation public key is invalid", failure);
    } finally {
      java.util.Arrays.fill(encoded, (byte) 0);
    }
  }
}
