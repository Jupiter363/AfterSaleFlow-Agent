package com.example.dispute.tool.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SimulatedExecutionTool implements ToolAdapter {

    private static final Map<String, ToolOperation> OPERATIONS =
            Map.ofEntries(
                    Map.entry("REFUND", new ToolOperation("after_sale_tool", "refund")),
                    Map.entry("RESHIP", new ToolOperation("warehouse_tool", "reship")),
                    Map.entry("REPLACE", new ToolOperation("warehouse_tool", "replace")),
                    Map.entry(
                            "CLOSE_AFTER_SALE",
                            new ToolOperation("after_sale_tool", "close")),
                    Map.entry(
                            "REJECT_AFTER_SALE",
                            new ToolOperation("after_sale_tool", "reject")),
                    Map.entry("CANCEL_ORDER", new ToolOperation("order_tool", "cancel")),
                    Map.entry(
                            "CREATE_MANUAL_REVIEW_TICKET",
                            new ToolOperation("ticket_tool", "create_manual_review")),
                    Map.entry(
                            "CREATE_FULFILLMENT_REMINDER",
                            new ToolOperation("ticket_tool", "create_fulfillment_reminder")),
                    Map.entry(
                            "NOTIFY_USER_AFTER_EXECUTION",
                            new ToolOperation("message_tool", "notify_user")),
                    Map.entry(
                            "NOTIFY_MERCHANT_AFTER_EXECUTION",
                            new ToolOperation("message_tool", "notify_merchant")),
                    Map.entry(
                            "AUDIT_EXECUTION_RESULT",
                            new ToolOperation("audit_tool", "record_execution")));

    @Override
    public boolean supports(String actionType) {
        return OPERATIONS.containsKey(actionType);
    }

    @Override
    public ToolExecutionResult execute(ExecutableAction action) {
        ToolOperation operation = OPERATIONS.get(action.actionType());
        if (operation == null) {
            throw new ToolExecutionException(
                    ErrorCode.TOOL_EXECUTION_DENIED,
                    "unsupported approved action",
                    Map.of("action_type", action.actionType()));
        }
        if (Boolean.TRUE.equals(action.parameters().get("simulate_failure"))) {
            throw new ToolExecutionException(
                    ErrorCode.TOOL_EXECUTION_FAILED,
                    "simulated tool execution failed",
                    Map.of("action_type", action.actionType()));
        }
        String referenceId = "SIM_" + digest(action.idempotencyKey()).substring(0, 24);
        return new ToolExecutionResult(
                operation.toolName(),
                operation.operation(),
                referenceId,
                true,
                Map.of(
                        "status", "SUCCEEDED",
                        "action_type", action.actionType(),
                        "idempotency_key", action.idempotencyKey()));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ToolOperation(String toolName, String operation) {}
}
