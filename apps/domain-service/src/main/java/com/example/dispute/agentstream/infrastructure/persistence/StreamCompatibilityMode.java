package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityReport;
import java.util.Objects;

/**
 * Engineering-only stream storage modes. Runtime construction defaults to {@link #OLD_COMPATIBLE};
 * production apply and switching remain separate, externally authorized release actions.
 */
public enum StreamCompatibilityMode {
    OLD_COMPATIBLE(Reader.OLD_ONLY, Writer.OLD_ONLY),
    DUAL_WRITE_OLD_READ(Reader.OLD_ONLY, Writer.DUAL_WRITE),
    TARGET_READ_DUAL_WRITE(Reader.TARGET_ONLY, Writer.DUAL_WRITE),
    TARGET_ONLY(Reader.TARGET_ONLY, Writer.TARGET_ONLY),
    TARGET_AWARE_ROLLBACK(Reader.COMPATIBLE_UNION, Writer.DUAL_WRITE);

    public enum Reader {
        OLD_ONLY,
        TARGET_ONLY,
        COMPATIBLE_UNION
    }

    public enum Writer {
        OLD_ONLY,
        DUAL_WRITE,
        TARGET_ONLY
    }

    private final Reader reader;
    private final Writer writer;

    StreamCompatibilityMode(Reader reader, Writer writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public Reader reader() {
        return reader;
    }

    public Writer writer() {
        return writer;
    }

    public boolean writesOldStore() {
        return writer != Writer.TARGET_ONLY;
    }

    public boolean writesTargetStore() {
        return writer != Writer.OLD_ONLY;
    }

    public boolean readsTargetStore() {
        return reader != Reader.OLD_ONLY;
    }

    public static StreamCompatibilityMode defaultMode() {
        return OLD_COMPATIBLE;
    }

    /**
     * Validates a proposed engineering transition. Once a target-only write exists, an old-only
     * reader would hide a committed delivery event and is therefore never a legal rollback.
     */
    public static StreamCompatibilityMode requireTransition(
            StreamCompatibilityMode current,
            StreamCompatibilityMode proposed,
            CompatibilityReport parity,
            RollbackCoverage rollbackCoverage) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(proposed, "proposed");
        boolean targetOnlyWriteObserved = current.writer == Writer.TARGET_ONLY
                || (rollbackCoverage != null && rollbackCoverage.targetOnlyWriteObserved());
        if (targetOnlyWriteObserved && proposed.reader == Reader.OLD_ONLY) {
            throw new IllegalStateException(
                    "old-only rollback is forbidden after a target-only stream write");
        }
        if (proposed.reader == Reader.COMPATIBLE_UNION) {
            Objects.requireNonNull(rollbackCoverage, "rollbackCoverage")
                    .requireCompatibleUnion();
        } else if (proposed.reader == Reader.TARGET_ONLY) {
            Objects.requireNonNull(parity, "parity").requireCompatible();
        }
        return proposed;
    }

    /** Database-derived coverage for target-aware rollback; construction is package-restricted. */
    public static final class RollbackCoverage {
        private final long oldCount;
        private final long targetCount;
        private final boolean targetOnlyWriteObserved;
        private final boolean oldOverlapExact;
        private final boolean unionContiguous;
        private final boolean targetHighWatermarkCoversUnion;
        private final boolean compositeCursorStable;

        RollbackCoverage(
                long oldCount,
                long targetCount,
                boolean targetOnlyWriteObserved,
                boolean oldOverlapExact,
                boolean unionContiguous,
                boolean targetHighWatermarkCoversUnion,
                boolean compositeCursorStable) {
            if (oldCount < 0 || targetCount < 0) {
                throw new IllegalArgumentException("rollback coverage counts must not be negative");
            }
            this.oldCount = oldCount;
            this.targetCount = targetCount;
            this.targetOnlyWriteObserved = targetOnlyWriteObserved;
            this.oldOverlapExact = oldOverlapExact;
            this.unionContiguous = unionContiguous;
            this.targetHighWatermarkCoversUnion = targetHighWatermarkCoversUnion;
            this.compositeCursorStable = compositeCursorStable;
        }

        public long oldCount() {
            return oldCount;
        }

        public long targetCount() {
            return targetCount;
        }

        public boolean targetOnlyWriteObserved() {
            return targetOnlyWriteObserved;
        }

        public boolean compatibleUnion() {
            return targetCount >= oldCount
                    && oldOverlapExact
                    && unionContiguous
                    && targetHighWatermarkCoversUnion
                    && compositeCursorStable;
        }

        RollbackCoverage requireCompatibleUnion() {
            if (!compatibleUnion()) {
                throw new IllegalStateException(
                        "target-aware rollback cannot preserve every committed stream event");
            }
            return this;
        }
    }
}
