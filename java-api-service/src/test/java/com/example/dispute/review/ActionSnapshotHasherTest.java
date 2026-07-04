package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ActionSnapshotHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void hashIsStableWhenNestedObjectFieldOrderChanges() throws Exception {
        var original =
                objectMapper.readTree(
                        """
                        {
                          "id":"REMEDY_1",
                          "version":1,
                          "actions":[{
                            "action_type":"RESHIP",
                            "idempotency_key":"REMEDY:CASE_1:1:0:RESHIP",
                            "preconditions":["PLATFORM_REVIEW_APPROVED"],
                            "risk_level":"HIGH",
                            "requires_approval":true,
                            "parameters":{"sku":"A","qty":1}
                          }],
                          "preconditions":["PLATFORM_REVIEW_APPROVED"],
                          "notifications":["NOTIFY_USER_AFTER_EXECUTION"]
                        }
                        """);
        var reordered =
                objectMapper.readTree(
                        """
                        {
                          "notifications":["NOTIFY_USER_AFTER_EXECUTION"],
                          "preconditions":["PLATFORM_REVIEW_APPROVED"],
                          "actions":[{
                            "parameters":{"qty":1,"sku":"A"},
                            "requires_approval":true,
                            "risk_level":"HIGH",
                            "preconditions":["PLATFORM_REVIEW_APPROVED"],
                            "idempotency_key":"REMEDY:CASE_1:1:0:RESHIP",
                            "action_type":"RESHIP"
                          }],
                          "version":1,
                          "id":"REMEDY_1"
                        }
                        """);

        assertThat(ActionSnapshotHasher.hash(objectMapper, reordered))
                .isEqualTo(ActionSnapshotHasher.hash(objectMapper, original));
    }
}
