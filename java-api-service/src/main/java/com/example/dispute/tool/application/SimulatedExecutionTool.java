/*
 * 所属模块：执行工具目录。
 * 文件职责：承载模拟执行工具在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「definitions」、「supports」、「execute」；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
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

// 所属模块：【执行工具目录 / 应用编排层】类型「SimulatedExecutionTool」。
// 类型职责：承载模拟执行工具在当前业务模块中的规则与协作边界；本类型显式提供 「definitions」、「supports」、「execute」、「digest」、「definition」。
// 协作关系：主要由 「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess」 使用。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【执行工具目录 / 应用编排层】「SimulatedExecutionTool.definitions()」。
    // 具体功能：「SimulatedExecutionTool.definitions()」：构建定义列表，最终返回「List<ToolDefinition>」。
    // 上游调用：「SimulatedExecutionTool.definitions()」由使用「SimulatedExecutionTool」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SimulatedExecutionTool.definitions()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<ToolDefinition>」交给调用方。
    // 系统意义：「SimulatedExecutionTool.definitions()」负责主链路中的“定义列表”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    @Override
    public List<ToolDefinition> definitions() {
        return DEFINITIONS;
    }

    // 所属模块：【执行工具目录 / 应用编排层】「SimulatedExecutionTool.supports(String)」。
    // 具体功能：「SimulatedExecutionTool.supports(String)」：判断是否支持；实际协作者为 「OPERATIONS.containsKey」，最终返回「boolean」。
    // 上游调用：「SimulatedExecutionTool.supports(String)」由使用「SimulatedExecutionTool」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SimulatedExecutionTool.supports(String)」向下依次触达 「OPERATIONS.containsKey」；计算结果以「boolean」交给调用方。
    // 系统意义：「SimulatedExecutionTool.supports(String)」负责主链路中的“”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    @Override
    public boolean supports(String actionType) {
        return OPERATIONS.containsKey(actionType);
    }

    // 所属模块：【执行工具目录 / 应用编排层】「SimulatedExecutionTool.execute(ExecutableAction)」。
    // 具体功能：「SimulatedExecutionTool.execute(ExecutableAction)」：执行工具执行；实际协作者为 「action.actionType」、「action.parameters」、「action.idempotencyKey」、「operation.toolName」；不满足前置条件时抛出 「ToolExecutionException」；处理的关键状态/协议值包括 「action_type」、「simulate_failure」、「SIM_」、「status」，最终返回「ToolExecutionResult」。
    // 上游调用：「SimulatedExecutionTool.execute(ExecutableAction)」的上游调用点包括 「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess」。
    // 下游影响：「SimulatedExecutionTool.execute(ExecutableAction)」向下依次触达 「action.actionType」、「action.parameters」、「action.idempotencyKey」、「operation.toolName」；计算结果以「ToolExecutionResult」交给调用方。
    // 系统意义：「SimulatedExecutionTool.execute(ExecutableAction)」负责主链路中的“工具执行”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
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

    // 所属模块：【执行工具目录 / 应用编排层】「SimulatedExecutionTool.digest(String)」。
    // 具体功能：「SimulatedExecutionTool.digest(String)」：对模拟工具输入计算 SHA-256 十六进制摘要，生成可重复核对且不回显完整敏感参数的外部结果引用，最终返回「String」。
    // 上游调用：「SimulatedExecutionTool.digest(String)」的上游调用点包括 「SimulatedExecutionTool.execute」。
    // 下游影响：「SimulatedExecutionTool.digest(String)」向下依次触达 「MessageDigest.getInstance」、「HexFormat.of().formatHex」、「MessageDigest.getInstance("SHA-256").digest」；计算结果以「String」交给调用方。
    // 系统意义：「SimulatedExecutionTool.digest(String)」负责主链路中的“digest”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
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

    // 所属模块：【执行工具目录 / 应用编排层】「SimulatedExecutionTool.definition(String,String,String,String,String,RiskLevel)」。
    // 具体功能：「SimulatedExecutionTool.definition(String,String,String,String,String,RiskLevel)」：构建定义，最终返回「ToolDefinition」。
    // 上游调用：「SimulatedExecutionTool.definition(String,String,String,String,String,RiskLevel)」只由「SimulatedExecutionTool」内部流程使用，负责封装“定义”这一步校验、映射或状态转换。
    // 下游影响：「SimulatedExecutionTool.definition(String,String,String,String,String,RiskLevel)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ToolDefinition」交给调用方。
    // 系统意义：「SimulatedExecutionTool.definition(String,String,String,String,String,RiskLevel)」负责主链路中的“定义”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
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
