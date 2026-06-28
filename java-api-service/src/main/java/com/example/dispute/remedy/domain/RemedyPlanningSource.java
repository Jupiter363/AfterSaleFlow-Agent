package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.List;
import java.util.Objects;

public record RemedyPlanningSource(
        String caseId,
        RouteType sourceRoute,
        RiskLevel caseRiskLevel,
        String sourceConclusionCode,
        List<String> recommendedActions,
        String draftId,
        String draftRecommendation,
        int planVersion) {

    public RemedyPlanningSource {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        Objects.requireNonNull(sourceRoute, "sourceRoute must not be null");
        Objects.requireNonNull(caseRiskLevel, "caseRiskLevel must not be null");
        if (sourceConclusionCode == null || sourceConclusionCode.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceConclusionCode must not be blank");
        }
        recommendedActions =
                recommendedActions == null
                        ? List.of()
                        : List.copyOf(recommendedActions);
        if (planVersion < 1) {
            throw new IllegalArgumentException("planVersion must be positive");
        }
        if (sourceRoute == RouteType.DISPUTE_HEARING
                && (draftId == null
                        || draftId.isBlank()
                        || draftRecommendation == null
                        || draftRecommendation.isBlank())) {
            throw new IllegalArgumentException(
                    "hearing source requires a non-final adjudication draft");
        }
    }
}
