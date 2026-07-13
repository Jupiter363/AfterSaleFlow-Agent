/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明Adjudication草案在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndDraftVersion」、「findFirstByCaseIdOrderByDraftVersionDesc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「AdjudicationDraftRepository」。
// 类型职责：声明Adjudication草案在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndDraftVersion」、「findFirstByCaseIdOrderByDraftVersionDesc」。
// 协作关系：主要由 「CaseClosureService.buildSnapshot」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「CaseOutcomeService.get」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AdjudicationDraftRepository
        extends JpaRepository<AdjudicationDraftEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AdjudicationDraftRepository.findByCaseIdAndDraftVersion(String,int)」。
    // 具体功能：「AdjudicationDraftRepository.findByCaseIdAndDraftVersion(String,int)」：声明按案件标识、草案版本访问Adjudication草案的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AdjudicationDraftEntity>」返回。
    // 上游调用：「AdjudicationDraftRepository.findByCaseIdAndDraftVersion(String,int)」的上游调用点包括 「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound」、「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingRoundService.status」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」。
    // 下游影响：「AdjudicationDraftRepository.findByCaseIdAndDraftVersion(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AdjudicationDraftRepository.findByCaseIdAndDraftVersion(String,int)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AdjudicationDraftEntity> findByCaseIdAndDraftVersion(
            String caseId, int draftVersion);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「AdjudicationDraftRepository.findFirstByCaseIdOrderByDraftVersionDesc(String)」。
    // 具体功能：「AdjudicationDraftRepository.findFirstByCaseIdOrderByDraftVersionDesc(String)」：声明按案件标识访问Adjudication草案的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AdjudicationDraftEntity>」返回。
    // 上游调用：「AdjudicationDraftRepository.findFirstByCaseIdOrderByDraftVersionDesc(String)」的上游调用点包括 「CaseClosureService.buildSnapshot」、「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingRoundService.status」。
    // 下游影响：「AdjudicationDraftRepository.findFirstByCaseIdOrderByDraftVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AdjudicationDraftRepository.findFirstByCaseIdOrderByDraftVersionDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AdjudicationDraftEntity> findFirstByCaseIdOrderByDraftVersionDesc(
            String caseId);
}
