package com.example.dispute.workflow.targete2e;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable key-ID allowlist containing public P-256 material only. */
public final class TargetE2eActivationPublicKeySet {

  private static final int MAXIMUM_KEYS = 16;

  private final Map<String, ECPublicKey> keys;

  private TargetE2eActivationPublicKeySet(Map<String, ECPublicKey> keys) {
    this.keys = Map.copyOf(keys);
  }

  public static TargetE2eActivationPublicKeySet allowlisted(
      Map<String, ? extends ECPublicKey> source) {
    Objects.requireNonNull(source, "source");
    if (source.size() > MAXIMUM_KEYS) {
      throw new IllegalArgumentException("activation public key count exceeds 16");
    }
    Map<String, ECPublicKey> snapshot = new LinkedHashMap<>();
    source.forEach(
        (keyId, publicKey) ->
            snapshot.put(TargetE2eActivationContract.keyId(keyId), copyPublicKey(publicKey)));
    return new TargetE2eActivationPublicKeySet(snapshot);
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
      throw new IllegalArgumentException("activation public key is invalid", failure);
    } finally {
      java.util.Arrays.fill(encoded, (byte) 0);
    }
  }
}
