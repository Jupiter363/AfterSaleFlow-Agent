package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.tool.application.SimulatedExecutionTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatedExecutionToolTest {

    private final SimulatedExecutionTool tool = new SimulatedExecutionTool();

    @Test
    void mapsEveryApprovedExecutionFamilyToADeterministicSimulation() {
        Map<String, String> expectedTools =
                Map.ofEntries(
                        Map.entry("REFUND", "after_sale_tool"),
                        Map.entry("RESHIP", "warehouse_tool"),
                        Map.entry("REPLACE", "warehouse_tool"),
                        Map.entry("CLOSE_AFTER_SALE", "after_sale_tool"),
                        Map.entry("REJECT_AFTER_SALE", "after_sale_tool"),
                        Map.entry("CANCEL_ORDER", "order_tool"),
                        Map.entry("CREATE_MANUAL_REVIEW_TICKET", "ticket_tool"),
                        Map.entry("CREATE_FULFILLMENT_REMINDER", "ticket_tool"),
                        Map.entry("NOTIFY_USER_AFTER_EXECUTION", "message_tool"),
                        Map.entry("NOTIFY_MERCHANT_AFTER_EXECUTION", "message_tool"),
                        Map.entry("AUDIT_EXECUTION_RESULT", "audit_tool"));

        expectedTools.forEach(
                (actionType, toolName) -> {
                    var result =
                            tool.execute(
                                    new ExecutableAction(
                                            actionType,
                                            "IDEMPOTENCY_" + actionType,
                                            RiskLevel.HIGH,
                                            Map.of("case_id", "CASE_1")));
                    assertThat(result.toolName()).isEqualTo(toolName);
                    assertThat(result.simulated()).isTrue();
                    assertThat(result.referenceId()).startsWith("SIM_");
                });
    }

    @Test
    void recordsDeterministicToolFailuresInsteadOfPretendingSuccess() {
        var action =
                new ExecutableAction(
                        "REFUND",
                        "IDEMPOTENCY_FAIL",
                        RiskLevel.HIGH,
                        Map.of("simulate_failure", true));

        assertThatThrownBy(() -> tool.execute(action))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("simulated");
    }
}
