package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunStreamRetentionManifestTest {

    @Test
    void hotChunksRequireRetentionAndVerifiedCompactionOrArchive() {
        Instant finalizedAt = Instant.parse("2026-07-19T00:00:00Z");
        AgentRunStreamRetentionManifest unverified =
                new AgentRunStreamRetentionManifest(
                        "RUN_1",
                        "ATTEMPT_1",
                        7,
                        "a".repeat(64),
                        "MANIFEST_1",
                        "b".repeat(64),
                        finalizedAt,
                        finalizedAt.plusSeconds(24 * 60 * 60),
                        false,
                        false);

        assertThat(unverified.hotChunkDeletionAllowed(finalizedAt.plusSeconds(48 * 60 * 60)))
                .isFalse();
        assertThat(unverified.withArchiveVerified())
                .satisfies(
                        verified -> {
                            assertThat(
                                            verified.hotChunkDeletionAllowed(
                                                    finalizedAt.plusSeconds(23 * 60 * 60)))
                                    .isFalse();
                            assertThat(
                                            verified.hotChunkDeletionAllowed(
                                                    finalizedAt.plusSeconds(24 * 60 * 60)))
                                    .isTrue();
                        });
    }
}
