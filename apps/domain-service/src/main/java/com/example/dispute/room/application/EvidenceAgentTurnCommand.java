/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义证据Agent轮次跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAgentTurnCommand」。
// 类型职责：定义证据Agent轮次跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceAgentTurnCommand」。
// 协作关系：主要由 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「RestClientEvidenceAgentTurnClientTest.command」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceAgentTurnCommand(
        @JsonProperty("context_envelope") EvidenceContextEnvelopeV1 contextEnvelope,
        @JsonProperty("agent_context") AgentInvocationContext agentContext) {

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnCommand.EvidenceAgentTurnCommand(EvidenceContextEnvelopeV1,AgentInvocationContext)」。
    // 具体功能：「EvidenceAgentTurnCommand.EvidenceAgentTurnCommand(EvidenceContextEnvelopeV1,AgentInvocationContext)」：在不可变「EvidenceAgentTurnCommand」写入组件前校验 「contextEnvelope」(EvidenceContextEnvelopeV1)、「agentContext」(AgentInvocationContext)，并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「EvidenceAgentTurnCommand.EvidenceAgentTurnCommand(EvidenceContextEnvelopeV1,AgentInvocationContext)」的上游创建点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「RestClientEvidenceAgentTurnClientTest.command」。
    // 下游影响：「EvidenceAgentTurnCommand.EvidenceAgentTurnCommand(EvidenceContextEnvelopeV1,AgentInvocationContext)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「EvidenceAgentTurnCommand.EvidenceAgentTurnCommand(EvidenceContextEnvelopeV1,AgentInvocationContext)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceAgentTurnCommand {
        Objects.requireNonNull(contextEnvelope, "contextEnvelope must not be null");
        Objects.requireNonNull(agentContext, "agentContext must not be null");
    }
}
