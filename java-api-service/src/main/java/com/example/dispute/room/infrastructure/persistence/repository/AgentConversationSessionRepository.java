/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明Agent会话会话在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「AgentConversationSessionRepository」。
// 类型职责：声明Agent会话会话在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」。
// 协作关系：主要由 「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」、「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentConversationSessionRepository
        extends JpaRepository<AgentConversationSessionEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「AgentConversationSessionRepository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(String,String,RoomType,String,ActorRole,String,String)」。
    // 具体功能：「AgentConversationSessionRepository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(String,String,RoomType,String,ActorRole,String,String)」：声明按Tenant标识、案件标识、房间类型、操作者标识、操作者角色、Agent键、PromptProfile标识访问Agent会话会话的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentConversationSessionEntity>」返回。
    // 上游调用：「AgentConversationSessionRepository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(String,String,RoomType,String,ActorRole,String,String)」的上游调用点包括 「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」、「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」。
    // 下游影响：「AgentConversationSessionRepository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(String,String,RoomType,String,ActorRole,String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentConversationSessionRepository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(String,String,RoomType,String,ActorRole,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AgentConversationSessionEntity>
            findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                    String tenantId,
                    String caseId,
                    RoomType roomType,
                    String actorId,
                    ActorRole actorRole,
                    String agentKey,
                    String promptProfileId);
}
