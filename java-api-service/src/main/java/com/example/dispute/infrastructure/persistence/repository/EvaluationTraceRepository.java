/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明评估链路标识在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findFirstByCaseIdOrderByEvaluationVersionDesc」、「findAllByOrderByCreatedAtDesc」、「findByIdForUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「EvaluationTraceRepository」。
// 类型职责：声明评估链路标识在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findFirstByCaseIdOrderByEvaluationVersionDesc」、「findAllByOrderByCreatedAtDesc」、「findByIdForUpdate」。
// 协作关系：主要由 「CaseClosureService.closureView」、「CaseClosureService.completeEvaluation」、「CaseClosureService.evaluation」、「CaseClosureService.failEvaluation」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvaluationTraceRepository
        extends JpaRepository<EvaluationTraceEntity, String> {

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvaluationTraceRepository.findFirstByCaseIdOrderByEvaluationVersionDesc(String)」。
    // 具体功能：「EvaluationTraceRepository.findFirstByCaseIdOrderByEvaluationVersionDesc(String)」：声明按案件标识访问评估链路标识的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvaluationTraceEntity>」返回。
    // 上游调用：「EvaluationTraceRepository.findFirstByCaseIdOrderByEvaluationVersionDesc(String)」的上游调用点包括 「CaseClosureService.evaluation」、「CaseClosureService.prepareClosure」、「CaseClosureService.closureView」。
    // 下游影响：「EvaluationTraceRepository.findFirstByCaseIdOrderByEvaluationVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvaluationTraceRepository.findFirstByCaseIdOrderByEvaluationVersionDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvaluationTraceEntity>
            findFirstByCaseIdOrderByEvaluationVersionDesc(String caseId);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvaluationTraceRepository.findAllByOrderByCreatedAtDesc()」。
    // 具体功能：「EvaluationTraceRepository.findAllByOrderByCreatedAtDesc()」：声明按调用方给定条件访问评估链路标识的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<EvaluationTraceEntity>」返回。
    // 上游调用：「EvaluationTraceRepository.findAllByOrderByCreatedAtDesc()」的上游调用点包括 「CaseClosureService.metrics」。
    // 下游影响：「EvaluationTraceRepository.findAllByOrderByCreatedAtDesc()」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvaluationTraceRepository.findAllByOrderByCreatedAtDesc()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<EvaluationTraceEntity> findAllByOrderByCreatedAtDesc();

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvaluationTraceRepository.findByIdForUpdate(String)」。
    // 具体功能：「EvaluationTraceRepository.findByIdForUpdate(String)」：声明按标识面向更新访问评估链路标识的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvaluationTraceEntity>」返回。
    // 上游调用：「EvaluationTraceRepository.findByIdForUpdate(String)」的上游调用点包括 「CaseClosureService.completeEvaluation」、「CaseClosureService.failEvaluation」。
    // 下游影响：「EvaluationTraceRepository.findByIdForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvaluationTraceRepository.findByIdForUpdate(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select trace from EvaluationTraceEntity trace "
                    + "where trace.id = :traceId")
    Optional<EvaluationTraceEntity> findByIdForUpdate(
            @Param("traceId") String traceId);
}
