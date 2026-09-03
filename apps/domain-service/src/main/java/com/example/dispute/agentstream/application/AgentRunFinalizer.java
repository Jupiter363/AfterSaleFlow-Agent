/*
 * 所属模块：Agent 流式运行。
 * 文件职责：把Agent运行终态处理器从候选结果收敛为正式业务事实。
 * 业务链路：核心入口/契约为 「supports」、「finalizeResult」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Optional operation-specific atomic persistence hook.  Implementations run inside the same
 * transaction that changes the run to COMPLETED; throwing rolls back both changes.
 */
// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunFinalizer」。
// 类型职责：把Agent运行终态处理器从候选结果收敛为正式业务事实；本类型显式提供 「supports」、「finalizeResult」。
// 协作关系：由 「HearingCourtOrchestrator」、「EvidenceAgentTurnService」、「IntakeAgentTurnService」、「CaseFulfillmentDisputeActivitiesImpl」 实现。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentRunFinalizer {

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunFinalizer.supports(String)」。
    // 具体功能：「AgentRunFinalizer.supports(String)」：定义「AgentRunFinalizer」端口方法：接收 「operation」(String)，返回「boolean」；具体副作用由 「HearingCourtOrchestrator」、「EvidenceAgentTurnService」、「IntakeAgentTurnService」 承担。
    // 上游调用：「AgentRunFinalizer.supports(String)」由使用「AgentRunFinalizer」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunFinalizer.supports(String)」的下游由 「HearingCourtOrchestrator」、「EvidenceAgentTurnService」、「IntakeAgentTurnService」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunFinalizer.supports(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean supports(String operation);

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunFinalizer.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「AgentRunFinalizer.finalizeResult(AgentRunFinalizationContext,JsonNode)」：定义「AgentRunFinalizer」端口方法：接收 「context」(AgentRunFinalizationContext)、「result」(JsonNode)，返回「void」；具体副作用由 「HearingCourtOrchestrator」、「EvidenceAgentTurnService」、「IntakeAgentTurnService」 承担。
    // 上游调用：「AgentRunFinalizer.finalizeResult(AgentRunFinalizationContext,JsonNode)」由使用「AgentRunFinalizer」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunFinalizer.finalizeResult(AgentRunFinalizationContext,JsonNode)」的下游由 「HearingCourtOrchestrator」、「EvidenceAgentTurnService」、「IntakeAgentTurnService」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunFinalizer.finalizeResult(AgentRunFinalizationContext,JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void finalizeResult(AgentRunFinalizationContext context, JsonNode result);
}
