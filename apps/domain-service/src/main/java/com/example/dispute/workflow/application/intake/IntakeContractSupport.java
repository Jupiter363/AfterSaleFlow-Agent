package com.example.dispute.workflow.application.intake;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class IntakeContractSupport {

    static final int SNAPSHOT_MAX_BYTES = 256 * 1024;
    static final int EVENT_MAX_BYTES = 32 * 1024;
    static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern THREAD_ID = Pattern.compile("grt\\.v1\\.[0-9a-f]{32}");
    static final Pattern TRACEPARENT =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private IntakeContractSupport() {}

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    static String threadId(String value) {
        if (value == null || !THREAD_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("threadId must be an opaque UUIDv7 wire id");
        }
        return value;
    }

    static String traceparent(String value) {
        if (value == null || !TRACEPARENT.matcher(value).matches()) {
            throw new IllegalArgumentException("traceparent is invalid");
        }
        return value;
    }

    static String immutableUri(String value) {
        Objects.requireNonNull(value, "uri must not be null");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("uri must be an immutable payload URI", failure);
        }
        if (!("s3".equals(uri.getScheme())
                || "minio".equals(uri.getScheme())
                || "urn".equals(uri.getScheme()))
                || value.length() > 1024) {
            throw new IllegalArgumentException("uri must use s3, minio, or urn");
        }
        return value;
    }

    static String boundedText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is blank or exceeds its bound");
        }
        return value;
    }

    static long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    static long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static List<String> identifiers(
            List<String> values, int minimum, int maximum, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field));
        if (copy.size() < minimum || copy.size() > maximum) {
            throw new IllegalArgumentException(field + " has an invalid item count");
        }
        copy.forEach(value -> identifier(value, field));
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain unique values");
        }
        return copy;
    }

    static JsonNode immutableJson(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value.deepCopy();
    }
}
