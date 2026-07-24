package com.example.dispute.executor.domain.ledger;

import java.time.Instant;
import java.util.Objects;

final class OutcomeLedgerValues {

    private OutcomeLedgerValues() {}

    static String identifier(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
        }
        return normalized;
    }

    static String sha256(String value, String field) {
        String normalized = identifier(value, field, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    static String boundedHash(String value, String field) {
        return identifier(value, field, 128);
    }

    static String immutableRef(String value, String field) {
        String normalized = identifier(value, field, 1024);
        if (!(normalized.startsWith("urn:")
                || normalized.startsWith("s3:")
                || normalized.startsWith("minio:"))) {
            throw new IllegalArgumentException(field + " must be an immutable urn, s3, or minio ref");
        }
        return normalized;
    }

    static long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    static int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static Instant instant(Instant value, String field) {
        return Objects.requireNonNull(value, field);
    }
}
