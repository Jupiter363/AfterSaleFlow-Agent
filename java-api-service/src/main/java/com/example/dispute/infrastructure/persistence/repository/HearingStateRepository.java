/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明庭审状态在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseId」、「findByWorkflowId」、「findAllByHearingStatusOrderByCompletedAtAsc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.domain.model.HearingStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「HearingStateRepository」。
// 类型职责：声明庭审状态在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseId」、「findByWorkflowId」、「findAllByHearingStatusOrderByCompletedAtAsc」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.completeHearing」、「CaseFulfillmentDisputeActivitiesImpl.initializeHearing」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingStateRepository extends JpaRepository<HearingStateEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingStateRepository.findByCaseId(String)」。
    // 具体功能：「HearingStateRepository.findByCaseId(String)」：声明按案件标识访问庭审状态的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingStateEntity>」返回。
    // 上游调用：「HearingStateRepository.findByCaseId(String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」、「HearingCourtBootstrapService.ensureHearingState」、「HearingFinalDraftService.completeStateIfNeeded」、「HearingOutcomeOrchestrationService.orchestrate」。
    // 下游影响：「HearingStateRepository.findByCaseId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingStateRepository.findByCaseId(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingStateEntity> findByCaseId(String caseId);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingStateRepository.findByWorkflowId(String)」。
    // 具体功能：「HearingStateRepository.findByWorkflowId(String)」：声明按标识访问庭审状态的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingStateEntity>」返回。
    // 上游调用：「HearingStateRepository.findByWorkflowId(String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.initializeHearing」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「CaseFulfillmentDisputeActivitiesImpl.completeHearing」、「HearingPersistenceIntegrationTest.seedFinalCourtroomContext」。
    // 下游影响：「HearingStateRepository.findByWorkflowId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingStateRepository.findByWorkflowId(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingStateEntity> findByWorkflowId(String workflowId);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingStateRepository.findAllByHearingStatusOrderByCompletedAtAsc(HearingStatus)」。
    // 具体功能：「HearingStateRepository.findAllByHearingStatusOrderByCompletedAtAsc(HearingStatus)」：声明按庭审状态访问庭审状态的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingStateEntity>」返回。
    // 上游调用：「HearingStateRepository.findAllByHearingStatusOrderByCompletedAtAsc(HearingStatus)」的上游调用点包括 「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview」。
    // 下游影响：「HearingStateRepository.findAllByHearingStatusOrderByCompletedAtAsc(HearingStatus)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingStateRepository.findAllByHearingStatusOrderByCompletedAtAsc(HearingStatus)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<HearingStateEntity> findAllByHearingStatusOrderByCompletedAtAsc(
            HearingStatus hearingStatus);
}
