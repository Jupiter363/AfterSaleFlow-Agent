/*
 * 所属模块：确定性工具执行。
 * 文件职责：定义Executable动作跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Map;

// 所属模块：【确定性工具执行 / 领域模型层】类型「ExecutableAction」。
// 类型职责：定义Executable动作跨层传递时使用的不可变数据契约；本类型显式提供 「ExecutableAction」。
// 协作关系：主要由 「ToolExecutorService.approvedActions」、「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess」、「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ExecutableAction(
        String actionType,
        String idempotencyKey,
        RiskLevel riskLevel,
        Map<String, Object> parameters) {

    // 所属模块：【确定性工具执行 / 领域模型层】「ExecutableAction.ExecutableAction(String,String,RiskLevel,Map)」。
    // 具体功能：「ExecutableAction.ExecutableAction(String,String,RiskLevel,Map)」：在不可变「ExecutableAction」写入组件前校验 「actionType」(String)、「idempotencyKey」(String)、「riskLevel」(RiskLevel)、「parameters」(Map)，并统一规范 record 组件值。
    // 上游调用：「ExecutableAction.ExecutableAction(String,String,RiskLevel,Map)」的上游创建点包括 「ToolExecutorService.approvedActions」、「SimulatedExecutionToolTest.mapsEveryApprovedExecutionFamilyToADeterministicSimulation」、「SimulatedExecutionToolTest.recordsDeterministicToolFailuresInsteadOfPretendingSuccess」、「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter」。
    // 下游影响：「ExecutableAction.ExecutableAction(String,String,RiskLevel,Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExecutableAction.ExecutableAction(String,String,RiskLevel,Map)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ExecutableAction {
        parameters = Map.copyOf(parameters);
    }
}
