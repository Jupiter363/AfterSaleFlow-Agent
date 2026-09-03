/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义证据Agent轮次外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「run」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAgentTurnClient」。
// 类型职责：定义证据Agent轮次外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「run」。
// 协作关系：主要由 「EvidenceAgentTurnService.safeRun」、「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceAgentTurnClient {
    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」。
    // 具体功能：「EvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」：定义「EvidenceAgentTurnClient」端口方法：接收 「command」(EvidenceAgentTurnCommand)、「traceId」(String)、「requestId」(String)，返回「EvidenceAgentTurnResult」；具体副作用由 「RestClientEvidenceAgentTurnClient」 承担。
    // 上游调用：「EvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」的上游调用点包括 「EvidenceAgentTurnService.safeRun」、「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」。
    // 下游影响：「EvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」的下游由 「RestClientEvidenceAgentTurnClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」负责主链路中的“证据Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    EvidenceAgentTurnResult run(
            EvidenceAgentTurnCommand command, String traceId, String requestId);
}
