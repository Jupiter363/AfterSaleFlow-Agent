/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明履约案件在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCreationIdempotencyKey」、「findBySourceSystemAndExternalCaseRef」、「findByIdForUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「FulfillmentCaseRepository」。
// 类型职责：声明履约案件在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCreationIdempotencyKey」、「findBySourceSystemAndExternalCaseRef」、「findByIdForUpdate」。
// 协作关系：主要由 「AccessSessionInitializer.initialize」、「AgentRunCoordinator.start」、「AgentSessionInitializer.initialize」、「CaseApplicationService.create」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface FulfillmentCaseRepository
        extends JpaRepository<FulfillmentCaseEntity, String>,
                JpaSpecificationExecutor<FulfillmentCaseEntity> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「FulfillmentCaseRepository.findByCreationIdempotencyKey(String)」。
    // 具体功能：「FulfillmentCaseRepository.findByCreationIdempotencyKey(String)」：声明按CreationIdempotency键访问履约案件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<FulfillmentCaseEntity>」返回。
    // 上游调用：「FulfillmentCaseRepository.findByCreationIdempotencyKey(String)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「ExternalCaseImportTransactionService.importDispute」、「CaseApplicationService.create」、「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」。
    // 下游影响：「FulfillmentCaseRepository.findByCreationIdempotencyKey(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentCaseRepository.findByCreationIdempotencyKey(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<FulfillmentCaseEntity> findByCreationIdempotencyKey(String idempotencyKey);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(String,String)」。
    // 具体功能：「FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(String,String)」：声明按来源System、外部案件Ref访问履约案件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<FulfillmentCaseEntity>」返回。
    // 上游调用：「FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState」、「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」。
    // 下游影响：「FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<FulfillmentCaseEntity> findBySourceSystemAndExternalCaseRef(
            String sourceSystem, String externalCaseRef);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「FulfillmentCaseRepository.findByIdForUpdate(String)」。
    // 具体功能：「FulfillmentCaseRepository.findByIdForUpdate(String)」：声明按标识面向更新访问履约案件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<FulfillmentCaseEntity>」返回。
    // 上游调用：「FulfillmentCaseRepository.findByIdForUpdate(String)」的上游调用点包括 「AgentRunCoordinator.start」、「DemoCasePurgeService.purge」、「CaseClosureService.prepareClosure」、「EvidenceCompletionService.warnDeadline」。
    // 下游影响：「FulfillmentCaseRepository.findByIdForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentCaseRepository.findByIdForUpdate(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select disputeCase from FulfillmentCaseEntity disputeCase where disputeCase.id = :id")
    Optional<FulfillmentCaseEntity> findByIdForUpdate(@Param("id") String id);
}
