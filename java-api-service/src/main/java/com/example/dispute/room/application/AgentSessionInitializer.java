/*
 * 所属模块：房间协作与权限。
 * 文件职责：承载Agent会话初始化器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「initializeInCurrentTransaction」、「initializeInNewTransaction」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「AgentSessionInitializer」。
// 类型职责：承载Agent会话初始化器在当前业务模块中的规则与协作边界；本类型显式提供 「AgentSessionInitializer」、「initializeInCurrentTransaction」、「initializeInNewTransaction」、「initialize」、「compactUuid」。
// 协作关系：主要由 「AgentSessionResolver.initialize」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」、「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession」、「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentSessionInitializer {

    private final FulfillmentCaseRepository caseRepository;
    private final AgentConversationSessionRepository repository;

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionInitializer.AgentSessionInitializer(FulfillmentCaseRepository,AgentConversationSessionRepository)」。
    // 具体功能：「AgentSessionInitializer.AgentSessionInitializer(FulfillmentCaseRepository,AgentConversationSessionRepository)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「repository」(AgentConversationSessionRepository) 并保存为「AgentSessionInitializer」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentSessionInitializer.AgentSessionInitializer(FulfillmentCaseRepository,AgentConversationSessionRepository)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「AgentSessionInitializer.AgentSessionInitializer(FulfillmentCaseRepository,AgentConversationSessionRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentSessionInitializer.AgentSessionInitializer(FulfillmentCaseRepository,AgentConversationSessionRepository)」负责主链路中的“Agent会话初始化器”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentSessionInitializer(
            FulfillmentCaseRepository caseRepository,
            AgentConversationSessionRepository repository) {
        this.caseRepository = caseRepository;
        this.repository = repository;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionInitializer.initializeInCurrentTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「AgentSessionInitializer.initializeInCurrentTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」：初始化InCurrentTransaction：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「initialize」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentSessionInitializer.initializeInCurrentTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」的上游调用点包括 「AgentSessionResolver.initialize」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」、「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession」。
    // 下游影响：「AgentSessionInitializer.initializeInCurrentTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」向下依次触达 「initialize」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentSessionInitializer.initializeInCurrentTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentConversationSessionEntity initializeInCurrentTransaction(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return initialize(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionInitializer.initializeInNewTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「AgentSessionInitializer.initializeInNewTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」：初始化In新案件Transaction：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「initialize」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentSessionInitializer.initializeInNewTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」的上游调用点包括 「AgentSessionResolver.initialize」、「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」。
    // 下游影响：「AgentSessionInitializer.initializeInNewTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」向下依次触达 「initialize」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentSessionInitializer.initializeInNewTransaction(CaseAccessSessionEntity,RoomType,String,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentConversationSessionEntity initializeInNewTransaction(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return initialize(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionInitializer.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「AgentSessionInitializer.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」：初始化Agent会话会话：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」、「repository.save」、「AgentConversationSessionEntity.create」；处理的关键状态/协议值包括 「AGENT_SESSION_」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentSessionInitializer.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」的上游调用点包括 「AgentSessionInitializer.initializeInCurrentTransaction」、「AgentSessionInitializer.initializeInNewTransaction」。
    // 下游影响：「AgentSessionInitializer.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」、「repository.save」、「AgentConversationSessionEntity.create」；计算结果以「AgentConversationSessionEntity」交给调用方。
    // 系统意义：「AgentSessionInitializer.initialize(CaseAccessSessionEntity,RoomType,String,String,String)」负责主链路中的“Agent会话会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private AgentConversationSessionEntity initialize(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        caseRepository
                .findByIdForUpdate(accessSession.getCaseId())
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return repository
                .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                        accessSession.getTenantId(),
                        accessSession.getCaseId(),
                        roomType,
                        accessSession.getActorId(),
                        accessSession.getActorRole(),
                        agentKey,
                        promptProfileId)
                .orElseGet(
                        () ->
                                repository.save(
                                        AgentConversationSessionEntity.create(
                                                "AGENT_SESSION_" + compactUuid(),
                                                accessSession,
                                                roomType,
                                                agentKey,
                                                promptProfileId,
                                                memoryPolicyId,
                                                accessSession.getActorId())));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AgentSessionInitializer.compactUuid()」。
    // 具体功能：「AgentSessionInitializer.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AgentSessionInitializer.compactUuid()」的上游调用点包括 「AgentSessionInitializer.initialize」。
    // 下游影响：「AgentSessionInitializer.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AgentSessionInitializer.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
