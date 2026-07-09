package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.tool.application.SimulatedExecutionTool;
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
}
