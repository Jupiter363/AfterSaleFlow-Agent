/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：声明证据提交批次在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndIdempotencyKey」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceSubmissionBatchEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【证据与版本化卷宗 / 仓储接口层】类型「EvidenceSubmissionBatchRepository」。
// 类型职责：声明证据提交批次在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndIdempotencyKey」。
// 协作关系：主要由 「EvidenceSubmissionService.submit」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceSubmissionBatchRepository
        extends JpaRepository<EvidenceSubmissionBatchEntity, String> {
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidenceSubmissionBatchRepository.findByCaseIdAndIdempotencyKey(String,String)」。
    // 具体功能：「EvidenceSubmissionBatchRepository.findByCaseIdAndIdempotencyKey(String,String)」：声明按案件标识、Idempotency键访问证据提交批次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidenceSubmissionBatchEntity>」返回。
    // 上游调用：「EvidenceSubmissionBatchRepository.findByCaseIdAndIdempotencyKey(String,String)」的上游调用点包括 「EvidenceSubmissionService.submit」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」。
    // 下游影响：「EvidenceSubmissionBatchRepository.findByCaseIdAndIdempotencyKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceSubmissionBatchRepository.findByCaseIdAndIdempotencyKey(String,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidenceSubmissionBatchEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
}
