package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RemedyPlanner {

    private static final Set<String> HIGH_RISK_ACTIONS =
            Set.of(
                    "REFUND",
                    "RESHIP",
                    "REPLACE",
                    "CANCEL_ORDER",
                    "REJECT_AFTER_SALE",
                    "CLOSE_AFTER_SALE");

    public RemedyPlanDraft plan(RemedyPlanningSource source) {
        List<ActionSeed> seeds =
                source.sourceRoute() == RouteType.FULL_HEARING
                        ? List.of(fromDraft(source.draftRecommendation()))
                        : source.recommendedActions().stream()
                                .map(action -> new ActionSeed(action, Map.of()))
                                .toList();
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                    "upstream conclusion has no recommended action");
        }
        List<PlannedRemedyAction> actions = new ArrayList<>();
        RiskLevel planRisk = source.caseRiskLevel();
        for (int index = 0; index < seeds.size(); index++) {
            ActionSeed seed = seeds.get(index);
            String actionType = normalize(seed.actionType());
            RiskLevel actionRisk = actionRisk(actionType);
            planRisk = max(planRisk, actionRisk);
            actions.add(
                    new PlannedRemedyAction(
                            actionType,
                            seed.parameters(),
                            "REMEDY:"
                                    + source.caseId()
                                    + ":"
                                    + source.planVersion()
                                    + ":"
                                    + index
                                    + ":"
                                    + actionType,
                            preconditions(actionType),
                            actionRisk,
                            true));
        }
        return new RemedyPlanDraft(
                source.sourceConclusionCode(),
                source.draftId(),
                planRisk,
                actions,
                List.of(
                        "CASE_NOT_CLOSED",
                        "PLAN_VERSION_CURRENT",
                        "PLATFORM_REVIEW_APPROVED"),
                List.of(
                        "NOTIFY_USER_AFTER_EXECUTION",
                        "NOTIFY_MERCHANT_AFTER_EXECUTION",
                        "AUDIT_EXECUTION_RESULT"),
                true);
    }

    private static ActionSeed fromDraft(String recommendation) {
        String normalized = recommendation.toUpperCase(Locale.ROOT);
        String actionType;
        if (normalized.contains("REFUND")) {
            actionType = "REFUND";
        } else if (normalized.contains("RESHIP")
                || normalized.contains("RESEND")) {
            actionType = "RESHIP";
        } else if (normalized.contains("REPLACE")
                || normalized.contains("EXCHANGE")) {
            actionType = "REPLACE";
        } else if (recommendation.contains("退款")
                || recommendation.contains("退费")
                || recommendation.contains("返款")) {
            actionType = "REFUND";
        } else if (recommendation.contains("补发")
                || recommendation.contains("重发")
                || recommendation.contains("重新发")
                || recommendation.contains("再次发")) {
            actionType = "RESHIP";
        } else if (recommendation.contains("换货")
                || recommendation.contains("更换")
                || recommendation.contains("调换")) {
            actionType = "REPLACE";
        } else if (normalized.contains("REJECT")
                || normalized.contains("DENY")) {
            actionType = "REJECT_AFTER_SALE";
        } else {
            actionType = "CREATE_MANUAL_REVIEW_TICKET";
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("source_recommendation", recommendation);
        parameters.put("source_is_final_decision", false);
        return new ActionSeed(actionType, parameters);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "recommended action must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static RiskLevel actionRisk(String actionType) {
        if (HIGH_RISK_ACTIONS.contains(actionType)) {
            return RiskLevel.HIGH;
        }
        if ("CREATE_FULFILLMENT_REMINDER".equals(actionType)) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static List<String> preconditions(String actionType) {
        List<String> conditions =
                new ArrayList<>(
                        List.of(
                                "CASE_NOT_CLOSED",
                                "PLAN_VERSION_CURRENT",
                                "PLATFORM_REVIEW_APPROVED"));
        switch (actionType) {
            case "REFUND" -> {
                conditions.add("PAYMENT_ELIGIBLE");
                conditions.add("REFUND_AMOUNT_RESOLVED");
            }
            case "CANCEL_ORDER" -> conditions.add("ORDER_CANCELLABLE");
            case "RESHIP", "REPLACE" -> conditions.add("INVENTORY_AVAILABLE");
            case "REJECT_AFTER_SALE", "CLOSE_AFTER_SALE" ->
                    conditions.add("REVIEW_DECISION_RECORDED");
            default -> conditions.add("TARGET_RESOURCE_AVAILABLE");
        }
        return List.copyOf(conditions);
    }

    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private record ActionSeed(String actionType, Map<String, Object> parameters) {}
}
