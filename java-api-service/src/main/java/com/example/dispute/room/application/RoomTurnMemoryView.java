/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义房间轮次记忆跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

// 所属模块：【房间协作与权限 / 应用编排层】类型「RoomTurnMemoryView」。
// 类型职责：定义房间轮次记忆跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RoomTurnMemoryView(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_no") int turnNo,
        @JsonProperty("agent_role") String agentRole,
        @JsonProperty("agent_response") String agentResponse,
        @JsonProperty("dossier_patch") JsonNode dossierPatch,
        @JsonProperty("scroll_snapshot") JsonNode scrollSnapshot,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("memory_frame") JsonNode memoryFrame,
        @JsonProperty("case_intake_dossier") CaseIntakeDossierView caseIntakeDossier,
        @JsonProperty("created_at") OffsetDateTime createdAt) {}
