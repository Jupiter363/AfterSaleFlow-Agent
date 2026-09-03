package com.example.dispute.room.application;

import java.util.List;
import java.util.Map;

/** Deterministic review routing for model-generated evidence scores. */
final class EvidenceAssessmentReviewPolicy {

    static final double LOW_AUTHENTICITY_THRESHOLD = 0.50;
    static final double LOW_RELEVANCE_THRESHOLD = 0.50;

    private EvidenceAssessmentReviewPolicy() {}

    static Decision evaluate(
            double authenticityScore,
            double relevanceScore,
            List<Map<String, Object>> riskFlags) {
        boolean explicitTamperingRisk =
                riskFlags != null
                        && riskFlags.stream()
                                .map(flag -> flag.get("code"))
                                .anyMatch(
                                        code ->
                                                "SUSPECTED_FORGERY".equals(code)
                                                        || "TAMPERING_SUSPECTED".equals(code));
        return new Decision(
                authenticityScore < LOW_AUTHENTICITY_THRESHOLD || explicitTamperingRisk,
                relevanceScore < LOW_RELEVANCE_THRESHOLD);
    }

    record Decision(boolean suspectedForgery, boolean lowRelevance) {
        boolean requiresHumanReview() {
            return suspectedForgery || lowRelevance;
        }
    }
}
