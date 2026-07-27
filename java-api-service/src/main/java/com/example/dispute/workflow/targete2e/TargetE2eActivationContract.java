package com.example.dispute.workflow.targete2e;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Objects;
import java.util.regex.Pattern;

final class TargetE2eActivationContract {

  static final String CONTRACT_VERSION = "target-e2e-activation.v1";
  static final String LANE = "TARGET_E2E_CANDIDATE";
  static final String JWS_TYPE = "target-e2e-activation+jwt";

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}");
  private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern CASE_ID_PREFIX = Pattern.compile("[A-Z][A-Z0-9_]{2,31}");
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{31,127}");
  private static final Pattern ACTIVATION_ID = Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");
  private static final Pattern CANDIDATE_SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

  private TargetE2eActivationContract() {}

  static String identifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
    return value;
  }

  static String keyId(String value) {
    if (value == null || !KEY_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("activation key ID is invalid");
    }
    return value;
  }

  static String caseId(String value) {
    if (value == null || !CASE_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("case ID is invalid");
    }
    return value;
  }

  static String caseIdPrefix(String value) {
    if (value == null || !CASE_ID_PREFIX.matcher(value).matches()) {
      throw new IllegalArgumentException("case ID prefix is invalid");
    }
    return value;
  }

  static String nonce(String value) {
    if (value == null || !NONCE.matcher(value).matches()) {
      throw new IllegalArgumentException("activation nonce is invalid");
    }
    return value;
  }

  static String candidateSha(String value) {
    if (value == null || !CANDIDATE_SHA.matcher(value).matches()) {
      throw new IllegalArgumentException("candidate SHA is invalid");
    }
    return value;
  }

  static String activationId(String value) {
    if (value == null || !ACTIVATION_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("activation ID is invalid");
    }
    return value;
  }

  static String sha256(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
    return value;
  }

  static String imageDigest(String value) {
    if (value == null || !IMAGE_DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException("Graph image digest must be a sha256 digest");
    }
    return value;
  }

  static String appProfile(String value) {
    if (!"target-e2e".equals(value)) {
      throw new IllegalArgumentException(
          "target E2E activation requires the dedicated target-e2e profile");
    }
    return value;
  }

  static long generation(long value) {
    if (value < 1 || value > MAXIMUM_SAFE_JSON_INTEGER) {
      throw new IllegalArgumentException("activation generation must be a positive safe integer");
    }
    return value;
  }

  static boolean same(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  static ECPublicKey requireP256(ECPublicKey key) {
    Objects.requireNonNull(key, "key");
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
        throw new IllegalArgumentException("activation public keys must use P-256");
      }
      return key;
    } catch (GeneralSecurityException failure) {
      throw new IllegalArgumentException("activation public key cannot be validated", failure);
    }
  }
}
