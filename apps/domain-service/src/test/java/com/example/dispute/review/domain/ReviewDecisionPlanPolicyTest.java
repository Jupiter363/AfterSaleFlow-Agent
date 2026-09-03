package com.example.dispute.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReviewDecisionPlanPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void approvePersistsTheFrozenAiDecisionAction() {
        var result = ReviewDecisionPlanPolicy.resolve(
                mapper,
                ApprovalDecisionType.APPROVE,
                frozenPlan(),
                mapper.createObjectNode().put("decision_action", "REFUND_ONLY"),
                null);

        assertThat(result.aiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(result.reviewerDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(result.originalPlan().path("decision_action").asText())
                .isEqualTo("REFUND_ONLY");
        assertThat(result.approvedPlan()).isEqualTo(result.originalPlan());
    }

    @Test
    void modifyOnlyAcceptsAnotherCatalogDecisionAction() {
        ObjectNode submitted = frozenPlan();
        submitted.put("decision_action", "REPLACE");

        var result = ReviewDecisionPlanPolicy.resolve(
                mapper,
                ApprovalDecisionType.MODIFY_AND_APPROVE,
                frozenPlan(),
                mapper.createObjectNode().put("decision_action", "REFUND_ONLY"),
                submitted);

        assertThat(result.aiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(result.reviewerDecisionAction()).isEqualTo("REPLACE");
        assertThat(result.approvedPlan().path("decision_action").asText())
                .isEqualTo("REPLACE");
        assertThat(result.approvedPlan().path("actions"))
                .isEqualTo(result.originalPlan().path("actions"));
    }

    @Test
    void modifyRejectsExtraPlanChangesAndTheExistingAiDecision() {
        ObjectNode changedActions = frozenPlan();
        changedActions.put("decision_action", "REPLACE");
        ((ObjectNode) changedActions.path("actions").get(0)).put("amount", 1);
        ObjectNode unchangedDecision = frozenPlan();
        unchangedDecision.put("decision_action", "REFUND_ONLY");
        ObjectNode draft = mapper.createObjectNode().put("decision_action", "REFUND_ONLY");

        assertThatThrownBy(() -> ReviewDecisionPlanPolicy.resolve(
                        mapper,
                        ApprovalDecisionType.MODIFY_AND_APPROVE,
                        frozenPlan(),
                        draft,
                        changedActions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only replace decision_action");
        assertThatThrownBy(() -> ReviewDecisionPlanPolicy.resolve(
                        mapper,
                        ApprovalDecisionType.MODIFY_AND_APPROVE,
                        frozenPlan(),
                        draft,
                        unchangedDecision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already the frozen AI decision");
    }

    @Test
    void escalationNeedsOnlyTheHumanOpinionAndNoDecisionAction() {
        var result = ReviewDecisionPlanPolicy.resolve(
                mapper,
                ApprovalDecisionType.ESCALATE_MANUAL,
                frozenPlan(),
                mapper.createObjectNode().put("decision_action", "REFUND_ONLY"),
                null);

        assertThat(result.aiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(result.reviewerDecisionAction()).isEqualTo("ESCALATE_MANUAL");
        assertThat(result.approvedPlan()).isEmpty();
    }

    private ObjectNode frozenPlan() {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("id", "PLAN_1");
        plan.put("version", 3);
        plan.putArray("actions").addObject().put("type", "REFUND");
        plan.putArray("preconditions");
        plan.putArray("notifications");
        return plan;
    }
}
