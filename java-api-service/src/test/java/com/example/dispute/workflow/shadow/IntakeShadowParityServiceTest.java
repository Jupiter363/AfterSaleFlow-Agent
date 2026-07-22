package com.example.dispute.workflow.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Classification;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.HardZeroFinding;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Verdict;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IntakeShadowParityServiceTest {

    private static final String COMPARISON_KEY = "f".repeat(64);

    @Test
    void recordsTextFreeParityAcrossEveryFrozenDimension() {
        List<IntakeShadowComparison> recorded = new ArrayList<>();
        IntakeShadowParityService service = new IntakeShadowParityService(recorded::add);
        ParitySnapshot snapshot = snapshot(Map.of(), Set.of());

        IntakeShadowComparison comparison =
                service.compare(COMPARISON_KEY, snapshot, snapshot);

        assertThat(comparison.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(comparison.dimensions().keySet())
                .containsExactlyInAnyOrder(Dimension.values());
        assertThat(comparison.differingDimensions()).isEmpty();
        assertThat(recorded).containsExactly(comparison);
    }

    @Test
    void reportsOnlyBoundedDimensionDiffsInsteadOfNaturalLanguage() {
        IntakeShadowParityService service = new IntakeShadowParityService(ignored -> {});
        ParitySnapshot legacy = snapshot(Map.of(), Set.of());
        ParitySnapshot shadow = snapshot(
                Map.of(
                        Dimension.READINESS, value(Classification.NOT_READY, 'a'),
                        Dimension.NORMALIZED_PATCH, value(Classification.PRESENT, 'b'),
                        Dimension.RECOMMENDATION, value(Classification.REVIEW, 'c')),
                Set.of());

        IntakeShadowComparison comparison =
                service.compare(COMPARISON_KEY, legacy, shadow);

        assertThat(comparison.verdict()).isEqualTo(Verdict.DIFFERENT);
        assertThat(comparison.differingDimensions())
                .containsExactlyInAnyOrder(
                        Dimension.READINESS,
                        Dimension.NORMALIZED_PATCH,
                        Dimension.RECOMMENDATION);
        assertThat(comparison.hardZeroFindings()).isEmpty();
    }

    @Test
    void everyPrivacyFenceAuthorityOrSinkFindingIsAHardFailure() {
        IntakeShadowParityService service = new IntakeShadowParityService(ignored -> {});

        for (HardZeroFinding finding : HardZeroFinding.values()) {
            IntakeShadowComparison comparison = service.compare(
                    COMPARISON_KEY,
                    snapshot(Map.of(), Set.of()),
                    snapshot(Map.of(), Set.of(finding)));

            assertThat(comparison.verdict()).isEqualTo(Verdict.HARD_FAILURE);
            assertThat(comparison.hardZeroFindings()).containsExactly(finding);
        }
    }

    @Test
    void rejectsMalformedHashesAndIncompleteSnapshots() {
        assertThat(ObservedValue.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .containsExactly(Classification.class.getName(), String.class.getName());
        assertThatThrownBy(() -> new ObservedValue(Classification.READY, "raw-private-text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase SHA-256");
        assertThatThrownBy(() -> new ParitySnapshot(Map.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every bounded parity dimension");
    }

    private static ParitySnapshot snapshot(
            Map<Dimension, ObservedValue> replacements,
            Set<HardZeroFinding> findings) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            char hashCharacter = "abcdef".charAt(dimension.ordinal() % 6);
            values.put(dimension, value(Classification.VALUE, hashCharacter));
        }
        values.putAll(replacements);
        return new ParitySnapshot(values, findings);
    }

    private static ObservedValue value(
            Classification classification, char hashCharacter) {
        return new ObservedValue(classification, String.valueOf(hashCharacter).repeat(64));
    }
}
