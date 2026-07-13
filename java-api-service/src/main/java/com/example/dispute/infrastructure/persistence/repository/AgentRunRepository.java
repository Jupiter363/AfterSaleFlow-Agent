/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明Agent运行在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByCaseIdOrderByCreatedAtAsc」、「findByCaseIdAndStreamIdempotencyKey」、「findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc」、「findByIdForUpdate」、「findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc」、「findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「AgentRunRepository」。
// 类型职责：声明Agent运行在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByCaseIdOrderByCreatedAtAsc」、「findByCaseIdAndStreamIdempotencyKey」、「findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc」、「findByIdForUpdate」、「findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc」、「findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc」。
// 协作关系：主要由 「AgentRunCoordinator.start」、「AgentRunLifecycleService.claim」、「AgentRunLifecycleService.requireNonTerminal」、「AgentRunQueryService.active」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」。
    // 具体功能：「AgentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」：声明按案件标识访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的上游调用点包括 「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」。
    // 下游影响：「AgentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<AgentRunEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findByCaseIdAndStreamIdempotencyKey(String,String)」。
    // 具体功能：「AgentRunRepository.findByCaseIdAndStreamIdempotencyKey(String,String)」：声明按案件标识、流Idempotency键访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findByCaseIdAndStreamIdempotencyKey(String,String)」的上游调用点包括 「AgentRunCoordinator.start」、「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」、「AgentRunCoordinatorTest.prepareNewRun」。
    // 下游影响：「AgentRunRepository.findByCaseIdAndStreamIdempotencyKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findByCaseIdAndStreamIdempotencyKey(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AgentRunEntity> findByCaseIdAndStreamIdempotencyKey(
            String caseId, String streamIdempotencyKey);

    List<AgentRunEntity> findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
            String caseId, String streamIdempotencyKeyPrefix);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(String,String,String,String,List)」。
    // 具体功能：「AgentRunRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(String,String,String,String,List)」：声明按案件标识、房间标识、流Operation、Created按、运行状态In访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(String,String,String,String,List)」的上游调用点包括 「AgentRunCoordinator.start」、「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」、「AgentRunCoordinatorTest.prepareNewRun」。
    // 下游影响：「AgentRunRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(String,String,String,String,List)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(String,String,String,String,List)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AgentRunEntity>
            findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(
                    String caseId,
                    String roomId,
                    String streamOperation,
                    String createdBy,
                    List<String> runStatuses);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findByIdForUpdate(String)」。
    // 具体功能：「AgentRunRepository.findByIdForUpdate(String)」：声明按标识面向更新访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findByIdForUpdate(String)」的上游调用点包括 「AgentRunLifecycleService.claim」、「AgentRunLifecycleService.requireNonTerminal」、「CaseFulfillmentDisputeActivitiesImpl.finalizeResult」、「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract」。
    // 下游影响：「AgentRunRepository.findByIdForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findByIdForUpdate(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRunEntity run where run.id = :id")
    Optional<AgentRunEntity> findByIdForUpdate(@Param("id") String id);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(String)」。
    // 具体功能：「AgentRunRepository.findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(String)」：声明按20按运行状态、流OperationIs不空值访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(String)」的上游调用点包括 「AgentRunRecoveryScheduler.recoverPendingRuns」。
    // 下游影响：「AgentRunRepository.findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<AgentRunEntity>
            findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                    String runStatus);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AgentRunRepository.findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(String,String,List)」。
    // 具体功能：「AgentRunRepository.findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(String,String,List)」：声明按20按案件标识、房间标识、运行状态In、流OperationIs不空值访问Agent运行的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AgentRunEntity>」返回。
    // 上游调用：「AgentRunRepository.findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(String,String,List)」的上游调用点包括 「AgentRunQueryService.active」。
    // 下游影响：「AgentRunRepository.findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(String,String,List)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunRepository.findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(String,String,List)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<AgentRunEntity>
            findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(
                    String caseId, String roomId, List<String> runStatuses);
}
