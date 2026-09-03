/*
 * 所属模块：房间协作与权限。
 * 文件职责：承载Agent会话解析器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「resolve」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【房间协作与权限 / 应用编排层】类型「AgentSessionResolver」。
// 类型职责：承载Agent会话解析器在当前业务模块中的规则与协作边界；本类型显式提供 「AgentSessionResolver」、「resolve」、「initialize」、「find」。
// 协作关系：主要由 「EvidenceAgentTurnService.resolveSession」、「IntakeAgentTurnService.resolveSession」、「RoomTurnMemoryQueryService.latestAgentMemory」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentSessionResolver {

    private final AgentConversationSessionRepository repository;
    private final AgentSessionInitializer initializer;

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionResolver.AgentSessionResolver(AgentConversationSessionRepository,AgentSessionInitializer)」。
    // 具体功能：「AgentSessionResolver.AgentSessionResolver(AgentConversationSessionRepository,AgentSessionInitializer)」：通过构造器接收 「repository」(AgentConversationSessionRepository)、「initializer」(AgentSessionInitializer) 并保存为「AgentSessionResolver」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentSessionResolver.AgentSessionResolver(AgentConversationSessionRepository,AgentSessionInitializer)」的上游创建点包括 「AgentConversationSessionResolverTest.setUp」。
    // 下游影响：「AgentSessionResolver.AgentSessionResolver(AgentConversationSessionRepository,AgentSessionInitializer)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentSessionResolver.AgentSessionResolver(AgentConversationSessionRepository,AgentSessionInitializer)」负责主链路中的“Agent会话解析器”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentSessionResolver(
            AgentConversationSessionRepository repository,
            AgentSessionInitializer initializer) {
        this.repository = repository;
        this.initializer = initializer;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionResolver.resolve(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「AgentSessionResolver.resolve(CaseAccessSessionEntity,RoomType,String,String,String)」：解析Agent会话会话：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「find」、「initialize」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentSessionResolver.resolve(CaseAccessSessionEntity,RoomType,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.resolveSession」、「IntakeAgentTurnService.resolveSession」、「RoomTurnMemoryQueryService.latestAgentMemory」、「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession」。
    // 下游影响：「AgentSessionResolver.resolve(CaseAccessSessionEntity,RoomType,String,String,String)」向下依次触达 「find」、「initialize」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentSessionResolver.resolve(CaseAccessSessionEntity,RoomType,String,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentConversationSessionEntity resolve(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return find(accessSession, roomType, agentKey, promptProfileId)
                .orElseGet(
                        () ->
                                initialize(
                                        accessSession,
                                        roomType,
                                        agentKey,
                                        promptProfileId,
                                        memoryPolicyId));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionResolver.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「AgentSessionResolver.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」：初始化Agent会话会话；实际协作者为 「TransactionSynchronizationManager.isCurrentTransactionReadOnly」、「initializer.initializeInNewTransaction」、「initializer.initializeInCurrentTransaction」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentSessionResolver.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」的上游调用点包括 「AgentSessionResolver.resolve」。
    // 下游影响：「AgentSessionResolver.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」向下依次触达 「TransactionSynchronizationManager.isCurrentTransactionReadOnly」、「initializer.initializeInNewTransaction」、「initializer.initializeInCurrentTransaction」；计算结果以「AgentConversationSessionEntity」交给调用方。
    // 系统意义：「AgentSessionResolver.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」负责主链路中的“Agent会话会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private AgentConversationSessionEntity initialize(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return initializer.initializeInNewTransaction(
                    accessSession,
                    roomType,
                    agentKey,
                    promptProfileId,
                    memoryPolicyId);
        }
        return initializer.initializeInCurrentTransaction(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionResolver.find(CaseAccessSessionEntity,RoomType,String,String)」。
    // 具体功能：「AgentSessionResolver.find(CaseAccessSessionEntity,RoomType,String,String)」：查找可选；实际协作者为 「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」，最终返回「Optional<AgentConversationSessionEntity>」。
    // 上游调用：「AgentSessionResolver.find(CaseAccessSessionEntity,RoomType,String,String)」的上游调用点包括 「AgentSessionResolver.resolve」。
    // 下游影响：「AgentSessionResolver.find(CaseAccessSessionEntity,RoomType,String,String)」向下依次触达 「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」；计算结果以「Optional<AgentConversationSessionEntity>」交给调用方。
    // 系统意义：「AgentSessionResolver.find(CaseAccessSessionEntity,RoomType,String,String)」负责主链路中的“可选”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private Optional<AgentConversationSessionEntity> find(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId) {
        return repository
                .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                        accessSession.getTenantId(),
                        accessSession.getCaseId(),
                        roomType,
                        accessSession.getActorId(),
                        accessSession.getActorRole(),
                        agentKey,
                        promptProfileId);
    }
}
