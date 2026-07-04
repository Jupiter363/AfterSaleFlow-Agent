package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.Locale;
import java.util.Objects;

public final class DeliberationPolicy {

    private DeliberationPolicy() {}

    public static boolean shouldRunFinalPanel(
            RouteType routeType,
            String caseRiskLevel,
            DeliberationInterventionMode interventionMode,
            String minimumRiskLevel) {
        if (routeType != RouteType.FULL_HEARING) {
            return false;
        }
        if (interventionMode == DeliberationInterventionMode.DISABLED) {
            return false;
        }
        return rank(risk(caseRiskLevel)) >= rank(risk(minimumRiskLevel));
    }

    public static int validateScoreThreshold(int scoreThreshold) {
        if (scoreThreshold < 1 || scoreThreshold > 100) {
            throw new IllegalArgumentException(
                    "deliberation score threshold must be between 1 and 100");
        }
        return scoreThreshold;
    }

    public static int validateMaxRegenerations(int maxRegenerations) {
        if (maxRegenerations < 0 || maxRegenerations > 2) {
            throw new IllegalArgumentException(
                    "deliberation max regenerations must be between 0 and 2");
        }
        return maxRegenerations;
    }

    private static RiskLevel risk(String value) {
        String normalized =
                Objects.requireNonNull(value, "risk level must not be null")
                        .trim()
                        .toUpperCase(Locale.ROOT);
        return RiskLevel.valueOf(normalized);
    }

    private static int rank(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }
}
