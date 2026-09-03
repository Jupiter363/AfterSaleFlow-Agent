/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明审批记录在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByApprovalHash」、「findAllByCaseIdOrderByCreatedAtAsc」、「findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.domain.model.ApprovalDecisionType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「ApprovalRecordRepository」。
// 类型职责：声明审批记录在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByApprovalHash」、「findAllByCaseIdOrderByCreatedAtAsc」、「findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc」。
// 协作关系：主要由 「CaseClosureService.latestApproval」、「CaseOutcomeService.get」、「ReviewApplicationService.persistDecision」、「ToolExecutorService.loadApprovedExecution」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ApprovalRecordRepository.findByApprovalHash(String)」。
    // 具体功能：「ApprovalRecordRepository.findByApprovalHash(String)」：声明按审批哈希访问审批记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ApprovalRecordEntity>」返回。
    // 上游调用：「ApprovalRecordRepository.findByApprovalHash(String)」的上游调用点包括 「ReviewApplicationService.persistDecision」。
    // 下游影响：「ApprovalRecordRepository.findByApprovalHash(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ApprovalRecordRepository.findByApprovalHash(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<ApprovalRecordEntity> findByApprovalHash(String approvalHash);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ApprovalRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」。
    // 具体功能：「ApprovalRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」：声明按案件标识访问审批记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<ApprovalRecordEntity>」返回。
    // 上游调用：「ApprovalRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的上游调用点包括 「CaseOutcomeService.get」、「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft」、「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」、「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff」。
    // 下游影响：「ApprovalRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ApprovalRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<ApprovalRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「ApprovalRecordRepository.findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(String,Collection)」。
    // 具体功能：「ApprovalRecordRepository.findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(String,Collection)」：声明按案件标识、决定类型In访问审批记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<ApprovalRecordEntity>」返回。
    // 上游调用：「ApprovalRecordRepository.findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(String,Collection)」的上游调用点包括 「CaseClosureService.latestApproval」、「ToolExecutorService.loadApprovedExecution」。
    // 下游影响：「ApprovalRecordRepository.findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(String,Collection)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ApprovalRecordRepository.findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(String,Collection)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<ApprovalRecordEntity>
            findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                    String caseId, Collection<ApprovalDecisionType> decisionTypes);
}
