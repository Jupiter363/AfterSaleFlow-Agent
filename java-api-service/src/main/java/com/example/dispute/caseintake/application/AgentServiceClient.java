/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：定义Agent外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「analyze」；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.application;

// 所属模块：【案件受理兼容链路 / 应用编排层】类型「AgentServiceClient」。
// 类型职责：定义Agent外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「analyze」。
// 协作关系：由 「RestClientAgentServiceClient」 实现。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentServiceClient {
    // 所属模块：【案件受理兼容链路 / 应用编排层】「AgentServiceClient.analyze(CreateCaseCommand,String,String)」。
    // 具体功能：「AgentServiceClient.analyze(CreateCaseCommand,String,String)」：定义「AgentServiceClient」端口方法：接收 「command」(CreateCaseCommand)、「traceId」(String)、「requestId」(String)，返回「IntakeAnalysis」；具体副作用由 「RestClientAgentServiceClient」 承担。
    // 上游调用：「AgentServiceClient.analyze(CreateCaseCommand,String,String)」由使用「AgentServiceClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentServiceClient.analyze(CreateCaseCommand,String,String)」的下游由 「RestClientAgentServiceClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentServiceClient.analyze(CreateCaseCommand,String,String)」负责主链路中的“接待Analysis”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    IntakeAnalysis analyze(CreateCaseCommand command, String traceId, String requestId);
}
