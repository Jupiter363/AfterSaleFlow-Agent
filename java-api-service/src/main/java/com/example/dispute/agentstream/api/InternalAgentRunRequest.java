/*
 * 所属模块：Agent 流式运行。
 * 文件职责：定义内部Agent运行跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.api;

import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

// 所属模块：【Agent 流式运行 / HTTP 接口层】类型「InternalAgentRunRequest」。
// 类型职责：定义内部Agent运行跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「InternalAgentRunController.start」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record InternalAgentRunRequest(
        @NotBlank @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
        @Size(max = 64) String roomId,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String operation,
        @NotNull JsonNode request,
        @Size(max = 10) List<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String> audienceRoles,
        @Size(max = 10) List<@Size(max = 128) String> audienceActorIds,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                String idempotencyKey) {

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「InternalAgentRunRequest.toCommand(String,String,String)」。
    // 具体功能：「InternalAgentRunRequest.toCommand(String,String,String)」：转换命令，最终返回「AgentRunStartCommand」。
    // 上游调用：「InternalAgentRunRequest.toCommand(String,String,String)」的上游调用点包括 「InternalAgentRunController.start」。
    // 下游影响：「InternalAgentRunRequest.toCommand(String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「AgentRunStartCommand」交给调用方。
    // 系统意义：「InternalAgentRunRequest.toCommand(String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    AgentRunStartCommand toCommand(
            String traceId, String requestId, String actorId) {
        return new AgentRunStartCommand(
                caseId,
                roomId,
                operation,
                request,
                audienceRoles,
                audienceActorIds,
                idempotencyKey,
                traceId,
                requestId,
                actorId);
    }
}
