/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明审计Log在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「countByCaseId」、「findAllByCaseIdOrderByCreatedAtDesc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「AuditLogRepository」。
// 类型职责：声明审计Log在 PostgreSQL 中的查询与写入契约；本类型显式提供 「countByCaseId」、「findAllByCaseIdOrderByCreatedAtDesc」。
// 协作关系：主要由 「AuditQueryService.listForCase」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AuditLogRepository.countByCaseId(String)」。
    // 具体功能：「AuditLogRepository.countByCaseId(String)」：声明按案件标识访问审计Log的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「AuditLogRepository.countByCaseId(String)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「AuditLogRepository.countByCaseId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AuditLogRepository.countByCaseId(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    long countByCaseId(String caseId);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AuditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(String)」。
    // 具体功能：「AuditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(String)」：声明按案件标识访问审计Log的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AuditLogEntity>」返回。
    // 上游调用：「AuditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(String)」的上游调用点包括 「AuditQueryService.listForCase」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」。
    // 下游影响：「AuditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AuditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<AuditLogEntity> findAllByCaseIdOrderByCreatedAtDesc(String caseId);
}
