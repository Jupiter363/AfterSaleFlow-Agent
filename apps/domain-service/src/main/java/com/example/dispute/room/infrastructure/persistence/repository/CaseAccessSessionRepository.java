/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件访问会话在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CaseAccessSessionRepository」。
// 类型职责：声明案件访问会话在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」。
// 协作关系：主要由 「AccessSessionInitializer.initialize」、「AccessSessionResolver.find」、「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」、「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CaseAccessSessionRepository
        extends JpaRepository<CaseAccessSessionEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseAccessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(String,String,String,ActorRole,PermissionLevel)」。
    // 具体功能：「CaseAccessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(String,String,String,ActorRole,PermissionLevel)」：声明按Tenant标识、案件标识、操作者标识、操作者角色、权限级别访问案件访问会话的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseAccessSessionEntity>」返回。
    // 上游调用：「CaseAccessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(String,String,String,ActorRole,PermissionLevel)」的上游调用点包括 「AccessSessionInitializer.initialize」、「AccessSessionResolver.find」、「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」、「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」。
    // 下游影响：「CaseAccessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(String,String,String,ActorRole,PermissionLevel)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseAccessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(String,String,String,ActorRole,PermissionLevel)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CaseAccessSessionEntity>
            findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                    String tenantId,
                    String caseId,
                    String actorId,
                    ActorRole actorRole,
                    PermissionLevel permissionLevel);
}
