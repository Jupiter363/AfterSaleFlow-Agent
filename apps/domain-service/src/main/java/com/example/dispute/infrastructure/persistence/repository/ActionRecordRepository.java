/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明动作记录在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByIdempotencyKey」、「findAllByCaseIdOrderByCreatedAtAsc」、「findByIdempotencyKeyForUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「ActionRecordRepository」。
// 类型职责：声明动作记录在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByIdempotencyKey」、「findAllByCaseIdOrderByCreatedAtAsc」、「findByIdempotencyKeyForUpdate」。
// 协作关系：主要由 「CaseClosureService.prepareClosure」、「ToolExecutorService.actions」、「ToolExecutorService.completeFailure」、「ToolExecutorService.completeSuccess」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ActionRecordRepository extends JpaRepository<ActionRecordEntity, String> {

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ActionRecordRepository.findByIdempotencyKey(String)」。
    // 具体功能：「ActionRecordRepository.findByIdempotencyKey(String)」：声明按Idempotency键访问动作记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ActionRecordEntity>」返回。
    // 上游调用：「ActionRecordRepository.findByIdempotencyKey(String)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「ActionRecordRepository.findByIdempotencyKey(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ActionRecordRepository.findByIdempotencyKey(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<ActionRecordEntity> findByIdempotencyKey(String idempotencyKey);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ActionRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」。
    // 具体功能：「ActionRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」：声明按案件标识访问动作记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<ActionRecordEntity>」返回。
    // 上游调用：「ActionRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的上游调用点包括 「CaseClosureService.prepareClosure」、「ToolExecutorService.executeApprovedActions」、「ToolExecutorService.actions」、「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction」。
    // 下游影响：「ActionRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ActionRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<ActionRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ActionRecordRepository.findByIdempotencyKeyForUpdate(String)」。
    // 具体功能：「ActionRecordRepository.findByIdempotencyKeyForUpdate(String)」：声明按Idempotency键面向更新访问动作记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ActionRecordEntity>」返回。
    // 上游调用：「ActionRecordRepository.findByIdempotencyKeyForUpdate(String)」的上游调用点包括 「ToolExecutorService.prepare」、「ToolExecutorService.completeSuccess」、「ToolExecutorService.completeFailure」。
    // 下游影响：「ActionRecordRepository.findByIdempotencyKeyForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ActionRecordRepository.findByIdempotencyKeyForUpdate(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select action from ActionRecordEntity action "
                    + "where action.idempotencyKey = :idempotencyKey")
    Optional<ActionRecordEntity> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);
}
