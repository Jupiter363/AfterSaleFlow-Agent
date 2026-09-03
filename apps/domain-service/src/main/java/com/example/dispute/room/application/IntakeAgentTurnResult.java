/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待Agent轮次跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeAgentTurnResult」。
// 类型职责：定义接待Agent轮次跨层传递时使用的不可变数据契约；本类型显式提供 「IntakeAgentTurnResult」。
// 协作关系：主要由 「IntakeAgentTurnService.degraded」、「IntakeAgentTurnServiceTest.result」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeAgentTurnResult(
        @JsonProperty("room_utterance") String roomUtterance,
        @JsonProperty("dossier_patch") JsonNode dossierPatch,
        @JsonProperty("scroll_snapshot") JsonNode scrollSnapshot,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("memory_frame") JsonNode memoryFrame,
        @JsonProperty("admission_recommendation") String admissionRecommendation,
        @JsonProperty("missing_fields") List<String> missingFields,
        @JsonProperty("knowledge_query_intent") boolean knowledgeQueryIntent,
        @JsonProperty("knowledge_answer_mode") String knowledgeAnswerMode,
        double confidence) {

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnResult.IntakeAgentTurnResult(String,JsonNode,JsonNode,JsonNode,JsonNode,String,List,boolean,String,double)」。
    // 具体功能：「IntakeAgentTurnResult.IntakeAgentTurnResult(String,JsonNode,JsonNode,JsonNode,JsonNode,String,List,boolean,String,double)」：在不可变「IntakeAgentTurnResult」写入组件前校验 「roomUtterance」(String)、「dossierPatch」(JsonNode)、「scrollSnapshot」(JsonNode)、「canvasOperations」(JsonNode)、「memoryFrame」(JsonNode)、「admissionRecommendation」(String)、「missingFields」(List)、「knowledgeQueryIntent」(boolean)、「knowledgeAnswerMode」(String)、「confidence」(double)，并统一规范 record 组件值。
    // 上游调用：「IntakeAgentTurnResult.IntakeAgentTurnResult(String,JsonNode,JsonNode,JsonNode,JsonNode,String,List,boolean,String,double)」的上游创建点包括 「IntakeAgentTurnService.degraded」、「IntakeAgentTurnServiceTest.result」。
    // 下游影响：「IntakeAgentTurnResult.IntakeAgentTurnResult(String,JsonNode,JsonNode,JsonNode,JsonNode,String,List,boolean,String,double)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeAgentTurnResult.IntakeAgentTurnResult(String,JsonNode,JsonNode,JsonNode,JsonNode,String,List,boolean,String,double)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public IntakeAgentTurnResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
