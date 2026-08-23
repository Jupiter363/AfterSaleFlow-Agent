package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.review.api.ReviewController;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewDecisionCommandTest {

    private final ObjectMapper mapper=new ObjectMapper();

    @Test
    void explicitUnconfirmedDecisionFailsClosed() {
        assertThatThrownBy(() -> new ReviewDecisionCommand(
                        ApprovalDecisionType.REJECT,"not supported",null,"key",false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmation");
    }

    @Test
    void approvedPlanIsDefensivelyCopied() {
        var input=mapper.createObjectNode().put("id","PLAN_1");
        ReviewDecisionCommand command=new ReviewDecisionCommand(
                ApprovalDecisionType.MODIFY_AND_APPROVE,"adjust",input,"key");
        input.put("id","MUTATED");
        var returned=command.approvedPlan();
        ((com.fasterxml.jackson.databind.node.ObjectNode) returned).put("id","ALSO_MUTATED");

        assertThat(command.approvedPlan().path("id").asText()).isEqualTo("PLAN_1");
    }

    @ParameterizedTest
    @EnumSource(
            value = ApprovalDecisionType.class,
            names = {"REJECT", "REQUEST_MORE_EVIDENCE"})
    void removedReviewDecisionsFailBeforePersistence(ApprovalDecisionType decision) {
        assertThatThrownBy(() -> new ReviewDecisionCommand(
                        decision, "legacy action is closed", null, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current review decision");
    }

    @ParameterizedTest
    @ValueSource(strings={
            "room_epoch","roomEpoch","room-epoch","workflow_id","receipt_hash",
            "source_revision","committed_event_sequence","required_operation_set_ref",
            "required_operation_set_hash","required_operation_count","idempotency_key_hash",
            "synthetic_only","expected_binding","expectedBinding","epoch","outcome_epoch",
            "outcomeEpoch","fence","fencing_token","fencingToken","revision",
            "process_revision","processRevision","request_hash","requestHash","packet_hash",
            "packetHash","action_hash","actionHash","policy_version","policyVersion",
            "arbitrary_unknown"
    })
    void publicRequestRejectsEveryUnknownProperty(String field) {
        ObjectMapper httpMapper=new ObjectMapper()
                .setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

        assertThatThrownBy(() -> httpMapper.readValue("""
                        {"decision":"APPROVE","reason":"forge","%s":99}
                        """.formatted(field),ReviewController.DecisionRequest.class))
                .as("unknown public field %s",field)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Unknown public review decision property: "+field);
    }

    @ParameterizedTest
    @ValueSource(strings={"approvedPlan","approved_plan"})
    void approvedPlanCamelAndSnakeAliasesRemainCompatible(String approvedPlanField) throws Exception {
        ObjectMapper httpMapper=new ObjectMapper()
                .setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

        var request=httpMapper.readValue(
                """
                {"decision":"MODIFY_AND_APPROVE","reason":"reviewed","%s":{"id":"PLAN_1"}}
                """.formatted(approvedPlanField),
                ReviewController.DecisionRequest.class);

        assertThat(request.confirmed()).isTrue();
        assertThat(request.decision()).isEqualTo(ApprovalDecisionType.MODIFY_AND_APPROVE);
        assertThat(request.reason()).isEqualTo("reviewed");
        assertThat(request.approvedPlan().path("id").asText()).isEqualTo("PLAN_1");
    }

    @Test
    void approvedPlanSerializesWithThePublicSnakeCaseName() {
        ObjectMapper httpMapper=new ObjectMapper()
                .setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var request=new ReviewController.DecisionRequest(
                ApprovalDecisionType.MODIFY_AND_APPROVE,
                "reviewed",
                mapper.createObjectNode().put("id","PLAN_1"));

        var serialized=httpMapper.valueToTree(request);

        assertThat(serialized.has("approved_plan")).isTrue();
        assertThat(serialized.has("approvedPlan")).isFalse();
    }
}
