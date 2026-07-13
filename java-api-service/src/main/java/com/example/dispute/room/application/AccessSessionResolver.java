/*
 * 所属模块：房间协作与权限。
 * 文件职责：承载访问会话解析器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「resolve」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【房间协作与权限 / 应用编排层】类型「AccessSessionResolver」。
// 类型职责：承载访问会话解析器在当前业务模块中的规则与协作边界；本类型显式提供 「AccessSessionResolver」、「resolve」、「initialize」、「find」、「permissionLevelFor」、「assertPartyCanAccess」。
// 协作关系：主要由 「AgentRunStreamEventService.requireVisibleRun」、「CaseAgentRunController.active」、「CaseEventService.accessSession」、「EvidenceAgentTurnService.resolveSession」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AccessSessionResolver {

    public static final String DEFAULT_TENANT = "default";

    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final CaseAccessSessionRepository accessSessionRepository;
    private final AccessSessionInitializer accessSessionInitializer;

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.AccessSessionResolver(FulfillmentCaseRepository,CaseParticipantRepository,CaseAccessSessionRepository,AccessSessionInitializer)」。
    // 具体功能：「AccessSessionResolver.AccessSessionResolver(FulfillmentCaseRepository,CaseParticipantRepository,CaseAccessSessionRepository,AccessSessionInitializer)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「participantRepository」(CaseParticipantRepository)、「accessSessionRepository」(CaseAccessSessionRepository)、「accessSessionInitializer」(AccessSessionInitializer) 并保存为「AccessSessionResolver」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AccessSessionResolver.AccessSessionResolver(FulfillmentCaseRepository,CaseParticipantRepository,CaseAccessSessionRepository,AccessSessionInitializer)」的上游创建点包括 「AccessSessionResolverTest.setUp」。
    // 下游影响：「AccessSessionResolver.AccessSessionResolver(FulfillmentCaseRepository,CaseParticipantRepository,CaseAccessSessionRepository,AccessSessionInitializer)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AccessSessionResolver.AccessSessionResolver(FulfillmentCaseRepository,CaseParticipantRepository,CaseAccessSessionRepository,AccessSessionInitializer)」负责主链路中的“访问会话解析器”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AccessSessionResolver(
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            CaseAccessSessionRepository accessSessionRepository,
            AccessSessionInitializer accessSessionInitializer) {
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.accessSessionRepository = accessSessionRepository;
        this.accessSessionInitializer = accessSessionInitializer;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.resolve(String,AuthenticatedActor)」。
    // 具体功能：「AccessSessionResolver.resolve(String,AuthenticatedActor)」：解析案件访问会话：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「dispute.getId」、「permissionLevelFor」、「find」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「AccessSessionResolver.resolve(String,AuthenticatedActor)」的上游调用点包括 「CaseAgentRunController.active」、「AgentRunStreamEventService.requireVisibleRun」、「CaseEventService.accessSession」、「EvidenceAgentTurnService.resolveSession」。
    // 下游影响：「AccessSessionResolver.resolve(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「dispute.getId」、「permissionLevelFor」、「find」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AccessSessionResolver.resolve(String,AuthenticatedActor)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public CaseAccessSessionEntity resolve(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        PermissionLevel permissionLevel = permissionLevelFor(dispute, actor);
        return find(dispute.getId(), actor, permissionLevel)
                .orElseGet(
                        () ->
                                initialize(dispute.getId(), actor, permissionLevel));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.initialize(String,AuthenticatedActor,PermissionLevel)」。
    // 具体功能：「AccessSessionResolver.initialize(String,AuthenticatedActor,PermissionLevel)」：初始化案件访问会话；实际协作者为 「TransactionSynchronizationManager.isCurrentTransactionReadOnly」、「accessSessionInitializer.initializeInNewTransaction」、「accessSessionInitializer.initializeInCurrentTransaction」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「AccessSessionResolver.initialize(String,AuthenticatedActor,PermissionLevel)」的上游调用点包括 「AccessSessionResolver.resolve」。
    // 下游影响：「AccessSessionResolver.initialize(String,AuthenticatedActor,PermissionLevel)」向下依次触达 「TransactionSynchronizationManager.isCurrentTransactionReadOnly」、「accessSessionInitializer.initializeInNewTransaction」、「accessSessionInitializer.initializeInCurrentTransaction」；计算结果以「CaseAccessSessionEntity」交给调用方。
    // 系统意义：「AccessSessionResolver.initialize(String,AuthenticatedActor,PermissionLevel)」负责主链路中的“案件访问会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private CaseAccessSessionEntity initialize(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return accessSessionInitializer.initializeInNewTransaction(
                    caseId, actor, permissionLevel);
        }
        return accessSessionInitializer.initializeInCurrentTransaction(
                caseId, actor, permissionLevel);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.find(String,AuthenticatedActor,PermissionLevel)」。
    // 具体功能：「AccessSessionResolver.find(String,AuthenticatedActor,PermissionLevel)」：查找可选；实际协作者为 「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」、「actor.actorId」、「actor.role」，最终返回「Optional<CaseAccessSessionEntity>」。
    // 上游调用：「AccessSessionResolver.find(String,AuthenticatedActor,PermissionLevel)」的上游调用点包括 「AccessSessionResolver.resolve」。
    // 下游影响：「AccessSessionResolver.find(String,AuthenticatedActor,PermissionLevel)」向下依次触达 「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」、「actor.actorId」、「actor.role」；计算结果以「Optional<CaseAccessSessionEntity>」交给调用方。
    // 系统意义：「AccessSessionResolver.find(String,AuthenticatedActor,PermissionLevel)」负责主链路中的“可选”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private Optional<CaseAccessSessionEntity> find(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        return accessSessionRepository
                .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                        DEFAULT_TENANT,
                        caseId,
                        actor.actorId(),
                        actor.role(),
                        permissionLevel);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.permissionLevelFor(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「AccessSessionResolver.permissionLevelFor(FulfillmentCaseEntity,AuthenticatedActor)」：构建权限级别面向；实际协作者为 「actor.role」、「dispute.getUserId」、「dispute.getMerchantId」、「assertPartyCanAccess」，最终返回「PermissionLevel」。
    // 上游调用：「AccessSessionResolver.permissionLevelFor(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「AccessSessionResolver.resolve」。
    // 下游影响：「AccessSessionResolver.permissionLevelFor(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「dispute.getUserId」、「dispute.getMerchantId」、「assertPartyCanAccess」；计算结果以「PermissionLevel」交给调用方。
    // 系统意义：「AccessSessionResolver.permissionLevelFor(FulfillmentCaseEntity,AuthenticatedActor)」负责主链路中的“权限级别面向”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private PermissionLevel permissionLevelFor(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        return switch (actor.role()) {
            case USER -> {
                assertPartyCanAccess(dispute, actor, ActorRole.USER, dispute.getUserId());
                yield PermissionLevel.PARTY_USER;
            }
            case MERCHANT -> {
                assertPartyCanAccess(
                        dispute, actor, ActorRole.MERCHANT, dispute.getMerchantId());
                yield PermissionLevel.PARTY_MERCHANT;
            }
            case CUSTOMER_SERVICE -> PermissionLevel.SERVICE_ASSIST;
            case PLATFORM_REVIEWER -> PermissionLevel.REVIEWER_ALL;
            case ADMIN -> PermissionLevel.ADMIN_ALL;
            case SYSTEM -> PermissionLevel.SYSTEM_ALL;
        };
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionResolver.assertPartyCanAccess(FulfillmentCaseEntity,AuthenticatedActor,ActorRole,String)」。
    // 具体功能：「AccessSessionResolver.assertPartyCanAccess(FulfillmentCaseEntity,AuthenticatedActor,ActorRole,String)」：断言当事方Can访问；实际协作者为 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.actorId」、「dispute.getId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「AccessSessionResolver.assertPartyCanAccess(FulfillmentCaseEntity,AuthenticatedActor,ActorRole,String)」的上游调用点包括 「AccessSessionResolver.permissionLevelFor」。
    // 下游影响：「AccessSessionResolver.assertPartyCanAccess(FulfillmentCaseEntity,AuthenticatedActor,ActorRole,String)」向下依次触达 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.actorId」、「dispute.getId」。
    // 系统意义：「AccessSessionResolver.assertPartyCanAccess(FulfillmentCaseEntity,AuthenticatedActor,ActorRole,String)」在“当事方Can访问”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void assertPartyCanAccess(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor actor,
            ActorRole expectedRole,
            String ownerActorId) {
        if (actor.actorId().equals(ownerActorId)) {
            return;
        }
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), expectedRole);
        if (!participant) {
            throw new ForbiddenException("actor cannot create access session for this case");
        }
    }
}
