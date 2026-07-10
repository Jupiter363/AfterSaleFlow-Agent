package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.tool.application.SimulatedExecutionTool;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private final ToolRegistry registry =
            new ToolRegistry(List.of(new SimulatedExecutionTool()));

    @Test
    void routesApprovedActionsThroughTheMatchingToolAdapter() {
        var result =
                registry.execute(
                        new ExecutableAction(
                                "REFUND",
                                "IDEMPOTENCY_REFUND",
                                RiskLevel.HIGH,
                                Map.of("case_id", "CASE_1")));

        assertThat(result.toolName()).isEqualTo("after_sale_tool");
        assertThat(result.operation()).isEqualTo("refund");
        assertThat(result.simulated()).isTrue();
        assertThat(result.referenceId()).startsWith("SIM_");
    }

    @Test
    void rejectsActionsWithoutARegisteredAdapter() {
        assertThatThrownBy(
                        () ->
                                registry.execute(
                                        new ExecutableAction(
                                                "UNREGISTERED_ACTION",
                                                "IDEMPOTENCY_UNKNOWN",
                                                RiskLevel.LOW,
                                                Map.of())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("registered tool adapter");
    }

    @Test
    void exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority() {
        List<ToolDefinition> definitions = registry.definitions();

        assertThat(definitions)
                .extracting(ToolDefinition::actionType)
                .containsExactlyInAnyOrder(
                        "REFUND",
                        "RESHIP",
                        "REPLACE",
                        "CLOSE_AFTER_SALE",
                        "REJECT_AFTER_SALE",
                        "CANCEL_ORDER",
                        "CREATE_MANUAL_REVIEW_TICKET",
                        "CREATE_FULFILLMENT_REMINDER",
                        "NOTIFY_USER_AFTER_EXECUTION",
                        "NOTIFY_MERCHANT_AFTER_EXECUTION",
                        "AUDIT_EXECUTION_RESULT");

        ToolDefinition refund =
                definitions.stream()
                        .filter(definition -> definition.actionType().equals("REFUND"))
                        .findFirst()
                        .orElseThrow();
        assertThat(refund.toolName()).isEqualTo("after_sale_tool");
        assertThat(refund.operation()).isEqualTo("refund");
        assertThat(refund.displayName()).isEqualTo("模拟退款");
        assertThat(refund.description()).contains("仅在平台审核通过后");
        assertThat(refund.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(refund.simulated()).isTrue();
        assertThat(refund.requiresApprovedPlan()).isTrue();
    }
}
