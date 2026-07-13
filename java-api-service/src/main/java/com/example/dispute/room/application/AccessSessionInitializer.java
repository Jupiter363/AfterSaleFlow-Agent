/*
 * 所属模块：房间协作与权限。
 * 文件职责：承载访问会话初始化器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「initializeInCurrentTransaction」、「initializeInNewTransaction」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「AccessSessionInitializer」。
// 类型职责：承载访问会话初始化器在当前业务模块中的规则与协作边界；本类型显式提供 「AccessSessionInitializer」、「initializeInCurrentTransaction」、「initializeInNewTransaction」、「initialize」、「compactUuid」。
// 协作关系：主要由 「AccessSessionResolver.initialize」、「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」、「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AccessSessionInitializer {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseAccessSessionRepository accessSessionRepository;

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionInitializer.AccessSessionInitializer(FulfillmentCaseRepository,CaseAccessSessionRepository)」。
    // 具体功能：「AccessSessionInitializer.AccessSessionInitializer(FulfillmentCaseRepository,CaseAccessSessionRepository)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「accessSessionRepository」(CaseAccessSessionRepository) 并保存为「AccessSessionInitializer」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AccessSessionInitializer.AccessSessionInitializer(FulfillmentCaseRepository,CaseAccessSessionRepository)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「AccessSessionInitializer.AccessSessionInitializer(FulfillmentCaseRepository,CaseAccessSessionRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AccessSessionInitializer.AccessSessionInitializer(FulfillmentCaseRepository,CaseAccessSessionRepository)」负责主链路中的“访问会话初始化器”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AccessSessionInitializer(
            FulfillmentCaseRepository caseRepository,
            CaseAccessSessionRepository accessSessionRepository) {
        this.caseRepository = caseRepository;
        this.accessSessionRepository = accessSessionRepository;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionInitializer.initializeInCurrentTransaction(String,AuthenticatedActor,PermissionLevel)」。
    // 具体功能：「AccessSessionInitializer.initializeInCurrentTransaction(String,AuthenticatedActor,PermissionLevel)」：初始化InCurrentTransaction：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「initialize」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「AccessSessionInitializer.initializeInCurrentTransaction(String,AuthenticatedActor,PermissionLevel)」的上游调用点包括 「AccessSessionResolver.initialize」、「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」。
    // 下游影响：「AccessSessionInitializer.initializeInCurrentTransaction(String,AuthenticatedActor,PermissionLevel)」向下依次触达 「initialize」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AccessSessionInitializer.initializeInCurrentTransaction(String,AuthenticatedActor,PermissionLevel)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public CaseAccessSessionEntity initializeInCurrentTransaction(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        return initialize(caseId, actor, permissionLevel);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionInitializer.initializeInNewTransaction(String,AuthenticatedActor,PermissionLevel)」。
    // 具体功能：「AccessSessionInitializer.initializeInNewTransaction(String,AuthenticatedActor,PermissionLevel)」：初始化In新案件Transaction：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「initialize」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「AccessSessionInitializer.initializeInNewTransaction(String,AuthenticatedActor,PermissionLevel)」的上游调用点包括 「AccessSessionResolver.initialize」、「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」。
    // 下游影响：「AccessSessionInitializer.initializeInNewTransaction(String,AuthenticatedActor,PermissionLevel)」向下依次触达 「initialize」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AccessSessionInitializer.initializeInNewTransaction(String,AuthenticatedActor,PermissionLevel)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaseAccessSessionEntity initializeInNewTransaction(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        return initialize(caseId, actor, permissionLevel);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionInitializer.initialize(String,AuthenticatedActor,PermissionLevel)」。
    // 具体功能：「AccessSessionInitializer.initialize(String,AuthenticatedActor,PermissionLevel)」：初始化案件访问会话：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」、「accessSessionRepository.save」、「CaseAccessSessionEntity.create」；处理的关键状态/协议值包括 「ACCESS_」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「AccessSessionInitializer.initialize(String,AuthenticatedActor,PermissionLevel)」的上游调用点包括 「AccessSessionInitializer.initializeInCurrentTransaction」、「AccessSessionInitializer.initializeInNewTransaction」。
    // 下游影响：「AccessSessionInitializer.initialize(String,AuthenticatedActor,PermissionLevel)」向下依次触达 「caseRepository.findByIdForUpdate」、「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」、「accessSessionRepository.save」、「CaseAccessSessionEntity.create」；计算结果以「CaseAccessSessionEntity」交给调用方。
    // 系统意义：「AccessSessionInitializer.initialize(String,AuthenticatedActor,PermissionLevel)」负责主链路中的“案件访问会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private CaseAccessSessionEntity initialize(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return accessSessionRepository
                .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                        AccessSessionResolver.DEFAULT_TENANT,
                        caseId,
                        actor.actorId(),
                        actor.role(),
                        permissionLevel)
                .orElseGet(
                        () ->
                                accessSessionRepository.save(
                                        CaseAccessSessionEntity.create(
                                                "ACCESS_" + compactUuid(),
                                                AccessSessionResolver.DEFAULT_TENANT,
                                                caseId,
                                                actor.actorId(),
                                                actor.role(),
                                                permissionLevel,
                                                actor.actorId())));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「AccessSessionInitializer.compactUuid()」。
    // 具体功能：「AccessSessionInitializer.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AccessSessionInitializer.compactUuid()」的上游调用点包括 「AccessSessionInitializer.initialize」。
    // 下游影响：「AccessSessionInitializer.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AccessSessionInitializer.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
