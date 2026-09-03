/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import java.time.OffsetDateTime;
import java.util.List;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceView」。
// 类型职责：定义证据跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceView」。
// 协作关系：主要由 「EvidenceApplicationService.toView」、「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceView(
        String id,
        String caseId,
        String evidenceType,
        String sourceType,
        String fileBucket,
        String fileObjectKey,
        String fileHash,
        String originalFilename,
        String contentType,
        long fileSize,
        String parseStatus,
        String visibility,
        boolean desensitized,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt,
        String submissionStatus,
        OffsetDateTime submittedAt,
        String submissionBatchId,
        String claimedFact,
        boolean truthAttested,
        List<String> attestationScope,
        String partyCapacity,
        String attestationVersion,
        String forgeryConsequenceCode,
        String enforcementGate) {

    public EvidenceView {
        attestationScope =
                attestationScope == null ? List.of() : List.copyOf(attestationScope);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceView.EvidenceView(String,String,String,String,String,String,String,String,String,long,String,String,boolean,OffsetDateTime,OffsetDateTime)」。
    // 具体功能：「EvidenceView.EvidenceView(String,String,String,String,String,String,String,String,String,long,String,String,boolean,OffsetDateTime,OffsetDateTime)」：使用 「id」(String)、「caseId」(String)、「evidenceType」(String)、「sourceType」(String)、「fileBucket」(String)、「fileObjectKey」(String)、「fileHash」(String)、「originalFilename」(String)、「contentType」(String)、「fileSize」(long)、「parseStatus」(String)、「visibility」(String)、「desensitized」(boolean)、「occurredAt」(OffsetDateTime)、「createdAt」(OffsetDateTime) 初始化「EvidenceView」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「EvidenceView.EvidenceView(String,String,String,String,String,String,String,String,String,long,String,String,boolean,OffsetDateTime,OffsetDateTime)」的上游创建点包括 「EvidenceApplicationService.toView」、「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex」。
    // 下游影响：「EvidenceView.EvidenceView(String,String,String,String,String,String,String,String,String,long,String,String,boolean,OffsetDateTime,OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceView.EvidenceView(String,String,String,String,String,String,String,String,String,long,String,String,boolean,OffsetDateTime,OffsetDateTime)」负责主链路中的“证据视图”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceView(
            String id,
            String caseId,
            String evidenceType,
            String sourceType,
            String fileBucket,
            String fileObjectKey,
            String fileHash,
            String originalFilename,
            String contentType,
            long fileSize,
            String parseStatus,
            String visibility,
            boolean desensitized,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt) {
        this(
                id,
                caseId,
                evidenceType,
                sourceType,
                fileBucket,
                fileObjectKey,
                fileHash,
                originalFilename,
                contentType,
                fileSize,
                parseStatus,
                visibility,
                desensitized,
                occurredAt,
                createdAt,
                "SUBMITTED",
                null,
                null,
                null,
                false,
                List.of(),
                null,
                null,
                null,
                null);
    }
}
