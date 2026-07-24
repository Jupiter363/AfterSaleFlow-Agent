package com.example.dispute.review.domain;

import java.time.OffsetDateTime;

/** Immutable identity and integrity boundary for one frozen review packet. */
public record FrozenReviewPacketIdentity(
        String packetId,
        String caseId,
        String planId,
        int packetVersion,
        String contentHash,
        String actionHash,
        OffsetDateTime frozenAt,
        OffsetDateTime expiresAt) {

    public FrozenReviewPacketIdentity {
        requireText(packetId, "packetId");
        requireText(caseId, "caseId");
        requireText(planId, "planId");
        if (packetVersion < 1) {
            throw new IllegalArgumentException("packetVersion must be positive");
        }
        requireHash(contentHash, "contentHash");
        requireText(actionHash, "actionHash");
        if (frozenAt == null || expiresAt == null || !expiresAt.isAfter(frozenAt)) {
            throw new IllegalArgumentException("packet freeze interval is invalid");
        }
    }

    private static void requireHash(String value, String name) {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
