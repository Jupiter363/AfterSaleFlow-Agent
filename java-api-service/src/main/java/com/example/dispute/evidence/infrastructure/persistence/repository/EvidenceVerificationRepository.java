/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：声明证据核验在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findTopByEvidenceIdOrderByVerificationVersionDesc」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【证据与版本化卷宗 / 仓储接口层】类型「EvidenceVerificationRepository」。
// 类型职责：声明证据核验在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findTopByEvidenceIdOrderByVerificationVersionDesc」。
// 协作关系：主要由 「EvidenceAgentTurnService.nextVerificationVersion」、「EvidenceCatalogService.project」、「EvidenceDossierFreezer.withLatestStatus」、「EvidenceVerificationService.verify」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceVerificationRepository
        extends JpaRepository<EvidenceVerificationEntity, String> {
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidenceVerificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(String)」。
    // 具体功能：「EvidenceVerificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(String)」：声明按证据标识访问证据核验的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidenceVerificationEntity>」返回。
    // 上游调用：「EvidenceVerificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(String)」的上游调用点包括 「EvidenceCatalogService.project」、「EvidenceDossierFreezer.withLatestStatus」、「EvidenceVerificationService.verify」、「EvidenceAgentTurnService.nextVerificationVersion」。
    // 下游影响：「EvidenceVerificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceVerificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidenceVerificationEntity> findTopByEvidenceIdOrderByVerificationVersionDesc(
            String evidenceId);
}
