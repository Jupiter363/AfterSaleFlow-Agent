/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待Agent轮次跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeAgentTurnCommand」。
// 类型职责：定义接待Agent轮次跨层传递时使用的不可变数据契约；本类型显式提供 「IntakeAgentTurnCommand」。
// 协作关系：主要由 「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.startInitialTurn」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeAgentTurnCommand(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_source") String turnSource,
        @JsonProperty("initial_case_facts") IntakeInitialCaseFacts initialCaseFacts,
        @JsonProperty("current_user_message") IntakeDialogueMessage currentUserMessage,
        @JsonProperty("recent_dialogue_messages")
                List<IntakeDialogueMessage> recentDialogueMessages,
        @JsonProperty("previous_case_detail") JsonNode previousCaseDetail,
        @JsonProperty("initiator_statement_transcript")
                List<IntakeParticipantMessage> initiatorStatementTranscript,
        @JsonProperty("agent_context") AgentInvocationContext agentContext) {

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnCommand.IntakeAgentTurnCommand(String,RoomType,String,IntakeInitialCaseFacts,IntakeDialogueMessage,List,JsonNode,List,AgentInvocationContext)」。
    // 具体功能：「IntakeAgentTurnCommand.IntakeAgentTurnCommand(String,RoomType,String,IntakeInitialCaseFacts,IntakeDialogueMessage,List,JsonNode,List,AgentInvocationContext)」：在不可变「IntakeAgentTurnCommand」写入组件前校验 「caseId」(String)、「roomType」(RoomType)、「turnSource」(String)、「initialCaseFacts」(IntakeInitialCaseFacts)、「currentUserMessage」(IntakeDialogueMessage)、「recentDialogueMessages」(List)、「previousCaseDetail」(JsonNode)、「initiatorStatementTranscript」(List)、「agentContext」(AgentInvocationContext)，并统一规范 record 组件值。
    // 上游调用：「IntakeAgentTurnCommand.IntakeAgentTurnCommand(String,RoomType,String,IntakeInitialCaseFacts,IntakeDialogueMessage,List,JsonNode,List,AgentInvocationContext)」的上游创建点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnCommand.IntakeAgentTurnCommand(String,RoomType,String,IntakeInitialCaseFacts,IntakeDialogueMessage,List,JsonNode,List,AgentInvocationContext)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeAgentTurnCommand.IntakeAgentTurnCommand(String,RoomType,String,IntakeInitialCaseFacts,IntakeDialogueMessage,List,JsonNode,List,AgentInvocationContext)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public IntakeAgentTurnCommand {
        recentDialogueMessages =
                recentDialogueMessages == null ? List.of() : List.copyOf(recentDialogueMessages);
        initiatorStatementTranscript =
                initiatorStatementTranscript == null
                        ? List.of()
                        : List.copyOf(initiatorStatementTranscript);
    }
}
