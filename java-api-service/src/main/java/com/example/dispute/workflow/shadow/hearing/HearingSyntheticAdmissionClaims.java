package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Closed claims set for a Java-signed, comparison-only Hearing fixture. */
public record HearingSyntheticAdmissionClaims(
        String schemaVersion,
        String issuer,
        String audience,
        String subject,
        String jwtId,
        long issuedAtEpochSeconds,
        long notBeforeEpochSeconds,
        long expiresAtEpochSeconds,
        String roomType,
        String writerMode,
        String fixtureId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        String epochAdmission,
        ScopeKind scopeKind,
        String scopeId,
        String scopeHash,
        String selectionHash,
        String expectedTraceHash,
        Map<String, String> labels) {

    public static final String SCHEMA_VERSION = "hearing-synthetic-admission-claims.v1";
    public static final String ISSUER = "after-sale-flow.java-synthetic-driver";
    public static final String AUDIENCE = "after-sale-flow.java-hearing-admission";
    public static final String SUBJECT = "signed-synthetic-hearing-shadow";
    private static final Set<String> LABEL_KEYS = Set.of("suite", "scenario", "result_class");

    public HearingSyntheticAdmissionClaims {
        requireEqual(schemaVersion, SCHEMA_VERSION, "schema_version");
        requireEqual(issuer, ISSUER, "iss");
        requireEqual(audience, AUDIENCE, "aud");
        requireEqual(subject, SUBJECT, "sub");
        requireIdentifier(jwtId, "jti", 128);
        requireEqual(roomType, "HEARING", "room_type");
        requireEqual(writerMode, "SHADOW", "writer_mode");
        requireSyntheticIdentifier(fixtureId, "fixture_id");
        requireSyntheticIdentifier(tenantSurrogate, "tenant_surrogate");
        requireSyntheticIdentifier(caseId, "case_id");
        requireEqual(epochAdmission, "PINNED_NEW_EPOCH", "epoch_admission");
        Objects.requireNonNull(scopeKind, "scope_kind must not be null");
        requireIdentifier(scopeId, "scope_id", 128);
        requireHash(scopeHash, "scope_hash");
        requireHash(selectionHash, "selection_hash");
        requireHash(expectedTraceHash, "expected_trace_hash");
        if (roomEpoch < 1) {
            throw rejected("room_epoch must identify a pinned new epoch");
        }
        if (issuedAtEpochSeconds < 0
                || notBeforeEpochSeconds < issuedAtEpochSeconds
                || expiresAtEpochSeconds <= notBeforeEpochSeconds
                || expiresAtEpochSeconds - issuedAtEpochSeconds > 60) {
            throw rejected("iat/nbf/exp must describe a validity window of at most 60 seconds");
        }
        labels = boundedLabels(labels);
        String expectedScopeHash = calculateScopeHash(
                fixtureId, tenantSurrogate, caseId, roomEpoch, scopeKind, scopeId);
        if (!expectedScopeHash.equals(scopeHash)) {
            throw rejected("scope_hash does not bind the exact synthetic fixture scope");
        }
    }

    public boolean isValidAt(Instant instant) {
        long now = Objects.requireNonNull(instant, "instant must not be null").getEpochSecond();
        return issuedAtEpochSeconds <= now
                && notBeforeEpochSeconds <= now
                && expiresAtEpochSeconds > now;
    }

    public static String calculateScopeHash(
            String fixtureId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            ScopeKind scopeKind,
            String scopeId) {
        ObjectNode scope = JsonNodeFactory.instance.objectNode();
        scope.put("schema_version", "hearing-synthetic-scope.v1");
        scope.put("fixture_id", fixtureId);
        scope.put("tenant_surrogate", tenantSurrogate);
        scope.put("case_id", caseId);
        scope.put("room_epoch", roomEpoch);
        scope.put("scope_kind", Objects.requireNonNull(scopeKind, "scopeKind").name());
        scope.put("scope_id", scopeId);
        return ContractJson.sha256Hex(scope);
    }

    private static Map<String, String> boundedLabels(Map<String, String> source) {
        Objects.requireNonNull(source, "labels must not be null");
        if (source.size() > LABEL_KEYS.size() || !LABEL_KEYS.containsAll(source.keySet())) {
            throw rejected("labels must use only the bounded telemetry allowlist");
        }
        TreeMap<String, String> copy = new TreeMap<>();
        source.forEach((key, value) -> {
            requireIdentifier(value, "labels." + key, 64);
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    private static void requireSyntheticIdentifier(String value, String field) {
        if (value == null || !value.matches("synthetic-[A-Za-z0-9][A-Za-z0-9._:-]{0,117}")) {
            throw rejected(field + " must use the synthetic namespace");
        }
    }

    private static void requireIdentifier(String value, String field, int max) {
        if (value == null
                || value.length() > max
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw rejected(field + " must be a bounded identifier");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw rejected(field + " must be lowercase SHA-256");
        }
    }

    private static void requireEqual(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw rejected(field + " must be " + expected);
        }
    }

    private static HearingSyntheticAdmissionException rejected(String message) {
        return new HearingSyntheticAdmissionException("ADMISSION_CLAIMS_INVALID", message);
    }

    public enum ScopeKind {
        ACTOR,
        SHARED
    }
}
