/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义导入争议跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeLobbySeed;
import java.time.OffsetDateTime;
import java.util.Objects;

// 所属模块：【案件核心与导入 / 应用编排层】类型「ImportDisputeCommand」。
// 类型职责：定义导入争议跨层传递时使用的不可变数据契约；本类型显式提供 「ImportDisputeCommand」、「ImportDisputeCommand」、「requireText」。
// 协作关系：主要由 「DemoDisputeSeeder.command」、「ExternalCaseImportTransactionService.simulatedCommand」、「ImportDisputeRequest.toCommand」、「DisputeImportServiceIntegrationTest.command」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ImportDisputeCommand(
        String sourceSystem,
        String externalCaseReference,
        String orderReference,
        String afterSalesReference,
        String logisticsReference,
        String userId,
        String merchantId,
        String initiatorRole,
        String disputeType,
        String title,
        String description,
        RiskLevel riskLevel,
        CaseStatus caseStatus,
        String currentRoom,
        OffsetDateTime currentDeadlineAt,
        String requestedOutcomeHint,
        IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed) {

    // 所属模块：【案件核心与导入 / 应用编排层】「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,ClaimResolutionSeed,RespondentAttitudeSeed)」。
    // 具体功能：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,ClaimResolutionSeed,RespondentAttitudeSeed)」：在不可变「ImportDisputeCommand」写入组件前校验 「sourceSystem」(String)、「externalCaseReference」(String)、「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「userId」(String)、「merchantId」(String)、「initiatorRole」(String)、「disputeType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)、「caseStatus」(CaseStatus)、「currentRoom」(String)、「currentDeadlineAt」(OffsetDateTime)、「requestedOutcomeHint」(String)、「claimResolutionSeed」(ClaimResolutionSeed)、「respondentAttitudeSeed」(RespondentAttitudeSeed)，并通过 「DemoImportActors.requireImportedParties」、「Objects.requireNonNull」、「claimResolutionSeed.requestedResolution」、「requireText」 做标准化或防御性复制。
    // 上游调用：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,ClaimResolutionSeed,RespondentAttitudeSeed)」的上游创建点包括 「ImportDisputeRequest.toCommand」、「DemoDisputeSeeder.command」、「ExternalCaseImportTransactionService.simulatedCommand」、「DisputeImportServiceIntegrationTest.command」。
    // 下游影响：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,ClaimResolutionSeed,RespondentAttitudeSeed)」向下依次触达 「DemoImportActors.requireImportedParties」、「Objects.requireNonNull」、「claimResolutionSeed.requestedResolution」、「requireText」。
    // 系统意义：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,ClaimResolutionSeed,RespondentAttitudeSeed)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ImportDisputeCommand {
        requireText(sourceSystem, "sourceSystem");
        requireText(externalCaseReference, "externalCaseReference");
        requireText(orderReference, "orderReference");
        requireText(userId, "userId");
        requireText(merchantId, "merchantId");
        DemoImportActors.requireImportedParties(userId, merchantId);
        requireText(initiatorRole, "initiatorRole");
        requireText(disputeType, "disputeType");
        requireText(title, "title");
        requireText(description, "description");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(caseStatus, "caseStatus must not be null");
        requireText(currentRoom, "currentRoom");
        if ((requestedOutcomeHint == null || requestedOutcomeHint.isBlank())
                && claimResolutionSeed != null
                && claimResolutionSeed.requestedResolution() != null
                && !claimResolutionSeed.requestedResolution().isBlank()) {
            requestedOutcomeHint = claimResolutionSeed.requestedResolution();
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime)」。
    // 具体功能：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime)」：使用 「sourceSystem」(String)、「externalCaseReference」(String)、「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「userId」(String)、「merchantId」(String)、「initiatorRole」(String)、「disputeType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)、「caseStatus」(CaseStatus)、「currentRoom」(String)、「currentDeadlineAt」(OffsetDateTime) 初始化「ImportDisputeCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime)」的上游创建点包括 「ImportDisputeRequest.toCommand」、「DemoDisputeSeeder.command」、「ExternalCaseImportTransactionService.simulatedCommand」、「DisputeImportServiceIntegrationTest.command」。
    // 下游影响：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ImportDisputeCommand.ImportDisputeCommand(String,String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ImportDisputeCommand(
            String sourceSystem,
            String externalCaseReference,
            String orderReference,
            String afterSalesReference,
            String logisticsReference,
            String userId,
            String merchantId,
            String initiatorRole,
            String disputeType,
            String title,
            String description,
            RiskLevel riskLevel,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt) {
        this(
                sourceSystem,
                externalCaseReference,
                orderReference,
                afterSalesReference,
                logisticsReference,
                userId,
                merchantId,
                initiatorRole,
                disputeType,
                title,
                description,
                riskLevel,
                caseStatus,
                currentRoom,
                currentDeadlineAt,
                null,
                null,
                null);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ImportDisputeCommand.requireText(String,String)」。
    // 具体功能：「ImportDisputeCommand.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「ImportDisputeCommand.requireText(String,String)」的上游调用点包括 「ImportDisputeCommand.ImportDisputeCommand」。
    // 下游影响：「ImportDisputeCommand.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ImportDisputeCommand.requireText(String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
