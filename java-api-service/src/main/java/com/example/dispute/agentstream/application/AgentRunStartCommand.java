/*
 * 所属模块：Agent 流式运行。
 * 文件职责：定义Agent运行Start跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunStartCommand」。
// 类型职责：定义Agent运行Start跨层传递时使用的不可变数据契约；本类型显式提供 「AgentRunStartCommand」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「EvidenceAgentTurnService.startStreamingRun」、「HearingCourtOrchestrator.startStreamingJudgeTurn」、「IntakeAgentTurnService.startStreamingRun」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AgentRunStartCommand(
        String caseId,
        String roomId,
        String operation,
        JsonNode request,
        List<String> audienceRoles,
        List<String> audienceActorIds,
        String idempotencyKey,
        String traceId,
        String requestId,
        String actorId) {

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStartCommand.AgentRunStartCommand(String,String,String,JsonNode,List,List,String,String,String,String)」。
    // 具体功能：「AgentRunStartCommand.AgentRunStartCommand(String,String,String,JsonNode,List,List,String,String,String,String)」：在不可变「AgentRunStartCommand」写入组件前校验 「caseId」(String)、「roomId」(String)、「operation」(String)、「request」(JsonNode)、「audienceRoles」(List)、「audienceActorIds」(List)、「idempotencyKey」(String)、「traceId」(String)、「requestId」(String)、「actorId」(String)，并统一规范 record 组件值。
    // 上游调用：「AgentRunStartCommand.AgentRunStartCommand(String,String,String,JsonNode,List,List,String,String,String,String)」的上游创建点包括 「InternalAgentRunRequest.toCommand」、「HearingCourtOrchestrator.startStreamingJudgeTurn」、「ReviewCopilotStreamService.query」、「EvidenceAgentTurnService.startStreamingRun」。
    // 下游影响：「AgentRunStartCommand.AgentRunStartCommand(String,String,String,JsonNode,List,List,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunStartCommand.AgentRunStartCommand(String,String,String,JsonNode,List,List,String,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public AgentRunStartCommand {
        audienceRoles = audienceRoles == null ? List.of() : List.copyOf(audienceRoles);
        audienceActorIds =
                audienceActorIds == null ? List.of() : List.copyOf(audienceActorIds);
    }
}
