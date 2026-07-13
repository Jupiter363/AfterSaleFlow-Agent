/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：定义Create案件跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.IntakeLobbySeed;
import java.util.List;

// 所属模块：【案件受理兼容链路 / 应用编排层】类型「CreateCaseCommand」。
// 类型职责：定义Create案件跨层传递时使用的不可变数据契约；本类型显式提供 「CreateCaseCommand」、「CreateCaseCommand」、「CreateCaseCommand」。
// 协作关系：主要由 「CreateCaseRequest.toCommand」、「CreateDisputeRequest.toCommand」、「CaseApplicationServiceTest.command」、「CaseApplicationServiceTest.commandWithClaim」 使用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CreateCaseCommand(
        String orderId,
        String afterSaleId,
        String logisticsId,
        String userId,
        String merchantId,
        String description,
        List<String> attachmentIds,
        String channel,
        ActorRole initiatorRole,
        IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed) {

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole,ClaimResolutionSeed,RespondentAttitudeSeed)」。
    // 具体功能：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole,ClaimResolutionSeed,RespondentAttitudeSeed)」：在不可变「CreateCaseCommand」写入组件前校验 「orderId」(String)、「afterSaleId」(String)、「logisticsId」(String)、「userId」(String)、「merchantId」(String)、「description」(String)、「attachmentIds」(List)、「channel」(String)、「initiatorRole」(ActorRole)、「claimResolutionSeed」(ClaimResolutionSeed)、「respondentAttitudeSeed」(RespondentAttitudeSeed)，并统一规范 record 组件值。
    // 上游调用：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole,ClaimResolutionSeed,RespondentAttitudeSeed)」的上游创建点包括 「CreateDisputeRequest.toCommand」、「CreateCaseRequest.toCommand」、「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator」、「CaseApplicationServiceTest.command」。
    // 下游影响：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole,ClaimResolutionSeed,RespondentAttitudeSeed)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole,ClaimResolutionSeed,RespondentAttitudeSeed)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public CreateCaseCommand {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        initiatorRole = initiatorRole == null ? ActorRole.USER : initiatorRole;
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String)」。
    // 具体功能：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String)」：使用 「orderId」(String)、「afterSaleId」(String)、「logisticsId」(String)、「userId」(String)、「merchantId」(String)、「description」(String)、「attachmentIds」(List)、「channel」(String) 初始化「CreateCaseCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String)」的上游创建点包括 「CreateDisputeRequest.toCommand」、「CreateCaseRequest.toCommand」、「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator」、「CaseApplicationServiceTest.command」。
    // 下游影响：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CreateCaseCommand(
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String description,
            List<String> attachmentIds,
            String channel) {
        this(
                orderId,
                afterSaleId,
                logisticsId,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel,
                ActorRole.USER,
                null,
                null);
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole)」。
    // 具体功能：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole)」：使用 「orderId」(String)、「afterSaleId」(String)、「logisticsId」(String)、「userId」(String)、「merchantId」(String)、「description」(String)、「attachmentIds」(List)、「channel」(String)、「initiatorRole」(ActorRole) 初始化「CreateCaseCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole)」的上游创建点包括 「CreateDisputeRequest.toCommand」、「CreateCaseRequest.toCommand」、「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator」、「CaseApplicationServiceTest.command」。
    // 下游影响：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CreateCaseCommand.CreateCaseCommand(String,String,String,String,String,String,List,String,ActorRole)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CreateCaseCommand(
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String description,
            List<String> attachmentIds,
            String channel,
            ActorRole initiatorRole) {
        this(
                orderId,
                afterSaleId,
                logisticsId,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel,
                initiatorRole,
                null,
                null);
    }
}
