package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import java.util.Optional;

/** Read-only proof that one V4 source FINAL is also the contiguous delivery terminal. */
public interface TargetE2eV4FinalAuthoritySource {

    Optional<FinalAuthority> load(String runId, String attemptId, long sequenceNo);

    record FinalAuthority(
            AgentStreamEventV4 event,
            String sourceEventId,
            String canonicalEventSha256,
            long durableHighWatermark,
            String actorId) {

        public FinalAuthority {
            if (event == null) {
                throw new IllegalArgumentException("event is required");
            }
            if (sourceEventId == null || sourceEventId.isBlank()) {
                throw new IllegalArgumentException("sourceEventId is required");
            }
            if (canonicalEventSha256 == null
                    || !canonicalEventSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "canonicalEventSha256 must be a lowercase SHA-256");
            }
            if (durableHighWatermark < 0) {
                throw new IllegalArgumentException("durableHighWatermark must not be negative");
            }
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("actorId is required");
            }
        }
    }
}
