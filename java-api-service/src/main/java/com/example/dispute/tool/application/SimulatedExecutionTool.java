package com.example.dispute.tool.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SimulatedExecutionTool implements ToolAdapter {

    private static final List<ToolDefinition> DEFINITIONS =
            List.of(
                    definition(
                            "REFUND",
                            "after_sale_tool",
                            "refund",
                            "模拟退款",
                            "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                            RiskLevel.HIGH),
                    definition(
                            "RESHIP",
                            "warehouse_tool",
                            "reship",
                            "模拟补发",
                            "仅在平台审核通过后模拟补发动作，不直接调用真实仓储下游。",
                            RiskLevel.HIGH),
                    definition(
                            "REPLACE",
                            "warehouse_tool",
                            "replace",
                            "模拟换货",
                            "仅在平台审核通过后模拟换货动作，不直接调用真实仓储下游。",
                            RiskLevel.HIGH),
                    definition(
                            "CLOSE_AFTER_SALE",
                            "after_sale_tool",
                            "close",
                            "模拟关闭售后",
                            "仅在平台审核通过后模拟关闭售后单，不直接关闭真实业务单据。",
                            RiskLevel.HIGH),
                    definition(
                            "REJECT_AFTER_SALE",
                            "after_sale_tool",
                            "reject",
                            "模拟驳回售后",
                            "仅在平台审核通过后模拟驳回售后申请，不直接变更真实售后状态。",
                            RiskLevel.HIGH),
                    definition(
                            "CANCEL_ORDER",
                            "order_tool",
                            "cancel",
                            "模拟取消订单",
                            "仅在平台审核通过后模拟取消订单，不直接调用真实订单下游。",
                            RiskLevel.HIGH),
                    definition(
                            "CREATE_MANUAL_REVIEW_TICKET",
                            "ticket_tool",
                            "create_manual_review",
                            "创建人工复核工单",
                            "在平台审核通过后模拟创建人工复核工单，用于后续人工处理。",
                            RiskLevel.LOW),
                    definition(
                            "CREATE_FULFILLMENT_REMINDER",
                            "ticket_tool",
                            "create_fulfillment_reminder",
                            "创建履约提醒",
                            "在平台审核通过后模拟创建履约提醒，用于提示商家或履约团队跟进。",
                            RiskLevel.MEDIUM),
                    definition(
                            "NOTIFY_USER_AFTER_EXECUTION",
                            "message_tool",
                            "notify_user",
                            "通知用户",
                            "在执行链路完成后模拟通知用户处理结果。",
                            RiskLevel.LOW),
                    definition(
                            "NOTIFY_MERCHANT_AFTER_EXECUTION",
                            "message_tool",
                            "notify_merchant",
                            "通知商家",
                            "在执行链路完成后模拟通知商家处理结果。",
                            RiskLevel.LOW),
                    definition(
                            "AUDIT_EXECUTION_RESULT",
                            "audit_tool",
                            "record_execution",
                            "记录执行审计",
                            "模拟记录执行结果和审计信息，用于后续追溯。",
                            RiskLevel.LOW));

    private static final Map<String, ToolDefinition> OPERATIONS =
            DEFINITIONS.stream()
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    ToolDefinition::actionType, Function.identity()));

    @Override
    public List<ToolDefinition> definitions() {
        return DEFINITIONS;
    }

    @Override
    public boolean supports(String actionType) {
        return OPERATIONS.containsKey(actionType);
    }

    @Override
    public ToolExecutionResult execute(ExecutableAction action) {
        ToolDefinition operation = OPERATIONS.get(action.actionType());
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

    private static ToolDefinition definition(
            String actionType,
            String toolName,
            String operation,
            String displayName,
            String description,
            RiskLevel riskLevel) {
        return new ToolDefinition(
                actionType,
                toolName,
                operation,
                displayName,
                description,
                riskLevel,
                true,
                true);
    }
}
