package com.example.dispute.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.OffsetDateTime;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ReviewCopilotStreamServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void frozenReviewSourceItemsBecomeExactDeliberationCitationAuthority() {
        ReviewCopilotStreamService service =
                new ReviewCopilotStreamService(null, null, null, objectMapper);
        ArrayNode reviewSourceItems = objectMapper.createArrayNode();
        reviewSourceItems
                .addObject()
                .put("review_item_ref", "V1_FOCUS_01")
                .put("review_source", "V1_REVIEW_FOCUS")
                .put("review_item_text", "核验复测执行节点");
        reviewSourceItems
                .addObject()
                .put("review_item_ref", "JURY_MANDATORY_02")
                .put("review_source", "MANDATORY_REVISION")
                .put("review_item_text", "将执行动作与可验证节点绑定");
        reviewSourceItems
                .addObject()
                .put("review_item_ref", "JURY_MANDATORY_02")
                .put("unrelated_ref", "UNTRUSTED_REF");

        JsonNode request =
                service.request(
                        "REVIEW_1",
                        "继续履约与限期补充信息是否一致？",
                        packet(reviewSourceItems));

        assertThat(textValues(request.path("available_deliberation_refs")))
                .containsExactly("V1_FOCUS_01", "JURY_MANDATORY_02");
        assertThat(textValues(request.path("available_deliberation_refs")))
                .doesNotContain("UNTRUSTED_REF");
        assertThat(textValues(request.path("available_fact_refs")))
                .doesNotContain("JURY_MANDATORY_02");
    }

    private ReviewPacketView packet(JsonNode reviewSourceItems) {
        return new ReviewPacketView(
                "PACKET_1",
                "CASE_1",
                "PLAN_1",
                1,
                1,
                1,
                1,
                2,
                1,
                1,
                "ruleset-current",
                "hearing-flow.v2",
                "dispute-default-v1",
                "hearing-judge-v2",
                "a".repeat(64),
                "b".repeat(64),
                objectMapper.createArrayNode(),
                OffsetDateTime.parse("2026-09-02T13:01:00+08:00"),
                OffsetDateTime.parse("2026-09-09T13:01:00+08:00"),
                objectMapper.createObjectNode(),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                objectMapper.createObjectNode().put("id", "DRAFT_2"),
                reviewSourceItems,
                objectMapper.createObjectNode(),
                objectMapper.createArrayNode(),
                "FROZEN",
                "IN_REVIEW",
                "reviewer-local",
                OffsetDateTime.parse("2026-09-09T13:01:00+08:00"));
    }

    private static java.util.List<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
