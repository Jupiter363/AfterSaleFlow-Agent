package com.example.dispute.review.domain;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.hearing.domain.HearingDecisionAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Resolves the bounded human-review decision against one frozen Judge V2 packet. */
public final class ReviewDecisionPlanPolicy {

    private ReviewDecisionPlanPolicy() {}

    public static Resolution resolve(
            ObjectMapper mapper,
            ApprovalDecisionType decision,
            JsonNode frozenPlan,
            JsonNode frozenDraft,
            JsonNode submittedPlan) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(decision, "decision");
        if (frozenPlan == null || !frozenPlan.isObject()) {
            throw new IllegalArgumentException("frozen review plan must be an object");
        }
        ObjectNode original = ((ObjectNode) frozenPlan).deepCopy();
        if (decision != ApprovalDecisionType.APPROVE
                && decision != ApprovalDecisionType.MODIFY_AND_APPROVE
                && decision != ApprovalDecisionType.ESCALATE_MANUAL) {
            throw new IllegalArgumentException("unsupported current review decision: " + decision);
        }

        String aiDecisionAction = findDecisionAction(original, frozenDraft);
        if (!HearingDecisionAction.supports(aiDecisionAction)) {
            throw new IllegalArgumentException(
                    "frozen Judge V2 draft has no supported decision_action");
        }
        original.put("decision_action", aiDecisionAction);
        if (decision == ApprovalDecisionType.ESCALATE_MANUAL) {
            if (submittedPlan != null && !submittedPlan.isNull()) {
                throw new IllegalArgumentException(
                        "ESCALATE_MANUAL does not accept an approved_plan");
            }
            return new Resolution(
                    original, mapper.createObjectNode(), aiDecisionAction, "ESCALATE_MANUAL");
        }
        if (decision == ApprovalDecisionType.APPROVE) {
            if (submittedPlan != null && !submittedPlan.isNull()) {
                throw new IllegalArgumentException("APPROVE must use the frozen AI decision");
            }
            return new Resolution(
                    original, original.deepCopy(), aiDecisionAction, aiDecisionAction);
        }

        if (submittedPlan == null || !submittedPlan.isObject()) {
            throw new IllegalArgumentException(
                    "approved_plan is required for MODIFY_AND_APPROVE");
        }
        String selectedDecisionAction = submittedPlan.path("decision_action").asText();
        if (!HearingDecisionAction.supports(selectedDecisionAction)) {
            throw new IllegalArgumentException(
                    "MODIFY_AND_APPROVE requires a supported decision_action");
        }
        if (aiDecisionAction.equals(selectedDecisionAction)) {
            throw new IllegalArgumentException(
                    "selected decision_action is already the frozen AI decision");
        }
        ObjectNode expected = original.deepCopy();
        expected.put("decision_action", selectedDecisionAction);
        if (!expected.equals(submittedPlan)) {
            throw new IllegalArgumentException(
                    "MODIFY_AND_APPROVE may only replace decision_action");
        }
        return new Resolution(
                original, expected, aiDecisionAction, selectedDecisionAction);
    }

    private static String findDecisionAction(JsonNode frozenPlan, JsonNode frozenDraft) {
        for (JsonNode candidate : new JsonNode[] {
            frozenPlan.path("decision_action"),
            frozenDraft == null ? null : frozenDraft.path("decision_action"),
            frozenDraft == null ? null : frozenDraft.path("draft").path("decision_action"),
            frozenDraft == null
                    ? null
                    : frozenDraft.path("draft").path("draft").path("decision_action")
        }) {
            if (candidate != null && candidate.isTextual()) {
                String value = candidate.asText();
                if (HearingDecisionAction.supports(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    public record Resolution(
            JsonNode originalPlan,
            JsonNode approvedPlan,
            String aiDecisionAction,
            String reviewerDecisionAction) {
        public Resolution {
            originalPlan = originalPlan.deepCopy();
            approvedPlan = approvedPlan.deepCopy();
        }

        @Override
        public JsonNode originalPlan() {
            return originalPlan.deepCopy();
        }

        @Override
        public JsonNode approvedPlan() {
            return approvedPlan.deepCopy();
        }
    }
}
