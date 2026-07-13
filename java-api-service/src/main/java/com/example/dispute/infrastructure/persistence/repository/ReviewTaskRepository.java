/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明审核任务在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByTaskStatusOrderByCreatedAtAsc」、「findFirstByCaseIdOrderByCreatedAtDesc」、「findByIdForUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.domain.model.ReviewTaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「ReviewTaskRepository」。
// 类型职责：声明审核任务在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByTaskStatusOrderByCreatedAtAsc」、「findFirstByCaseIdOrderByCreatedAtDesc」、「findByIdForUpdate」。
// 协作关系：主要由 「CaseOutcomeService.latestReviewTaskId」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing」、「HearingRoundService.status」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ReviewTaskRepository extends JpaRepository<ReviewTaskEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ReviewTaskRepository.findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus)」。
    // 具体功能：「ReviewTaskRepository.findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus)」：声明按任务状态访问审核任务的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<ReviewTaskEntity>」返回。
    // 上游调用：「ReviewTaskRepository.findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus)」的上游调用点包括 「ReviewApplicationService.list」。
    // 下游影响：「ReviewTaskRepository.findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ReviewTaskRepository.findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<ReviewTaskEntity> findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus status);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ReviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(String)」。
    // 具体功能：「ReviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(String)」：声明按案件标识访问审核任务的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ReviewTaskEntity>」返回。
    // 上游调用：「ReviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(String)」的上游调用点包括 「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingRoundService.status」、「CaseOutcomeService.latestReviewTaskId」。
    // 下游影响：「ReviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ReviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<ReviewTaskEntity> findFirstByCaseIdOrderByCreatedAtDesc(String caseId);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ReviewTaskRepository.findByIdForUpdate(String)」。
    // 具体功能：「ReviewTaskRepository.findByIdForUpdate(String)」：声明按标识面向更新访问审核任务的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ReviewTaskEntity>」返回。
    // 上游调用：「ReviewTaskRepository.findByIdForUpdate(String)」的上游调用点包括 「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewTaskRepository.findByIdForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ReviewTaskRepository.findByIdForUpdate(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from ReviewTaskEntity task where task.id = :id")
    Optional<ReviewTaskEntity> findByIdForUpdate(@Param("id") String id);
}
