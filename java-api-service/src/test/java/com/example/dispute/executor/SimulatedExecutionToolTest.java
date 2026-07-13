/*
 * 所属模块：确定性工具执行。
 * 文件职责：验证模拟执行工具，覆盖 「mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「recordsDeterministicToolFailuresInsteadOfPretendingSuccess」。
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
import java.util.Map;
import org.junit.jupiter.api.Test;

// 所属模块：【确定性工具执行 / 自动化测试层】类型「SimulatedExecutionToolTest」。
// 类型职责：集中验证模拟执行工具的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「recordsDeterministicToolFailuresInsteadOfPretendingSuccess」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class SimulatedExecutionToolTest {

    private final SimulatedExecutionTool tool = new SimulatedExecutionTool();

    // 所属模块：【确定性工具执行 / 自动化测试层】「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation()」。
    // 具体功能：「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation()」：复现“核对完整业务行为（场景方法「mapsEveryApprovedExecutionFamilyToADeterministicSimulation」）”场景：驱动 「Map.ofEntries」、「Map.entry」、「tool.execute」、「result.toolName」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND」、「after_sale_tool」、「RESHIP」、「warehouse_tool」。
    // 上游调用：「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation()」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」、「after_sale_tool」、「RESHIP」、「warehouse_tool」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
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

    // 所属模块：【确定性工具执行 / 自动化测试层】「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess()」。
    // 具体功能：「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess()」：复现“核对完整业务行为（场景方法「recordsDeterministicToolFailuresInsteadOfPretendingSuccess」）”场景：驱动 「tool.execute」、「hasMessageContaining」、「assertThatThrownBy(()->tool.execute(action)).isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND」、「IDEMPOTENCY_FAIL」、「simulate_failure」、「simulated」。
    // 上游调用：「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess()」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」、「IDEMPOTENCY_FAIL」、「simulate_failure」、「simulated」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
