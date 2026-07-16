/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义角色Scoped证据跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import java.util.List;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「RoleScopedEvidenceView」。
// 类型职责：定义角色Scoped证据跨层传递时使用的不可变数据契约；本类型显式提供 「RoleScopedEvidenceView」。
// 协作关系：主要由 「EvidenceCatalogService.catalog」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RoleScopedEvidenceView(
        String caseId, String initiatorRole, String initiatorId, List<Item> items) {

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「RoleScopedEvidenceView.RoleScopedEvidenceView(String,List)」。
    // 具体功能：「RoleScopedEvidenceView.RoleScopedEvidenceView(String,List)」：使用 「caseId」(String)、「items」(List) 初始化「RoleScopedEvidenceView」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「RoleScopedEvidenceView.RoleScopedEvidenceView(String,List)」的上游创建点包括 「EvidenceCatalogService.catalog」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」。
    // 下游影响：「RoleScopedEvidenceView.RoleScopedEvidenceView(String,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoleScopedEvidenceView.RoleScopedEvidenceView(String,List)」负责主链路中的“角色Scoped证据视图”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RoleScopedEvidenceView(String caseId, List<Item> items) {
        this(caseId, null, null, items);
    }

    public RoleScopedEvidenceView(String caseId, String initiatorRole, List<Item> items) {
        this(caseId, initiatorRole, null, items);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「Item」。
    // 类型职责：定义Item跨层传递时使用的不可变数据契约；本类型显式提供 「Item」、「Item」、「Item」。
    // 协作关系：主要由 「EvidenceCatalogService.project」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」 使用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Item(
            String evidenceId,
            String evidenceType,
            String submittedByRole,
            String submittedById,
            String visibility,
            String contentUrl,
            boolean redacted,
            EvidenceVerificationStatus verificationStatus,
            Double confidenceScore,
            String confidenceLevel,
            String verificationFeedback,
            String sourceType,
            String originalFilename,
            String parsedText,
            String submissionStatus,
            java.time.OffsetDateTime submittedAt,
            String submissionBatchId,
            Double authenticityScore,
            Double relevanceScore,
            Double completenessScore,
            Double assessmentConfidence,
            List<String> inspectedModalities,
            List<String> limitations,
            boolean requiresHumanReview,
            List<String> humanReviewReasonCodes,
            List<String> humanReviewInstructions,
            String claimedFact,
            boolean truthAttested,
            List<String> attestationScope,
            String partyCapacity,
            String attestationVersion,
            String forgeryConsequenceCode,
            String enforcementGate) {

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String,Double,Double,Double,Double,List,List,boolean,List,List)」。
        // 具体功能：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String,Double,Double,Double,Double,List,List,boolean,List,List)」：在不可变「Item」写入组件前校验 「evidenceId」(String)、「evidenceType」(String)、「submittedByRole」(String)、「visibility」(String)、「contentUrl」(String)、「redacted」(boolean)、「verificationStatus」(EvidenceVerificationStatus)、「confidenceScore」(Double)、「confidenceLevel」(String)、「verificationFeedback」(String)、「sourceType」(String)、「originalFilename」(String)、「parsedText」(String)、「submissionStatus」(String)、「submittedAt」(OffsetDateTime)、「submissionBatchId」(String)、「authenticityScore」(Double)、「relevanceScore」(Double)、「completenessScore」(Double)、「assessmentConfidence」(Double)、「inspectedModalities」(List)、「limitations」(List)、「requiresHumanReview」(boolean)、「humanReviewReasonCodes」(List)、「humanReviewInstructions」(List)，并统一规范 record 组件值。
        // 上游调用：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String,Double,Double,Double,Double,List,List,boolean,List,List)」的上游创建点包括 「EvidenceCatalogService.project」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」。
        // 下游影响：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String,Double,Double,Double,Double,List,List,boolean,List,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String,Double,Double,Double,Double,List,List,boolean,List,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public Item {
            inspectedModalities =
                    inspectedModalities == null ? List.of() : List.copyOf(inspectedModalities);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            humanReviewReasonCodes =
                    humanReviewReasonCodes == null
                            ? List.of()
                            : List.copyOf(humanReviewReasonCodes);
            humanReviewInstructions =
                    humanReviewInstructions == null
                            ? List.of()
                            : List.copyOf(humanReviewInstructions);
            attestationScope =
                    attestationScope == null ? List.of() : List.copyOf(attestationScope);
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String)」。
        // 具体功能：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String)」：使用 「evidenceId」(String)、「evidenceType」(String)、「submittedByRole」(String)、「visibility」(String)、「contentUrl」(String)、「redacted」(boolean)、「verificationStatus」(EvidenceVerificationStatus)、「confidenceScore」(Double)、「confidenceLevel」(String)、「verificationFeedback」(String)、「sourceType」(String)、「originalFilename」(String)、「parsedText」(String)、「submissionStatus」(String)、「submittedAt」(OffsetDateTime)、「submissionBatchId」(String) 初始化「Item」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
        // 上游调用：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String)」的上游创建点包括 「EvidenceCatalogService.project」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」。
        // 下游影响：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String,String,OffsetDateTime,String)」负责主链路中的“Item”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        public Item(
                String evidenceId,
                String evidenceType,
                String submittedByRole,
                String visibility,
                String contentUrl,
                boolean redacted,
                EvidenceVerificationStatus verificationStatus,
                Double confidenceScore,
                String confidenceLevel,
                String verificationFeedback,
                String sourceType,
                String originalFilename,
                String parsedText,
                String submissionStatus,
                java.time.OffsetDateTime submittedAt,
                String submissionBatchId) {
            this(
                    evidenceId,
                    evidenceType,
                    submittedByRole,
                    null,
                    visibility,
                    contentUrl,
                    redacted,
                    verificationStatus,
                    confidenceScore,
                    confidenceLevel,
                    verificationFeedback,
                    sourceType,
                    originalFilename,
                    parsedText,
                    submissionStatus,
                    submittedAt,
                    submissionBatchId,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    null);
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String)」。
        // 具体功能：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String)」：使用 「evidenceId」(String)、「evidenceType」(String)、「submittedByRole」(String)、「visibility」(String)、「contentUrl」(String)、「redacted」(boolean)、「verificationStatus」(EvidenceVerificationStatus)、「confidenceScore」(Double)、「confidenceLevel」(String)、「verificationFeedback」(String)、「sourceType」(String)、「originalFilename」(String)、「parsedText」(String) 初始化「Item」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
        // 上游调用：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String)」的上游创建点包括 「EvidenceCatalogService.project」、「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」。
        // 下游影响：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「RoleScopedEvidenceView.Item.Item(String,String,String,String,String,boolean,EvidenceVerificationStatus,Double,String,String,String,String,String)」负责主链路中的“Item”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        public Item(
                String evidenceId,
                String evidenceType,
                String submittedByRole,
                String visibility,
                String contentUrl,
                boolean redacted,
                EvidenceVerificationStatus verificationStatus,
                Double confidenceScore,
                String confidenceLevel,
                String verificationFeedback,
                String sourceType,
                String originalFilename,
                String parsedText) {
            this(
                    evidenceId,
                    evidenceType,
                    submittedByRole,
                    null,
                    visibility,
                    contentUrl,
                    redacted,
                    verificationStatus,
                    confidenceScore,
                    confidenceLevel,
                    verificationFeedback,
                    sourceType,
                    originalFilename,
                    parsedText,
                    "SUBMITTED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    null);
        }
    }
}
