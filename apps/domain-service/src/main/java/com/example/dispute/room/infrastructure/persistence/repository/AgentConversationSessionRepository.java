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
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(
            value =
                    """
                    select epoch.id
                      from case_room_epoch epoch
                      join production_runtime_room_epoch_binding target_binding
                        on target_binding.epoch_id = epoch.id
                       and target_binding.tenant_surrogate = epoch.tenant_surrogate
                       and target_binding.case_id = epoch.case_id
                       and target_binding.room_type = epoch.room_type
                       and target_binding.room_epoch = epoch.room_epoch
                       and target_binding.room_fencing_token = epoch.fencing_token
                     where epoch.case_id = :caseId
                       and epoch.room_type = 'INTAKE'
                       and epoch.writer_mode = 'TEMPORAL'
                       and epoch.lifecycle_status = 'ACTIVE'
                       and target_binding.execution_lane = 'PRODUCTION'
                     order by epoch.room_epoch desc
                    """,
            nativeQuery = true)
    List<String> findActiveTargetIntakeRouteIds(@Param("caseId") String caseId);

    @Query(
            value =
                    """
                    select session.*
                      from case_room_epoch epoch
                      join production_runtime_room_epoch_binding target_binding
                        on target_binding.epoch_id = epoch.id
                       and target_binding.tenant_surrogate = epoch.tenant_surrogate
                       and target_binding.case_id = epoch.case_id
                       and target_binding.room_type = epoch.room_type
                       and target_binding.room_epoch = epoch.room_epoch
                       and target_binding.room_fencing_token = epoch.fencing_token
                      join case_intake_graph_thread_binding binding
                        on binding.tenant_surrogate = epoch.tenant_surrogate
                       and binding.case_id = epoch.case_id
                       and binding.room_type = epoch.room_type
                       and binding.room_epoch = epoch.room_epoch
                       and binding.fencing_token = epoch.fencing_token
                       and binding.graph_key = epoch.graph_key
                       and binding.graph_version = epoch.graph_version
                       and binding.checkpoint_schema_version = epoch.checkpoint_schema_version
                      join agent_conversation_session session
                        on session.id = binding.agent_session_id
                       and session.tenant_id = binding.tenant_surrogate
                       and session.case_id = binding.case_id
                       and session.room_type = binding.room_type
                       and session.actor_id = binding.actor_id
                       and session.actor_role = binding.actor_role
                       and session.agent_key = :agentKey
                       and session.prompt_profile_id = binding.prompt_version
                       and session.memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
                       and session.status = 'ACTIVE'
                      join case_access_session access
                        on access.id = session.access_session_id
                       and access.tenant_id = binding.tenant_surrogate
                       and access.case_id = binding.case_id
                       and access.actor_id = binding.actor_id
                       and access.actor_role = binding.actor_role
                       and access.status = 'ACTIVE'
                       and access.permission_level = case
                           when :actorRole = 'USER' then 'PARTY_USER'
                           when :actorRole = 'MERCHANT' then 'PARTY_MERCHANT'
                           else '__DENY__'
                       end
                      join case_participant participant
                        on participant.case_id = binding.case_id
                       and participant.actor_id = binding.actor_id
                       and participant.participant_role = binding.actor_role
                       and participant.participant_status = 'ACTIVE'
                     where epoch.case_id = :caseId
                       and epoch.room_type = 'INTAKE'
                       and epoch.writer_mode = 'TEMPORAL'
                       and epoch.lifecycle_status = 'ACTIVE'
                       and epoch.provisioning_status = 'READY'
                       and target_binding.execution_lane = 'PRODUCTION'
                       and binding.actor_id = :actorId
                       and binding.actor_role = :actorRole
                       and binding.audience = :actorRole
                       and binding.writer_mode = 'TEMPORAL'
                       and binding.registration_status = 'REGISTERED'
                    """,
            nativeQuery = true)
    List<AgentConversationSessionEntity> findCurrentRegisteredTargetIntakePartySession(
            @Param("caseId") String caseId,
            @Param("actorId") String actorId,
            @Param("actorRole") String actorRole,
            @Param("agentKey") String agentKey);
}
