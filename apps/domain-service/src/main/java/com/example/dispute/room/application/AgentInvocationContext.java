/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义AgentInvocation上下文跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「partyPrivate」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

// 所属模块：【房间协作与权限 / 应用编排层】类型「AgentInvocationContext」。
// 类型职责：定义AgentInvocation上下文跨层传递时使用的不可变数据契约；本类型显式提供 「AgentInvocationContext」、「partyPrivate」、「compactUuid」。
// 协作关系：主要由 「EvidenceAgentTurnService.resolveSession」、「IntakeAgentTurnService.resolveSession」、「RestClientEvidenceAgentTurnClientTest.command」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AgentInvocationContext(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("actor_role") String actorRole,
        @JsonProperty("access_session_id") String accessSessionId,
        @JsonProperty("permission_level") String permissionLevel,
        @JsonProperty("permission_scopes") List<String> permissionScopes,
        @JsonProperty("agent_key") String agentKey,
        @JsonProperty("agent_invocation_id") String agentInvocationId,
        @JsonProperty("agent_session_id") String agentSessionId,
        @JsonProperty("conversation_scope") String conversationScope,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("allowed_actor_ids") List<String> allowedActorIds,
        @JsonProperty("allowed_actor_roles") List<String> allowedActorRoles,
        @JsonProperty("prompt_profile_id") String promptProfileId,
        @JsonProperty("memory_policy_id") String memoryPolicyId) {

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentInvocationContext.AgentInvocationContext(String,String,RoomType,String,String,String,String,List,String,String,String,String,String,List,List,String,String)」。
    // 具体功能：「AgentInvocationContext.AgentInvocationContext(String,String,RoomType,String,String,String,String,List,String,String,String,String,String,List,List,String,String)」：在不可变「AgentInvocationContext」写入组件前校验 「tenantId」(String)、「caseId」(String)、「roomType」(RoomType)、「actorId」(String)、「actorRole」(String)、「accessSessionId」(String)、「permissionLevel」(String)、「permissionScopes」(List)、「agentKey」(String)、「agentInvocationId」(String)、「agentSessionId」(String)、「conversationScope」(String)、「scopeType」(String)、「allowedActorIds」(List)、「allowedActorRoles」(List)、「promptProfileId」(String)、「memoryPolicyId」(String)，并统一规范 record 组件值。
    // 上游调用：「AgentInvocationContext.AgentInvocationContext(String,String,RoomType,String,String,String,String,List,String,String,String,String,String,List,List,String,String)」的上游创建点包括 「AgentInvocationContext.partyPrivate」、「RestClientEvidenceAgentTurnClientTest.command」。
    // 下游影响：「AgentInvocationContext.AgentInvocationContext(String,String,RoomType,String,String,String,String,List,String,String,String,String,String,List,List,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentInvocationContext.AgentInvocationContext(String,String,RoomType,String,String,String,String,List,String,String,String,String,String,List,List,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public AgentInvocationContext {
        permissionScopes = permissionScopes == null ? List.of() : List.copyOf(permissionScopes);
        allowedActorIds = allowedActorIds == null ? List.of() : List.copyOf(allowedActorIds);
        allowedActorRoles = allowedActorRoles == null ? List.of() : List.copyOf(allowedActorRoles);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentInvocationContext.partyPrivate(CaseAccessSessionEntity,AgentConversationSessionEntity,RoomType,String)」。
    // 具体功能：「AgentInvocationContext.partyPrivate(CaseAccessSessionEntity,AgentConversationSessionEntity,RoomType,String)」：更新当事方私有：先更新内部状态 「actorRole」；实际协作者为 「accessSession.getActorRole」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」；处理的关键状态/协议值包括 「AGENT_INVOCATION_」，最终返回「AgentInvocationContext」。
    // 上游调用：「AgentInvocationContext.partyPrivate(CaseAccessSessionEntity,AgentConversationSessionEntity,RoomType,String)」的上游调用点包括 「EvidenceAgentTurnService.resolveSession」、「IntakeAgentTurnService.resolveSession」。
    // 下游影响：「AgentInvocationContext.partyPrivate(CaseAccessSessionEntity,AgentConversationSessionEntity,RoomType,String)」向下依次触达 「accessSession.getActorRole」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」；计算结果以「AgentInvocationContext」交给调用方。
    // 系统意义：「AgentInvocationContext.partyPrivate(CaseAccessSessionEntity,AgentConversationSessionEntity,RoomType,String)」负责主链路中的“当事方私有”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public static AgentInvocationContext partyPrivate(
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            RoomType roomType,
            String scopeType) {
        ActorRole actorRole = accessSession.getActorRole();
        return new AgentInvocationContext(
                accessSession.getTenantId(),
                accessSession.getCaseId(),
                roomType,
                accessSession.getActorId(),
                actorRole.name(),
                accessSession.getId(),
                accessSession.getPermissionLevel().name(),
                accessSession.permissionScopes().stream().map(PermissionScope::name).sorted().toList(),
                agentSession.getAgentKey(),
                "AGENT_INVOCATION_" + compactUuid(),
                agentSession.getId(),
                agentSession.getConversationScope(),
                scopeType,
                List.of(accessSession.getActorId()),
                List.of(actorRole.name()),
                agentSession.getPromptProfileId(),
                agentSession.getMemoryPolicyId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentInvocationContext.compactUuid()」。
    // 具体功能：「AgentInvocationContext.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AgentInvocationContext.compactUuid()」的上游调用点包括 「AgentInvocationContext.partyPrivate」。
    // 下游影响：「AgentInvocationContext.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AgentInvocationContext.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
