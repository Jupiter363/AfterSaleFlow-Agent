package com.example.dispute.room.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceAssessmentReviewPolicyTest {

    @Test
    void lowAuthenticityIsSuspectedForgeryAndRequiresHumanReview() {
        var result = EvidenceAssessmentReviewPolicy.evaluate(0.49, 0.95, List.of());

        assertThat(result.suspectedForgery()).isTrue();
        assertThat(result.lowRelevance()).isFalse();
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void lowRelevanceRequiresReviewButIsNotCalledForgery() {
        var result = EvidenceAssessmentReviewPolicy.evaluate(0.95, 0.49, List.of());

        assertThat(result.suspectedForgery()).isFalse();
        assertThat(result.lowRelevance()).isTrue();
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void scoresAreIndependentAndNotCombinedByAWeightedFormula() {
        var result = EvidenceAssessmentReviewPolicy.evaluate(0.50, 0.50, List.of());

        assertThat(result.suspectedForgery()).isFalse();
        assertThat(result.lowRelevance()).isFalse();
        assertThat(result.requiresHumanReview()).isFalse();
    }

    @Test
    void explicitTamperingRiskStillRoutesToSuspectedForgeryReview() {
        var result =
                EvidenceAssessmentReviewPolicy.evaluate(
                        0.90,
                        0.90,
                        List.of(Map.of("code", "TAMPERING_SUSPECTED")));

        assertThat(result.suspectedForgery()).isTrue();
        assertThat(result.requiresHumanReview()).isTrue();
    }
}
