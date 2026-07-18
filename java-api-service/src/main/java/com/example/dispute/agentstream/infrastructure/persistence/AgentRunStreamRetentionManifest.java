package com.example.dispute.agentstream.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable evidence required before Phase 8 may remove a hot stream chunk. Phase 2 does not
 * delete stream rows; it only freezes the fail-closed retention contract.
 */
public record AgentRunStreamRetentionManifest(
        String runId,
        String attemptId,
        long terminalSequenceNo,
        String terminalPayloadHash,
        String agentExecutionManifestId,
        String agentExecutionManifestHash,
        Instant finalizedAt,
        Instant hotRetainUntil,
        boolean compactionVerified,
        boolean archiveVerified) {

    public AgentRunStreamRetentionManifest {
        required(runId, "runId");
        required(attemptId, "attemptId");
        if (terminalSequenceNo < 0) {
            throw new IllegalArgumentException("terminalSequenceNo must not be negative");
        }
        sha256(terminalPayloadHash, "terminalPayloadHash");
        required(agentExecutionManifestId, "agentExecutionManifestId");
        sha256(agentExecutionManifestHash, "agentExecutionManifestHash");
        Objects.requireNonNull(finalizedAt, "finalizedAt must not be null");
        Objects.requireNonNull(hotRetainUntil, "hotRetainUntil must not be null");
        if (hotRetainUntil.isBefore(finalizedAt.plusSeconds(24 * 60 * 60))) {
            throw new IllegalArgumentException("hot stream retention must be at least 24 hours");
        }
    }

    public boolean hotChunkDeletionAllowed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(hotRetainUntil) && (compactionVerified || archiveVerified);
    }

    public AgentRunStreamRetentionManifest withArchiveVerified() {
        return new AgentRunStreamRetentionManifest(
                runId,
                attemptId,
                terminalSequenceNo,
                terminalPayloadHash,
                agentExecutionManifestId,
                agentExecutionManifestHash,
                finalizedAt,
                hotRetainUntil,
                compactionVerified,
                true);
    }

    public AgentRunStreamRetentionManifest withCompactionVerified() {
        return new AgentRunStreamRetentionManifest(
                runId,
                attemptId,
                terminalSequenceNo,
                terminalPayloadHash,
                agentExecutionManifestId,
                agentExecutionManifestHash,
                finalizedAt,
                hotRetainUntil,
                true,
                archiveVerified);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }
}
