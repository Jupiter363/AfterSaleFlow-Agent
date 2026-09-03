/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明证据卷宗在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findTopByCaseIdOrderByDossierVersionDesc」、「findByCaseIdAndDossierVersion」、「findByCaseId」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「EvidenceDossierRepository」。
// 类型职责：声明证据卷宗在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findTopByCaseIdOrderByDossierVersionDesc」、「findByCaseIdAndDossierVersion」、「findByCaseId」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.assemble」、「EvidenceApplicationService.buildDossier」、「EvidenceApplicationService.getDossier」、「EvidenceApplicationService.upload」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceDossierRepository extends JpaRepository<EvidenceDossierEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」。
    // 具体功能：「EvidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」：声明按案件标识访问证据卷宗的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidenceDossierEntity>」返回。
    // 上游调用：「EvidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」的上游调用点包括 「EvidenceDossierFreezer.targetVersion」、「EvidenceDossierFreezer.latestVersion」、「EvidenceDossierQueryService.latest」、「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidenceDossierEntity> findTopByCaseIdOrderByDossierVersionDesc(String caseId);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvidenceDossierRepository.findByCaseIdAndDossierVersion(String,int)」。
    // 具体功能：「EvidenceDossierRepository.findByCaseIdAndDossierVersion(String,int)」：声明按案件标识、卷宗版本访问证据卷宗的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidenceDossierEntity>」返回。
    // 上游调用：「EvidenceDossierRepository.findByCaseIdAndDossierVersion(String,int)」的上游调用点包括 「EvidenceDossierFreezer.freeze」、「EvidenceDossierQueryService.get」、「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」。
    // 下游影响：「EvidenceDossierRepository.findByCaseIdAndDossierVersion(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceDossierRepository.findByCaseIdAndDossierVersion(String,int)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidenceDossierEntity> findByCaseIdAndDossierVersion(
            String caseId, int dossierVersion);

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「EvidenceDossierRepository.findByCaseId(String)」。
    // 具体功能：「EvidenceDossierRepository.findByCaseId(String)」：查找案件标识；实际协作者为 「findTopByCaseIdOrderByDossierVersionDesc」，最终返回「Optional<EvidenceDossierEntity>」。
    // 上游调用：「EvidenceDossierRepository.findByCaseId(String)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationService.buildDossier」、「EvidenceApplicationService.getDossier」、「ReviewApplicationService.createForWorkflow」。
    // 下游影响：「EvidenceDossierRepository.findByCaseId(String)」向下依次触达 「findTopByCaseIdOrderByDossierVersionDesc」；计算结果以「Optional<EvidenceDossierEntity>」交给调用方。
    // 系统意义：「EvidenceDossierRepository.findByCaseId(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    default Optional<EvidenceDossierEntity> findByCaseId(String caseId) {
        return findTopByCaseIdOrderByDossierVersionDesc(caseId);
    }
}
