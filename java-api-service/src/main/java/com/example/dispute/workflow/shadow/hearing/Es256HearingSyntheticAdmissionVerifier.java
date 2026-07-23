package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionClaims.ScopeKind;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Strict ES256 compact-JWS verifier for engineering-only Hearing fixture admissions. */
public final class Es256HearingSyntheticAdmissionVerifier {

    public static final String TOKEN_TYPE = "hearing-synthetic-admission+jwt";
    private static final int MAXIMUM_TOKEN_CHARACTERS = 8_192;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "schema_version", "iss", "aud", "sub", "jti", "iat", "nbf", "exp",
            "room_type", "writer_mode", "fixture_id", "tenant_surrogate", "case_id",
            "room_epoch", "epoch_admission", "scope_kind", "scope_id", "scope_hash",
            "selection_hash", "expected_trace_hash", "labels");

    private final HearingSyntheticAdmissionTrustSet trustSet;
    private final Clock clock;

    public Es256HearingSyntheticAdmissionVerifier(
            HearingSyntheticAdmissionTrustSet trustSet, Clock clock) {
        this.trustSet = Objects.requireNonNull(trustSet, "trustSet must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public VerifiedToken verify(String compactJws) {
        if (compactJws == null
                || compactJws.isBlank()
                || compactJws.length() > MAXIMUM_TOKEN_CHARACTERS) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "compact JWS is missing or exceeds its bound");
        }
        String[] segments = compactJws.split("\\.", -1);
        if (segments.length != 3) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "compact JWS must contain three segments");
        }

        JsonNode header = decodeObject(segments[0], "protected header");
        requireExactFields(header, HEADER_FIELDS, "protected header");
        requireText(header, "alg", "ES256");
        requireText(header, "typ", TOKEN_TYPE);
        String keyId = text(header, "kid");
        byte[] signature = decodeCanonicalSegment(segments[2], "signature");
        if (signature.length != 64) {
            throw rejected("ADMISSION_SIGNATURE_INVALID", "ES256 signature must be 64-byte R || S");
        }
        verifySignature(keyId, segments[0] + "." + segments[1], signature);

        JsonNode payload = decodeObject(segments[1], "claims");
        requireExactFields(payload, CLAIM_FIELDS, "claims");
        HearingSyntheticAdmissionClaims claims = parseClaims(payload);
        if (!claims.isValidAt(clock.instant())) {
            throw rejected("ADMISSION_TIME_INVALID", "admission token is not currently valid");
        }
        return new VerifiedToken(
                keyId,
                sha256Hex(compactJws.getBytes(StandardCharsets.US_ASCII)),
                ContractJson.sha256Hex(payload),
                claims);
    }

    private void verifySignature(String keyId, String input, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initVerify(trustSet.resolve(keyId));
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signatureBytes)) {
                throw rejected("ADMISSION_SIGNATURE_INVALID", "ES256 signature is invalid");
            }
        } catch (GeneralSecurityException exception) {
            throw new HearingSyntheticAdmissionException(
                    "ADMISSION_SIGNATURE_INVALID", "ES256 verification failed", exception);
        }
    }

    private static HearingSyntheticAdmissionClaims parseClaims(JsonNode payload) {
        JsonNode labelsNode = object(payload, "labels");
        Map<String, String> labels = new TreeMap<>();
        labelsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw rejected("ADMISSION_CLAIMS_INVALID", "labels must contain strings");
            }
            labels.put(entry.getKey(), entry.getValue().textValue());
        });
        try {
            return new HearingSyntheticAdmissionClaims(
                    text(payload, "schema_version"),
                    text(payload, "iss"),
                    text(payload, "aud"),
                    text(payload, "sub"),
                    text(payload, "jti"),
                    integer(payload, "iat"),
                    integer(payload, "nbf"),
                    integer(payload, "exp"),
                    text(payload, "room_type"),
                    text(payload, "writer_mode"),
                    text(payload, "fixture_id"),
                    text(payload, "tenant_surrogate"),
                    text(payload, "case_id"),
                    integer(payload, "room_epoch"),
                    text(payload, "epoch_admission"),
                    ScopeKind.valueOf(text(payload, "scope_kind")),
                    text(payload, "scope_id"),
                    text(payload, "scope_hash"),
                    text(payload, "selection_hash"),
                    text(payload, "expected_trace_hash"),
                    labels);
        } catch (IllegalArgumentException exception) {
            if (exception instanceof HearingSyntheticAdmissionException admissionException) {
                throw admissionException;
            }
            throw new HearingSyntheticAdmissionException(
                    "ADMISSION_CLAIMS_INVALID", "claims contain an invalid enum", exception);
        }
    }

    private static JsonNode decodeObject(String segment, String field) {
        byte[] decoded = decodeCanonicalSegment(segment, field);
        try {
            JsonNode value = JSON.readTree(decoded);
            if (value == null || !value.isObject()) {
                throw rejected("ADMISSION_EVIDENCE_INVALID", field + " must be an object");
            }
            return value;
        } catch (IOException exception) {
            throw new HearingSyntheticAdmissionException(
                    "ADMISSION_EVIDENCE_INVALID", field + " is not strict JSON", exception);
        }
    }

    private static byte[] decodeCanonicalSegment(String segment, String field) {
        try {
            byte[] decoded = DECODER.decode(segment);
            if (!ENCODER.encodeToString(decoded).equals(segment)) {
                throw rejected("ADMISSION_EVIDENCE_INVALID", field + " is not canonical base64url");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            if (exception instanceof HearingSyntheticAdmissionException admissionException) {
                throw admissionException;
            }
            throw new HearingSyntheticAdmissionException(
                    "ADMISSION_EVIDENCE_INVALID", field + " is not valid base64url", exception);
        }
    }

    private static void requireExactFields(JsonNode object, Set<String> expected, String field) {
        Set<String> actual = new java.util.HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", field + " fields are not the closed schema");
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be a string");
        }
        return value.textValue();
    }

    private static long integer(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be an integer");
        }
        return value.longValue();
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!expected.equals(text(parent, field))) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", field + " must be " + expected);
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static HearingSyntheticAdmissionException rejected(String code, String message) {
        return new HearingSyntheticAdmissionException(code, message);
    }

    public record VerifiedToken(
            String keyId,
            String envelopeHash,
            String claimsHash,
            HearingSyntheticAdmissionClaims claims) {}
}
