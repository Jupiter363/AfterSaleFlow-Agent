/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义Simulate导入跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 所属模块：【案件核心与导入 / HTTP 接口层】类型「SimulateImportRequest」。
// 类型职责：定义Simulate导入跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」、「toCommand」。
// 协作关系：主要由 「DisputeImportSimulationController.simulateImport」、「InternalDisputeImportController.simulateImport」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SimulateImportRequest(
        @Min(1) @Max(1) int count,
        @Size(max = 256) String scenario,
        RiskLevel riskLevelHint,
        @NotNull ActorRole initiatorRoleHint,
        @NotBlank @Size(max = 128) String currentActorId,
        @NotBlank @Size(max = 128) String counterpartyActorId,
        @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                String simulationBatchId) {

    // 所属模块：【案件核心与导入 / HTTP 接口层】「SimulateImportRequest.toCommand()」。
    // 具体功能：「SimulateImportRequest.toCommand()」：提供「toCommand」的便捷重载：接收 无显式入参，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「SimulateImportRequest.toCommand()」的上游调用点包括 「DisputeImportSimulationController.simulateImport」、「InternalDisputeImportController.simulateImport」、「SimulateImportRequest.toCommand」。
    // 下游影响：「SimulateImportRequest.toCommand()」向下依次触达 「toCommand」；计算结果以「SimulateExternalImportCommand」交给调用方。
    // 系统意义：「SimulateImportRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public SimulateExternalImportCommand toCommand() {
        return toCommand(null);
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「SimulateImportRequest.toCommand(String)」。
    // 具体功能：「SimulateImportRequest.toCommand(String)」：转换命令：先更新内部状态 「simulationBatchId」，最终返回「SimulateExternalImportCommand」。
    // 上游调用：「SimulateImportRequest.toCommand(String)」的上游调用点包括 「DisputeImportSimulationController.simulateImport」、「InternalDisputeImportController.simulateImport」、「SimulateImportRequest.toCommand」。
    // 下游影响：「SimulateImportRequest.toCommand(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SimulateExternalImportCommand」交给调用方。
    // 系统意义：「SimulateImportRequest.toCommand(String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public SimulateExternalImportCommand toCommand(String fallbackBatchId) {
        String batchId =
                simulationBatchId == null || simulationBatchId.isBlank()
                        ? fallbackBatchId
                        : simulationBatchId;
        return new SimulateExternalImportCommand(
                count,
                scenario,
                riskLevelHint,
                initiatorRoleHint,
                currentActorId,
                counterpartyActorId,
                batchId);
    }
}
