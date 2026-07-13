/*
 * 所属模块：共享小法庭。
 * 文件职责：定义庭审法庭Agent跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import java.util.List;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingCourtAgentCommand」。
// 类型职责：定义庭审法庭Agent跨层传递时使用的不可变数据契约；本类型显式提供 「HearingCourtAgentCommand」。
// 协作关系：主要由 「HearingCourtOrchestrator.command」、「RestClientHearingCourtAgentClientTest.postsRoundTurnToPythonAgentAndValidatesJudgeResult」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingCourtAgentCommand(
        String caseId,
        String workflowId,
        String orderId,
        String afterSaleId,
        String logisticsId,
        String disputeType,
        String title,
        String caseDescription,
        String riskLevel,
        int roundNo,
        int dossierVersion,
        boolean finalRound,
        String roundStatus,
        String stopReason,
        String roundSummaryJson,
        String courtroomContextJson,
        List<PartySubmission> partySubmissions) {

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtAgentCommand.HearingCourtAgentCommand(String,String,String,String,String,String,String,String,String,int,int,boolean,String,String,String,String,List)」。
    // 具体功能：「HearingCourtAgentCommand.HearingCourtAgentCommand(String,String,String,String,String,String,String,String,String,int,int,boolean,String,String,String,String,List)」：在不可变「HearingCourtAgentCommand」写入组件前校验 「caseId」(String)、「workflowId」(String)、「orderId」(String)、「afterSaleId」(String)、「logisticsId」(String)、「disputeType」(String)、「title」(String)、「caseDescription」(String)、「riskLevel」(String)、「roundNo」(int)、「dossierVersion」(int)、「finalRound」(boolean)、「roundStatus」(String)、「stopReason」(String)、「roundSummaryJson」(String)、「courtroomContextJson」(String)、「partySubmissions」(List)，并统一规范 record 组件值。
    // 上游调用：「HearingCourtAgentCommand.HearingCourtAgentCommand(String,String,String,String,String,String,String,String,String,int,int,boolean,String,String,String,String,List)」的上游创建点包括 「HearingCourtOrchestrator.command」、「RestClientHearingCourtAgentClientTest.postsRoundTurnToPythonAgentAndValidatesJudgeResult」。
    // 下游影响：「HearingCourtAgentCommand.HearingCourtAgentCommand(String,String,String,String,String,String,String,String,String,int,int,boolean,String,String,String,String,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCourtAgentCommand.HearingCourtAgentCommand(String,String,String,String,String,String,String,String,String,int,int,boolean,String,String,String,String,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public HearingCourtAgentCommand {
        courtroomContextJson =
                courtroomContextJson == null || courtroomContextJson.isBlank()
                        ? "{}"
                        : courtroomContextJson;
        partySubmissions = partySubmissions == null ? List.of() : List.copyOf(partySubmissions);
    }

    // 所属模块：【共享小法庭 / 应用编排层】类型「PartySubmission」。
    // 类型职责：定义当事方提交跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record PartySubmission(
            String participantRole,
            String participantId,
            String submissionSource,
            String submissionJson) {}
}
