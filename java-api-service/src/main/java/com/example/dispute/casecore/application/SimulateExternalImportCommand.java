/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义Simulate外部导入跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;

// 所属模块：【案件核心与导入 / 应用编排层】类型「SimulateExternalImportCommand」。
// 类型职责：定义Simulate外部导入跨层传递时使用的不可变数据契约；本类型显式提供 「SimulateExternalImportCommand」、「SimulateExternalImportCommand」。
// 协作关系：主要由 「SimulateImportRequest.toCommand」、「DisputeImportServiceIntegrationTest.simulationCommand」、「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary」、「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SimulateExternalImportCommand(
        int count,
        String scenario,
        RiskLevel riskLevelHint,
        ActorRole initiatorRoleHint,
        String currentActorId,
        String counterpartyActorId,
        String simulationBatchId) {

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String)」。
    // 具体功能：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String)」：使用 「count」(int)、「scenario」(String)、「riskLevelHint」(RiskLevel)、「initiatorRoleHint」(ActorRole)、「currentActorId」(String)、「counterpartyActorId」(String) 初始化「SimulateExternalImportCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String)」的上游创建点包括 「SimulateImportRequest.toCommand」、「DisputeImportServiceIntegrationTest.simulationCommand」、「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary」、「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay」。
    // 下游影响：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public SimulateExternalImportCommand(
            int count,
            String scenario,
            RiskLevel riskLevelHint,
            ActorRole initiatorRoleHint,
            String currentActorId,
            String counterpartyActorId) {
        this(
                count,
                scenario,
                riskLevelHint,
                initiatorRoleHint,
                currentActorId,
                counterpartyActorId,
                "default");
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String,String)」。
    // 具体功能：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String,String)」：在不可变「SimulateExternalImportCommand」写入组件前校验 「count」(int)、「scenario」(String)、「riskLevelHint」(RiskLevel)、「initiatorRoleHint」(ActorRole)、「currentActorId」(String)、「counterpartyActorId」(String)、「simulationBatchId」(String)，非法输入会抛出 「IllegalArgumentException」；并通过 「DemoImportActors.requireSimulationParties」 做标准化或防御性复制。
    // 上游调用：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String,String)」的上游创建点包括 「SimulateImportRequest.toCommand」、「DisputeImportServiceIntegrationTest.simulationCommand」、「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary」、「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay」。
    // 下游影响：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String,String)」向下依次触达 「DemoImportActors.requireSimulationParties」。
    // 系统意义：「SimulateExternalImportCommand.SimulateExternalImportCommand(int,String,RiskLevel,ActorRole,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public SimulateExternalImportCommand {
        if (count != 1) {
            throw new IllegalArgumentException("count must be 1");
        }
        if (scenario == null || scenario.isBlank()) {
            scenario = "履约争议订单";
        }
        if (riskLevelHint == null) {
            riskLevelHint = RiskLevel.MEDIUM;
        }
        if (initiatorRoleHint != ActorRole.USER && initiatorRoleHint != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("initiatorRoleHint must be USER or MERCHANT");
        }
        if (currentActorId == null || currentActorId.isBlank()) {
            throw new IllegalArgumentException("currentActorId must not be blank");
        }
        if (counterpartyActorId == null || counterpartyActorId.isBlank()) {
            throw new IllegalArgumentException("counterpartyActorId must not be blank");
        }
        DemoImportActors.requireSimulationParties(
                initiatorRoleHint, currentActorId, counterpartyActorId);
        if (simulationBatchId == null || simulationBatchId.isBlank()) {
            simulationBatchId = "default";
        }
    }
}
