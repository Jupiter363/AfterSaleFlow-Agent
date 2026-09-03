package com.example.dispute.workflow.shadow.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.SinkDisposition;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.Classification;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.Dimension;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ObservedValue;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ParityComparison;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.StopCondition;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.Verdict;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService.AdmissionReceipt;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionClaims.ScopeKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HearingShadowParityServiceTest {

    private static final String LEGACY_TRACE = "a".repeat(64);
    private static final String SHADOW_TRACE = "b".repeat(64);

    @Test
    void recordsEveryHashBoundDimensionIdempotently() {
        AtomicReference<ParityComparison> row = new AtomicReference<>();
        HearingSyntheticComparisonService service = new HearingSyntheticComparisonService(
                new HearingShadowParityService(),
                candidate -> {
                    row.compareAndSet(null, candidate);
                    return row.get();
                },
                HearingReliabilityObservationSink.noop());

        ParityComparison first = service.compareAndRecord(
                admission(), snapshot(LEGACY_TRACE, Map.of(), Set.of()),
                snapshot(SHADOW_TRACE, Map.of(), Set.of()));
        ParityComparison replay = service.compareAndRecord(
                admission(), snapshot(LEGACY_TRACE, Map.of(), Set.of()),
                snapshot(SHADOW_TRACE, Map.of(), Set.of()));

        assertThat(first).isEqualTo(replay);
        assertThat(first.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(first.dimensions().keySet()).containsExactlyInAnyOrder(Dimension.values());
        assertThat(first.comparisonKeyHash()).hasSize(64);
        assertThat(first.comparisonHash()).hasSize(64);
    }

    @Test
    void differingDimensionsAndHardStopsAreBoundedEnums() {
        HearingShadowParityService service = new HearingShadowParityService();
        ParitySnapshot legacy = snapshot(LEGACY_TRACE, Map.of(), Set.of());
        ParitySnapshot different = snapshot(
                SHADOW_TRACE,
                Map.of(Dimension.CLOSED_PROJECTION, value(Classification.INVALID, 'c')),
                Set.of());
        ParitySnapshot stopped = snapshot(
                SHADOW_TRACE,
                Map.of(),
                Set.of(StopCondition.STALE_FENCE_SUCCESS));

        assertThat(service.compare(admission(), legacy, different).verdict())
                .isEqualTo(Verdict.DIFFERENT);
        assertThat(service.compare(admission(), legacy, different).differingDimensions())
                .containsExactly(Dimension.CLOSED_PROJECTION);
        assertThat(service.compare(admission(), legacy, stopped).verdict())
                .isEqualTo(Verdict.HARD_FAILURE);
    }

    @Test
    void signedTraceAndLedgerKeyConflictsFailClosed() {
        HearingShadowParityService parity = new HearingShadowParityService();
        assertThatThrownBy(() -> parity.compare(
                        admission(),
                        snapshot(LEGACY_TRACE, Map.of(), Set.of()),
                        snapshot("d".repeat(64), Map.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed synthetic admission");

        HearingSyntheticComparisonService conflict = new HearingSyntheticComparisonService(
                parity,
                candidate -> new ParityComparison(
                        candidate.schemaVersion(),
                        candidate.comparisonKeyHash(),
                        candidate.scopeHash(),
                        candidate.legacyTraceHash(),
                        candidate.shadowTraceHash(),
                        candidate.dimensions(),
                        candidate.stopConditions(),
                        "e".repeat(64)),
                HearingReliabilityObservationSink.noop());
        assertThatThrownBy(() -> conflict.compareAndRecord(
                        admission(),
                        snapshot(LEGACY_TRACE, Map.of(), Set.of()),
                        snapshot(SHADOW_TRACE, Map.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different synthetic evidence");
    }

    @Test
    void rawTextAndIncompleteSnapshotsCannotEnterComparisonRows() {
        assertThatThrownBy(() -> new ObservedValue(Classification.VALUE, "party statement"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> new ParitySnapshot(SHADOW_TRACE, Map.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every Hearing parity dimension");
    }

    private static AdmissionReceipt admission() {
        return new AdmissionReceipt(
                "synthetic-fixture-1",
                ScopeKind.SHARED,
                "1".repeat(64),
                "key-1",
                "2".repeat(64),
                "3".repeat(64),
                SHADOW_TRACE,
                Map.of("suite", "phase6"),
                "p6-policy-v1",
                SinkDisposition.NO_FORMAL_SINK);
    }

    private static ParitySnapshot snapshot(
            String traceHash,
            Map<Dimension, ObservedValue> replacements,
            Set<StopCondition> stops) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            values.put(dimension, value(Classification.VALUE, 'f'));
        }
        values.putAll(replacements);
        return new ParitySnapshot(traceHash, values, stops);
    }

    private static ObservedValue value(Classification classification, char hashCharacter) {
        return new ObservedValue(classification, String.valueOf(hashCharacter).repeat(64));
    }
}
