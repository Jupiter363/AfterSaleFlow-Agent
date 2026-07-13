/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义Create争议跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Public intake contract for the final dispute API. */
// 所属模块：【案件核心与导入 / HTTP 接口层】类型「CreateDisputeRequest」。
// 类型职责：定义Create争议跨层传递时使用的不可变数据契约；本类型显式提供 「CreateDisputeRequest」、「toCommand」。
// 协作关系：主要由 「DisputeController.create」、「CreateDisputeRequestTest.requestWithClaimSeed」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CreateDisputeRequest(
        @NotNull ActorRole initiatorRole,
        @Size(max = 64) String orderReference,
        @Size(max = 64) String afterSalesReference,
        @Size(max = 64) String logisticsReference,
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String merchantId,
        @NotBlank @Size(max = 4000) String description,
        @JsonProperty("claim_resolution_seed")
                @Valid IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed")
                @Valid IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed,
        @Size(max = 50) List<@Size(max = 128) String> attachmentIds,
        @Size(max = 32) String channel) {

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequest.CreateDisputeRequest(ActorRole,String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed,List,String)」。
    // 具体功能：「CreateDisputeRequest.CreateDisputeRequest(ActorRole,String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed,List,String)」：在不可变「CreateDisputeRequest」写入组件前校验 「initiatorRole」(ActorRole)、「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「userId」(String)、「merchantId」(String)、「description」(String)、「claimResolutionSeed」(ClaimResolutionSeed)、「respondentAttitudeSeed」(RespondentAttitudeSeed)、「attachmentIds」(List)、「channel」(String)，并统一规范 record 组件值。
    // 上游调用：「CreateDisputeRequest.CreateDisputeRequest(ActorRole,String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed,List,String)」的上游创建点包括 「CreateDisputeRequestTest.requestWithClaimSeed」。
    // 下游影响：「CreateDisputeRequest.CreateDisputeRequest(ActorRole,String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed,List,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CreateDisputeRequest.CreateDisputeRequest(ActorRole,String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed,List,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public CreateDisputeRequest {
        attachmentIds =
                attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        channel = channel == null || channel.isBlank() ? "WEB" : channel;
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequest.toCommand()」。
    // 具体功能：「CreateDisputeRequest.toCommand()」：转换命令，最终返回「CreateCaseCommand」。
    // 上游调用：「CreateDisputeRequest.toCommand()」的上游调用点包括 「DisputeController.create」。
    // 下游影响：「CreateDisputeRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CreateCaseCommand」交给调用方。
    // 系统意义：「CreateDisputeRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    CreateCaseCommand toCommand() {
        return new CreateCaseCommand(
                orderReference,
                afterSalesReference,
                logisticsReference,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel,
                initiatorRole,
                claimResolutionSeed,
                respondentAttitudeSeed);
    }
}
