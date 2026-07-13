/*
 * 所属模块：共享小法庭。
 * 文件职责：定义庭审法庭Agent外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「generateRoundTurn」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingCourtAgentClient」。
// 类型职责：定义庭审法庭Agent外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「generateRoundTurn」。
// 协作关系：主要由 「HearingCourtOrchestrator.safeGenerate」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」、「HearingCourtOrchestratorTest.afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingCourtAgentClient {

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」。
    // 具体功能：「HearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」：定义「HearingCourtAgentClient」端口方法：接收 「command」(HearingCourtAgentCommand)、「traceId」(String)、「requestId」(String)，返回「HearingCourtAgentResult」；具体副作用由 「RestClientHearingCourtAgentClient」 承担。
    // 上游调用：「HearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」的上游调用点包括 「HearingCourtOrchestrator.safeGenerate」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」、「HearingCourtOrchestratorTest.invokesTheRemoteCourtAgentOutsideAnyActiveCourtTransaction」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」。
    // 下游影响：「HearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」的下游由 「RestClientHearingCourtAgentClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」负责主链路中的“轮次轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    HearingCourtAgentResult generateRoundTurn(
            HearingCourtAgentCommand command, String traceId, String requestId);
}
