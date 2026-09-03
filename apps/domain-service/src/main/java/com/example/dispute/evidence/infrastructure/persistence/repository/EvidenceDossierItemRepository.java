/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：声明证据卷宗Item在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByDossierIdOrderBySequenceNo」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【证据与版本化卷宗 / 仓储接口层】类型「EvidenceDossierItemRepository」。
// 类型职责：声明证据卷宗Item在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByDossierIdOrderBySequenceNo」。
// 协作关系：主要由 「EvidenceDossierQueryService.view」、「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」、「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceDossierItemRepository
        extends JpaRepository<EvidenceDossierItemEntity, String> {
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidenceDossierItemRepository.findAllByDossierIdOrderBySequenceNo(String)」。
    // 具体功能：「EvidenceDossierItemRepository.findAllByDossierIdOrderBySequenceNo(String)」：声明按卷宗标识访问证据卷宗Item的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<EvidenceDossierItemEntity>」返回。
    // 上游调用：「EvidenceDossierItemRepository.findAllByDossierIdOrderBySequenceNo(String)」的上游调用点包括 「EvidenceDossierQueryService.view」、「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」、「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」。
    // 下游影响：「EvidenceDossierItemRepository.findAllByDossierIdOrderBySequenceNo(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceDossierItemRepository.findAllByDossierIdOrderBySequenceNo(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<EvidenceDossierItemEntity> findAllByDossierIdOrderBySequenceNo(String dossierId);
}
