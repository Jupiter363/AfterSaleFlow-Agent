package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporalSearchAttributesTest {

    @Test
    void emitsOnlyTheApprovedNonContentVisibilityFields() {
        var attributes = new TemporalSearchAttributes(true).caseProcess(command("tenant-a"));

        assertThat(attributes.getUntypedValues().keySet())
                .extracting(key -> key.getName())
                .containsExactlyInAnyOrderElementsOf(TemporalSearchAttributes.allowedKeyNames());
        assertThat(attributes.get(TemporalSearchAttributes.TENANT_SURROGATE))
                .isEqualTo("tenant-a");
        assertThat(attributes.get(TemporalSearchAttributes.CASE_SURROGATE))
                .isEqualTo("CASE_VISIBLE");
        assertThat(attributes.get(TemporalSearchAttributes.WORKFLOW_KIND))
                .isEqualTo("CASE_PROCESS");
        assertThat(attributes.get(TemporalSearchAttributes.ROOM_TYPE))
                .isEqualTo("EVIDENCE");

        Set<String> normalized =
                TemporalSearchAttributes.allowedKeyNames().stream()
                        .map(String::toLowerCase)
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(normalized)
                .noneMatch(
                        key ->
                                key.contains("name")
                                        || key.contains("message")
                                        || key.contains("evidence")
                                        || key.contains("payload")
                                        || key.contains("trace")
                                        || key.contains("account"));
    }

    @Test
    void disabledVisibilityProducesNoCustomAttributes() {
        assertThat(new TemporalSearchAttributes(false).caseProcess(command("tenant-a")).size())
                .isZero();
    }

    @Test
    void unsafeTenantValuesFailBeforeTheyReachTemporalVisibility() {
        assertThatThrownBy(
                        () ->
                                new TemporalSearchAttributes(true)
                                        .caseProcess(command("Alice Example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-PII identifier");
    }

    private static CaseCommandRef command(String tenant) {
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        return new CaseCommandRef(
                "case-command-ref.v1",
                "command-visible",
                tenant,
                "CASE_VISIBLE",
                1,
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                1,
                new ActorRef("actor-surrogate", ActorRole.USER, List.of("case:write")),
                new PayloadRef(
                        "evidence-command.v1",
                        "urn:payload:visible",
                        "a".repeat(64),
                        42),
                0,
                now,
                now.plusSeconds(60),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "b".repeat(64));
    }
}
