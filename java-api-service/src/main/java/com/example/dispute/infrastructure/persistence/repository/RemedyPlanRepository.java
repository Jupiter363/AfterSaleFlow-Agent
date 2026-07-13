/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明补救方案在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findFirstByCaseIdOrderByPlanVersionDesc」、「findByCaseIdAndPlanVersion」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「RemedyPlanRepository」。
// 类型职责：声明补救方案在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findFirstByCaseIdOrderByPlanVersionDesc」、「findByCaseIdAndPlanVersion」。
// 协作关系：主要由 「CaseOutcomeService.get」、「HearingOutcomeOrchestrationService.orchestrate」、「RemedyApplicationService.generate」、「RemedyApplicationService.get」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface RemedyPlanRepository extends JpaRepository<RemedyPlanEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「RemedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(String)」。
    // 具体功能：「RemedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(String)」：声明按案件标识访问补救方案的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<RemedyPlanEntity>」返回。
    // 上游调用：「RemedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(String)」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」、「CaseOutcomeService.get」、「RemedyApplicationService.get」、「RemedyApplicationService.generate」。
    // 下游影响：「RemedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RemedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<RemedyPlanEntity> findFirstByCaseIdOrderByPlanVersionDesc(String caseId);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「RemedyPlanRepository.findByCaseIdAndPlanVersion(String,int)」。
    // 具体功能：「RemedyPlanRepository.findByCaseIdAndPlanVersion(String,int)」：声明按案件标识、方案版本访问补救方案的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<RemedyPlanEntity>」返回。
    // 上游调用：「RemedyPlanRepository.findByCaseIdAndPlanVersion(String,int)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「RemedyPlanRepository.findByCaseIdAndPlanVersion(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RemedyPlanRepository.findByCaseIdAndPlanVersion(String,int)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<RemedyPlanEntity> findByCaseIdAndPlanVersion(String caseId, int planVersion);
}
