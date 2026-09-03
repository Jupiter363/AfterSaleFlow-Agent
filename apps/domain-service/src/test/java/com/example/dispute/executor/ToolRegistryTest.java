/*
 * 所属模块：确定性工具执行。
 * 文件职责：验证工具，覆盖 「routesApprovedActionsThroughTheMatchingToolAdapter」、「rejectsActionsWithoutARegisteredAdapter」、「exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
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

// 所属模块：【确定性工具执行 / 自动化测试层】类型「ToolRegistryTest」。
// 类型职责：集中验证工具的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「routesApprovedActionsThroughTheMatchingToolAdapter」、「rejectsActionsWithoutARegisteredAdapter」、「exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ToolRegistryTest {

    private final ToolRegistry registry =
            new ToolRegistry(List.of(new SimulatedExecutionTool()));

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter()」。
    // 具体功能：「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter()」：复现“核对完整业务行为（场景方法「routesApprovedActionsThroughTheMatchingToolAdapter」）”场景：驱动 「registry.execute」、「result.toolName」、「result.operation」、「result.simulated」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND」、「IDEMPOTENCY_REFUND」、「case_id」、「CASE_1」。
    // 上游调用：「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter()」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」、「IDEMPOTENCY_REFUND」、「case_id」、「CASE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter()」。
    // 具体功能：「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter()」：复现“拒绝非法输入或越权操作（场景方法「rejectsActionsWithoutARegisteredAdapter」）”场景：驱动 「registry.execute」、「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UNREGISTERED_ACTION」、「IDEMPOTENCY_UNKNOWN」。
    // 上游调用：「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter()」守住「确定性工具执行」的可执行规格，尤其防止 「UNREGISTERED_ACTION」、「IDEMPOTENCY_UNKNOWN」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority()」。
    // 具体功能：「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority()」：复现“核对完整业务行为（场景方法「exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority」）”场景：驱动 「registry.definitions」、「definition.actionType」、「refund.toolName」、「refund.operation」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND」、「RESHIP」、「REPLACE」、「CLOSE_AFTER_SALE」。
    // 上游调用：「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority()」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」、「RESHIP」、「REPLACE」、「CLOSE_AFTER_SALE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
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
