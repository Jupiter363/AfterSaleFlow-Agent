package com.example.dispute.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentRunEntityTest {

    @Test
    void completedRunRetainsGovernanceCostAndTraceMetadata() {
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        AgentRunEntity run =
                AgentRunEntity.completed(
                        "RUN_test",
                        "CASE_test",
                        "WORKFLOW_test",
                        "presiding-judge",
                        "PRESIDING_JUDGE",
                        "presiding-judge-v1",
                        "hearing-v1",
                        "dispute-default-v1",
                        "ruleset-current",
                        "test-model",
                        "[\"EVIDENCE_1\",\"RULE_1\"]",
                        "DRAFT_1",
                        "{\"schema_valid\":true}",
                        "[]",
                        321,
                        450L,
                        new BigDecimal("0.012345"),
                        startedAt,
                        "TRACE_test",
                        "temporal-worker");

        assertThat(run.getRunStatus()).isEqualTo("COMPLETED");
        assertThat(run.getTraceId()).isEqualTo("TRACE_test");
        assertThat(run.getTokenUsage()).isEqualTo(321);
        assertThat(run.getLatencyMs()).isEqualTo(450L);
        assertThat(run.getCostAmount()).isEqualByComparingTo("0.012345");
        assertThat(run.getOutputRef()).isEqualTo("DRAFT_1");
    }
}
