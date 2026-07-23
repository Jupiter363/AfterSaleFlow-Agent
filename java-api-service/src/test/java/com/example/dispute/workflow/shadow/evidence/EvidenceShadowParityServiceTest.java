package com.example.dispute.workflow.shadow.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.Classification;
import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.Dimension;
import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.ObservedValue;
import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.StopCondition;
import com.example.dispute.workflow.shadow.evidence.EvidenceShadowParityService.Verdict;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceShadowParityServiceTest {

    private static final String COMPARISON_KEY = "f".repeat(64);

    @Test
    void comparesEveryFixedTextFreeDimension() {
        EvidenceShadowParityService service = new EvidenceShadowParityService();
        ParitySnapshot snapshot = snapshot(Map.of(), Set.of());

        EvidenceShadowParityService.ParityComparison comparison =
                service.compare(COMPARISON_KEY, snapshot, snapshot);

        assertThat(comparison.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(comparison.dimensions().keySet())
                .containsExactlyInAnyOrder(Dimension.values());
        assertThat(comparison.differingDimensions()).isEmpty();
    }

    @Test
    void reportsBoundedDifferencesAndHardStopsWithoutNaturalLanguage() {
        EvidenceShadowParityService service = new EvidenceShadowParityService();
        ParitySnapshot legacy = snapshot(Map.of(), Set.of());
        ParitySnapshot shadow = snapshot(
                Map.of(Dimension.REVIEW_CLASSIFICATION, value(Classification.INVALID, 'a')),
                Set.of());

        assertThat(service.compare(COMPARISON_KEY, legacy, shadow).verdict())
                .isEqualTo(Verdict.DIFFERENT);
        assertThat(service.compare(
                        COMPARISON_KEY,
                        legacy,
                        snapshot(Map.of(), Set.of(StopCondition.PRIVACY_VIOLATION)))
                .verdict())
                .isEqualTo(Verdict.HARD_FAILURE);
    }

    @Test
    void rejectsRawDataAndIncompleteParitySnapshots() {
        assertThatThrownBy(() -> new ObservedValue(Classification.VALUE, "private evidence text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase SHA-256");
        assertThatThrownBy(() -> new ParitySnapshot(Map.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every bounded parity dimension");
    }

    private static ParitySnapshot snapshot(
            Map<Dimension, ObservedValue> replacements,
            Set<StopCondition> stopConditions) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            values.put(dimension, value(Classification.VALUE, 'b'));
        }
        values.putAll(replacements);
        return new ParitySnapshot(values, stopConditions);
    }

    private static ObservedValue value(Classification classification, char character) {
        return new ObservedValue(classification, String.valueOf(character).repeat(64));
    }
}
