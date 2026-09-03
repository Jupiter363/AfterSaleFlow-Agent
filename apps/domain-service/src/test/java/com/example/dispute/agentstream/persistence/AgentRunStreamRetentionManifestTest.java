package com.example.dispute.agentstream.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifest.ArchiveReceipt;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunStreamRetentionManifestTest {

    private static final Instant FINALIZED_AT = Instant.parse("2026-07-19T00:00:00Z");
    private static final String TERMINAL_HASH = "a".repeat(64);
    private static final String EXECUTION_HASH = "b".repeat(64);

    @Test
    void releaseEligibilityRequiresTheFullVerifiedReceiptAfterTwentyFourHours() {
        AgentRunStreamRetentionManifest baseline = baseline(false, false);
        AgentRunStreamRetentionManifest verified = baseline.withArchiveReceipt(receipt(
                TERMINAL_HASH,
                "c".repeat(64),
                "c".repeat(64),
                true,
                true,
                false,
                false));

        assertThat(baseline.releaseCleanupEligible(FINALIZED_AT.plusSeconds(48 * 60 * 60)))
                .isFalse();
        assertThat(verified.releaseCleanupEligible(FINALIZED_AT.plusSeconds(24 * 60 * 60 - 1)))
                .isFalse();
        assertThat(verified.releaseCleanupEligible(FINALIZED_AT.plusSeconds(24 * 60 * 60)))
                .isTrue();
        assertThat(verified.archiveVerified()).isTrue();
        assertThat(verified.compactionVerified()).isFalse();
        assertThat(verified.archiveReceipt().objectVersion()).isEqualTo("version-17");
        assertThat(verified.archiveReceipt().objectCreationReceiptId())
                .isEqualTo("OBJECT_CREATION_1");
    }

    @Test
    void booleanVerificationCannotBypassDurableReceiptEvidence() {
        assertThatThrownBy(() -> baseline(true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable immutable archive receipt");
        assertThatThrownBy(() -> baseline(false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable immutable archive receipt");
    }

    @Test
    void readbackMismatchOrMissingLongAuditBindingsFailClosed() {
        AgentRunStreamRetentionManifest baseline = baseline(false, false);

        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        TERMINAL_HASH,
                        "c".repeat(64),
                        "d".repeat(64),
                        true,
                        true,
                        false,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        TERMINAL_HASH,
                        "c".repeat(64),
                        "c".repeat(64),
                        false,
                        true,
                        false,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        TERMINAL_HASH,
                        "c".repeat(64),
                        "c".repeat(64),
                        true,
                        false,
                        false,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void businessAuthorityOrReleaseCompletionCannotComeFromArchiveEvidence() {
        AgentRunStreamRetentionManifest baseline = baseline(false, false);

        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        TERMINAL_HASH,
                        "c".repeat(64),
                        "c".repeat(64),
                        true,
                        true,
                        true,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        TERMINAL_HASH,
                        "c".repeat(64),
                        "c".repeat(64),
                        true,
                        true,
                        false,
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void receiptMustBindTheExactFormalTerminalManifest() {
        AgentRunStreamRetentionManifest baseline = baseline(false, false);

        assertThatThrownBy(() -> baseline.withArchiveReceipt(receipt(
                        "f".repeat(64),
                        "c".repeat(64),
                        "c".repeat(64),
                        true,
                        true,
                        false,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not bind");
    }

    private static AgentRunStreamRetentionManifest baseline(
            boolean compactionVerified, boolean archiveVerified) {
        return new AgentRunStreamRetentionManifest(
                "RUN_1",
                "ATTEMPT_1",
                7,
                TERMINAL_HASH,
                "MANIFEST_1",
                EXECUTION_HASH,
                FINALIZED_AT,
                FINALIZED_AT.plusSeconds(24 * 60 * 60),
                compactionVerified,
                archiveVerified);
    }

    private static ArchiveReceipt receipt(
            String terminalHash,
            String objectHash,
            String readbackHash,
            boolean terminalEventRetained,
            boolean immutableManifest,
            boolean formalBusinessAuthority,
            boolean releaseEvidenceComplete) {
        return new ArchiveReceipt(
                "RECEIPT_1",
                "d".repeat(64),
                "ARCHIVE_MANIFEST_1",
                "e".repeat(64),
                "agent_run_stream_event_delivery_2026_07_19",
                "RUN_1",
                "ATTEMPT_1",
                0,
                7,
                8,
                "f".repeat(64),
                "version-17",
                objectHash,
                readbackHash,
                "OBJECT_CREATION_1",
                "1".repeat(64),
                7,
                FINALIZED_AT,
                FINALIZED_AT.plusSeconds(24 * 60 * 60),
                terminalHash,
                "MANIFEST_1",
                EXECUTION_HASH,
                terminalEventRetained,
                immutableManifest,
                formalBusinessAuthority,
                releaseEvidenceComplete);
    }
}
