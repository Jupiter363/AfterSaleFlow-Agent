/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明证据Item在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「EvidenceItemRepository」。
// 类型职责：声明证据Item在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」。
// 协作关系：主要由 「CaseClosureService.buildSnapshot」、「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「EvidenceAgentTurnService.visibleEvidenceIds」、「EvidenceApplicationService.buildDossier」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceItemRepository extends JpaRepository<EvidenceItemEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvidenceItemRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(String,String,String)」。
    // 具体功能：「EvidenceItemRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(String,String,String)」：声明按案件标识、文件哈希、来源类型、DeletedAtIs空值访问证据Item的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidenceItemEntity>」返回。
    // 上游调用：「EvidenceItemRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(String,String,String)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」、「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial」。
    // 下游影响：「EvidenceItemRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(String,String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceItemRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidenceItemEntity>
            findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
            String caseId, String fileHash, String sourceType);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String)」。
    // 具体功能：「EvidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String)」：声明按案件标识、DeletedAtIs空值访问证据Item的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<EvidenceItemEntity>」返回。
    // 上游调用：「EvidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String)」的上游调用点包括 「CaseClosureService.buildSnapshot」、「EvidenceApplicationService.buildDossier」、「EvidenceApplicationService.getDossier」、「EvidenceCatalogService.catalog」。
    // 下游影响：「EvidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<EvidenceItemEntity>
            findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String caseId);

    long countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
            String caseId, String submittedById, EvidenceSubmissionStatus submissionStatus);
}
