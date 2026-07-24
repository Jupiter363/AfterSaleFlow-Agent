package com.example.dispute.workflow.activity.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed engineering-only input. It cannot identify a domain case or party. */
public record SyntheticNoopExecutionCommand(
        String schemaVersion,
        String marker,
        String runtimeMode,
        String trafficSource,
        String fixtureId,
        String workflowId,
        String operationId,
        String packetRef,
        String packetHash,
        String requestHash,
        long epoch,
        long revision,
        long fence,
        boolean containsRealCaseOrPartyData,
        Instant issuedAt,
        String signer,
        String signatureAlgorithm,
        String signingKeyId,
        String signature) {

    public static final String SCHEMA_VERSION = "outcome-synthetic-noop-command.v1";
    public static final String MARKER = "JAVA_SIGNED_SYNTHETIC_NOOP_V1";
    public static final String RUNTIME_MODE = "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW";
    public static final String TRAFFIC_SOURCE = "SIGNED_SYNTHETIC";
    public static final String SIGNER = "JAVA_CONTROL_PLANE";
    public static final String SIGNATURE_ALGORITHM = "ES256";

    private static final Pattern FIXTURE_ID =
            Pattern.compile("OUTCOME_SYNTHETIC_[A-Z0-9._:-]{1,110}");
    private static final Pattern WORKFLOW_ID =
            Pattern.compile("outcome-synthetic/[A-Za-z0-9._:-]+(?:/[A-Za-z0-9._:-]+)*");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_-]{86}");

    public SyntheticNoopExecutionCommand {
        requireExact(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        requireExact(marker, MARKER, "marker");
        requireExact(runtimeMode, RUNTIME_MODE, "runtimeMode");
        requireExact(trafficSource, TRAFFIC_SOURCE, "trafficSource");
        requirePattern(fixtureId, FIXTURE_ID, "fixtureId");
        requirePattern(workflowId, WORKFLOW_ID, "workflowId");
        requirePattern(operationId, IDENTIFIER, "operationId");
        requireSyntheticRef(packetRef, "packetRef");
        requirePattern(packetHash, HASH, "packetHash");
        requirePattern(requestHash, HASH, "requestHash");
        if (epoch < 1) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (fence < 1) {
            throw new IllegalArgumentException("fence must be positive");
        }
        if (containsRealCaseOrPartyData) {
            throw new IllegalArgumentException("real case or party data is forbidden");
        }
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        requireExact(signer, SIGNER, "signer");
        requireExact(signatureAlgorithm, SIGNATURE_ALGORITHM, "signatureAlgorithm");
        requirePattern(signingKeyId, IDENTIFIER, "signingKeyId");
        if (!signingKeyId.startsWith("outcome-synthetic-")) {
            throw new IllegalArgumentException("signingKeyId must be synthetic-only");
        }
        requirePattern(signature, SIGNATURE, "signature");
    }

    private static void requireExact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    private static void requirePattern(String value, Pattern pattern, String field) {
        if (value == null || value.length() > 128 || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireSyntheticRef(String value, String field) {
        if (value == null
                || value.length() > 256
                || !value.startsWith("synthetic/")
                || value.contains("://")
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
            throw new IllegalArgumentException(field + " must be an opaque synthetic reference");
        }
    }
}
