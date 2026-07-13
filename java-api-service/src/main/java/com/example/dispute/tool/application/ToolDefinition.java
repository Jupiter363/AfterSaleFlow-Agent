/*
 * 所属模块：执行工具目录。
 * 文件职责：定义工具定义跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
package com.example.dispute.tool.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

// 所属模块：【执行工具目录 / 应用编排层】类型「ToolDefinition」。
// 类型职责：定义工具定义跨层传递时使用的不可变数据契约；本类型显式提供 「ToolDefinition」、「requireText」。
// 协作关系：主要由 「SimulatedExecutionTool.definition」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」、「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog」 使用。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ToolDefinition(
        String actionType,
        String toolName,
        String operation,
        String displayName,
        String description,
        RiskLevel riskLevel,
        boolean simulated,
        boolean requiresApprovedPlan) {

    // 所属模块：【执行工具目录 / 应用编排层】「ToolDefinition.ToolDefinition(String,String,String,String,String,RiskLevel,boolean,boolean)」。
    // 具体功能：「ToolDefinition.ToolDefinition(String,String,String,String,String,RiskLevel,boolean,boolean)」：在不可变「ToolDefinition」写入组件前校验 「actionType」(String)、「toolName」(String)、「operation」(String)、「displayName」(String)、「description」(String)、「riskLevel」(RiskLevel)、「simulated」(boolean)、「requiresApprovedPlan」(boolean)，并通过 「Objects.requireNonNull」、「requireText」 做标准化或防御性复制。
    // 上游调用：「ToolDefinition.ToolDefinition(String,String,String,String,String,RiskLevel,boolean,boolean)」的上游创建点包括 「SimulatedExecutionTool.definition」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」、「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog」。
    // 下游影响：「ToolDefinition.ToolDefinition(String,String,String,String,String,RiskLevel,boolean,boolean)」向下依次触达 「Objects.requireNonNull」、「requireText」。
    // 系统意义：「ToolDefinition.ToolDefinition(String,String,String,String,String,RiskLevel,boolean,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ToolDefinition {
        actionType = requireText(actionType, "actionType");
        toolName = requireText(toolName, "toolName");
        operation = requireText(operation, "operation");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }

    // 所属模块：【执行工具目录 / 应用编排层】「ToolDefinition.requireText(String,String)」。
    // 具体功能：「ToolDefinition.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「ToolDefinition.requireText(String,String)」的上游调用点包括 「ToolDefinition.ToolDefinition」。
    // 下游影响：「ToolDefinition.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ToolDefinition.requireText(String,String)」在“文本”进入下游前阻断非法状态；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
