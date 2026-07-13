/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义导入争议跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// 所属模块：【案件核心与导入 / HTTP 接口层】类型「ImportDisputeRequest」。
// 类型职责：定义导入争议跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「InternalDisputeImportController.importDispute」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ImportDisputeRequest(
        @NotBlank String sourceSystem,
        @NotBlank String externalCaseReference,
        @NotBlank String orderReference,
        String afterSalesReference,
        String logisticsReference,
        @NotBlank String userId,
        @NotBlank String merchantId,
        @NotBlank @Pattern(regexp = "USER|MERCHANT") String initiatorRole,
        @NotBlank String disputeType,
        @NotBlank String title,
        @NotBlank String description,
        @JsonProperty("requested_outcome_hint")
                @Pattern(
                        regexp =
                                "REFUND|RETURN_REFUND|RESHIP|REPLACE_OR_REPAIR|COMPENSATION|CANCEL_ORDER|VERIFY_OR_EXPLAIN_ONLY|OTHER|UNKNOWN")
                String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed")
                @Valid IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed")
                @Valid IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed,
        @NotNull RiskLevel riskLevel) {

    // 所属模块：【案件核心与导入 / HTTP 接口层】「ImportDisputeRequest.toCommand()」。
    // 具体功能：「ImportDisputeRequest.toCommand()」：转换命令；处理的关键状态/协议值包括 「INTAKE」，最终返回「ImportDisputeCommand」。
    // 上游调用：「ImportDisputeRequest.toCommand()」的上游调用点包括 「InternalDisputeImportController.importDispute」。
    // 下游影响：「ImportDisputeRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ImportDisputeCommand」交给调用方。
    // 系统意义：「ImportDisputeRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    ImportDisputeCommand toCommand() {
        return new ImportDisputeCommand(
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
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                requestedOutcomeHint,
                claimResolutionSeed,
                respondentAttitudeSeed);
    }
}
