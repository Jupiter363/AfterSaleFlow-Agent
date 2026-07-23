package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.hearing.domain.HearingWriterMode;
import java.util.Locale;
import java.util.Objects;

/** Fail-closed scheduler authority for the legacy-to-Temporal Hearing transition. */
public record HearingSchedulerControl(
        SchedulerMode mode, HearingWriterMode writerMode, LegacyWorkState legacyWorkState) {

    public HearingSchedulerControl {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(writerMode, "writerMode must not be null");
        Objects.requireNonNull(legacyWorkState, "legacyWorkState must not be null");
        if (legacyWorkState == LegacyWorkState.ACTIVE
                && (mode != SchedulerMode.EXECUTOR || writerMode != HearingWriterMode.LEGACY)) {
            throw new IllegalArgumentException(
                    "active legacy Hearing work requires the legacy scheduler executor");
        }
        if (writerMode == HearingWriterMode.SHADOW) {
            throw new IllegalArgumentException(
                    "synthetic SHADOW is not a formal scheduler writer mode");
        }
        if (writerMode == HearingWriterMode.TEMPORAL && mode != SchedulerMode.DETECTOR) {
            throw new IllegalArgumentException(
                    "a TEMPORAL Hearing writer requires the legacy scheduler detector");
        }
        if (mode == SchedulerMode.DETECTOR && writerMode != HearingWriterMode.TEMPORAL) {
            throw new IllegalArgumentException(
                    "the detector is reserved for a drained future TEMPORAL writer");
        }
        if (mode == SchedulerMode.OFF && legacyWorkState != LegacyWorkState.DRAINED) {
            throw new IllegalArgumentException("OFF requires proof that legacy work is drained");
        }
    }

    public static HearingSchedulerControl configured(
            String mode, String writerMode, boolean legacyWorkDrained) {
        try {
            return new HearingSchedulerControl(
                    SchedulerMode.valueOf(normalize(mode)),
                    HearingWriterMode.valueOf(normalize(writerMode)),
                    legacyWorkDrained ? LegacyWorkState.DRAINED : LegacyWorkState.ACTIVE);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid Hearing scheduler authority", exception);
        }
    }

    public static HearingSchedulerControl legacyExecutor() {
        return new HearingSchedulerControl(
                SchedulerMode.EXECUTOR,
                HearingWriterMode.LEGACY,
                LegacyWorkState.ACTIVE);
    }

    public static HearingSchedulerControl futureTemporalDetector() {
        return new HearingSchedulerControl(
                SchedulerMode.DETECTOR,
                HearingWriterMode.TEMPORAL,
                LegacyWorkState.DRAINED);
    }

    public static HearingSchedulerControl drainedOff() {
        return new HearingSchedulerControl(
                SchedulerMode.OFF,
                HearingWriterMode.LEGACY,
                LegacyWorkState.DRAINED);
    }

    public Decision decision() {
        return switch (mode) {
            case EXECUTOR -> Decision.EXECUTE_LEGACY;
            case DETECTOR -> Decision.DETECT_ONLY;
            case OFF -> Decision.SKIP;
        };
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scheduler authority must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public enum SchedulerMode {
        EXECUTOR,
        DETECTOR,
        OFF
    }

    public enum LegacyWorkState {
        ACTIVE,
        DRAINED
    }

    public enum Decision {
        EXECUTE_LEGACY,
        DETECT_ONLY,
        SKIP
    }
}
