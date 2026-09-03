package com.example.dispute.evidence.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, parser-produced content authority for one supported current Evidence attachment. */
public record EvidenceContentAuthorityV1(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("case_id") String caseId,
        @JsonProperty("evidence_id") String evidenceId,
        @JsonProperty("file_sha256") String fileSha256,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("parser_version") String parserVersion,
        @JsonProperty("parsed_content_sha256") String parsedContentSha256,
        @JsonProperty("parsed_text") String parsedText,
        @JsonProperty("parsed_byte_length") long parsedByteLength,
        @JsonProperty("completed_at") String completedAt,
        String status) {

    public static final String SCHEMA_VERSION = "evidence_content_authority.v1";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final int MAX_PARSED_TEXT_BYTES = 1_000_000;

    public EvidenceContentAuthorityV1 {
        requireExact(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        requireIdentifier(caseId, "caseId");
        requireIdentifier(evidenceId, "evidenceId");
        requireHash(fileSha256, "fileSha256");
        if (!isSupportedTextContentType(contentType)) {
            throw new IllegalArgumentException("contentType must be supported text evidence");
        }
        requireIdentifier(parserVersion, "parserVersion");
        requireHash(parsedContentSha256, "parsedContentSha256");
        requireExact(status, STATUS_SUCCEEDED, "status");
        if (completedAt == null || completedAt.isBlank()) {
            throw new IllegalArgumentException("completedAt must not be blank");
        }
        String canonical = canonicalParsedText(parsedText);
        if (!canonical.equals(parsedText)) {
            throw new IllegalArgumentException("parsedText must be canonical UTF-8 text");
        }
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != parsedByteLength) {
            throw new IllegalArgumentException("parsedByteLength does not match parsedText");
        }
        if (bytes.length < 1 || bytes.length > MAX_PARSED_TEXT_BYTES) {
            throw new IllegalArgumentException("parsedText byte length is out of bounds");
        }
        if (!sha256Hex(bytes).equals(parsedContentSha256)) {
            throw new IllegalArgumentException("parsedContentSha256 does not match canonical parsedText");
        }
    }

    public static EvidenceContentAuthorityV1 completed(
            String caseId,
            String evidenceId,
            String fileSha256,
            String contentType,
            String parserVersion,
            String parsedText,
            OffsetDateTime completedAt) {
        String canonical = canonicalParsedText(parsedText);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        OffsetDateTime exactTime =
                Objects.requireNonNull(completedAt, "completedAt")
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.MICROS);
        return new EvidenceContentAuthorityV1(
                SCHEMA_VERSION,
                caseId,
                evidenceId,
                fileSha256,
                contentType,
                parserVersion,
                sha256Hex(bytes),
                canonical,
                bytes.length,
                exactTime.toString(),
                STATUS_SUCCEEDED);
    }

    /** Canonical source bytes are NFC text with all line endings represented as LF. */
    public static String canonicalParsedText(String value) {
        if (value == null) {
            throw new IllegalArgumentException("parsedText must not be null");
        }
        String canonical =
                Normalizer.normalize(
                        value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("parsedText must not be blank");
        }
        return canonical;
    }

    public static boolean isSupportedTextContentType(String contentType) {
        return "text/plain".equals(contentType) || "text/markdown".equals(contentType);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireExact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
