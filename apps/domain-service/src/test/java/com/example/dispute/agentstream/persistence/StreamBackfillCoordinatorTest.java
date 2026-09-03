package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityReport;
import com.example.dispute.agentstream.infrastructure.persistence.StreamBackfillCoordinator.BackfillCursor;
import com.example.dispute.agentstream.infrastructure.persistence.StreamBackfillCoordinator.CursorStatus;
import com.example.dispute.agentstream.infrastructure.persistence.StreamBackfillCoordinator.SourcePosition;
import com.example.dispute.agentstream.infrastructure.persistence.StreamCompatibilityMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StreamBackfillCoordinatorTest {

    @Test
    void oldCompatibleIsTheDefaultAndTargetReadersRequireExactParity() {
        assertThat(StreamCompatibilityMode.defaultMode())
                .isEqualTo(StreamCompatibilityMode.OLD_COMPATIBLE);

        CompatibilityReport mismatch = report(false);
        assertThatThrownBy(() -> StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.DUAL_WRITE_OLD_READ,
                        StreamCompatibilityMode.TARGET_READ_DUAL_WRITE,
                        mismatch,
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compatibility validation failed");

        assertThat(StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.DUAL_WRITE_OLD_READ,
                        StreamCompatibilityMode.TARGET_READ_DUAL_WRITE,
                        report(true),
                        null))
                .isEqualTo(StreamCompatibilityMode.TARGET_READ_DUAL_WRITE);
    }

    @Test
    void targetOnlyWriteCanNeverRollBackToAnOldOnlyReader() {
        assertThatThrownBy(() -> StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.TARGET_ONLY,
                        StreamCompatibilityMode.OLD_COMPATIBLE,
                        null,
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("old-only rollback is forbidden");

        assertThatThrownBy(() -> StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.TARGET_ONLY,
                        StreamCompatibilityMode.TARGET_AWARE_ROLLBACK,
                        report(true),
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rollbackCoverage");
    }

    @Test
    void cursorKeepsScanProgressSeparateFromDeliveryHighWatermark() {
        SourcePosition upper =
                new SourcePosition(Instant.parse("2026-07-25T12:00:00Z"), "EVENT_2");
        BackfillCursor cursor = new BackfillCursor(
                "BACKFILL_1",
                upper,
                new SourcePosition(Instant.parse("2026-07-25T11:59:59Z"), "EVENT_1"),
                100,
                CursorStatus.RUNNING,
                1,
                0);

        assertThat(cursor.upperBound()).isEqualTo(upper);
        assertThat(cursor.processedCount()).isEqualTo(1);
        assertThatThrownBy(() -> new BackfillCursor(
                        "BACKFILL_1",
                        upper,
                        new SourcePosition(
                                Instant.parse("2026-07-25T12:00:01Z"), "EVENT_3"),
                        100,
                        CursorStatus.RUNNING,
                        2,
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    private static CompatibilityReport report(boolean compatible) {
        return new CompatibilityReport(
                "agent-stream.v2",
                "RUN_1",
                "ATTEMPT_1",
                2,
                compatible ? 2 : 1,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible,
                compatible);
    }
}
