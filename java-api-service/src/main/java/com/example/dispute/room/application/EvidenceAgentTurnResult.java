/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义证据Agent轮次跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;

// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAgentTurnResult」。
// 类型职责：定义证据Agent轮次跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceAgentTurnResult」、「EvidenceAgentTurnResult」、「EvidenceAgentTurnResult」、「EvidenceAgentTurnResult」、「validateScore」、「immutableList」。
// 协作关系：主要由 「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」、「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvidenceAgentTurnResult(
        @JsonProperty("room_utterance") String roomUtterance,
        @JsonAlias("memory_frame")
        @JsonProperty("memory_patch") JsonNode memoryPatch,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("referenced_evidence_ids") List<String> referencedEvidenceIds,
        @JsonProperty("verification_suggestions")
                List<EvidenceVerificationSuggestion> verificationSuggestions,
        @JsonProperty("authenticity_flags") List<EvidenceAuthenticityFlag> authenticityFlags,
        @JsonProperty("evidence_assessments") List<EvidenceAssessment> evidenceAssessments,
        @JsonProperty("fact_matrix_patch") List<Map<String, Object>> factMatrixPatch,
        @JsonProperty("human_review_tasks") List<Map<String, Object>> humanReviewTasks,
        @JsonProperty("internal_handoff") Map<String, Object> internalHandoff,
        @JsonProperty("liability_determined") boolean liabilityDetermined,
        @JsonProperty("remedy_recommended") boolean remedyRecommended,
        @JsonProperty("knowledge_answer_mode") String knowledgeAnswerMode,
        double confidence) {

    // 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceVerificationSuggestion」。
    // 类型职责：定义证据核验Suggestion跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record EvidenceVerificationSuggestion(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("suggestion") String suggestion,
            @JsonProperty("confidence_score") double confidenceScore) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAuthenticityFlag」。
    // 类型职责：定义证据真实性Flag跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record EvidenceAuthenticityFlag(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("flag_type") String flagType,
            @JsonProperty("description") String description,
            @JsonProperty("severity") String severity) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAssessment」。
    // 类型职责：定义证据Assessment跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceAssessment」、「EvidenceAssessment」。
    // 协作关系：主要由 「EvidenceAgentTurnServiceTest.assessment」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceAssessment(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("analysis_method") String analysisMethod,
            @JsonProperty("inspected_modalities") List<String> inspectedModalities,
            @JsonProperty("fact_links") List<Map<String, Object>> factLinks,
            @JsonProperty("authenticity_score") double authenticityScore,
            @JsonProperty("relevance_score") double relevanceScore,
            @JsonProperty("completeness_score") double completenessScore,
            @JsonProperty("assessment_confidence") double assessmentConfidence,
            @JsonProperty("source_basis") List<String> sourceBasis,
            @JsonProperty("supported_fact_ids") List<String> supportedFactIds,
            @JsonProperty("unsupported_claims") List<String> unsupportedClaims,
            @JsonProperty("formation_time_assessment") String formationTimeAssessment,
            List<Map<String, Object>> findings,
            List<String> limitations,
            @JsonProperty("risk_flags") List<Map<String, Object>> riskFlags,
            String recommendation,
            @JsonProperty("human_review") HumanReview humanReview,
            @JsonProperty("asset_audit") Map<String, Object> assetAudit,
            String summary) {

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,HumanReview,Map,String)」。
        // 具体功能：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,HumanReview,Map,String)」：使用 「evidenceId」(String)、「analysisMethod」(String)、「inspectedModalities」(List)、「factLinks」(List)、「authenticityScore」(double)、「relevanceScore」(double)、「completenessScore」(double)、「assessmentConfidence」(double)、「findings」(List)、「limitations」(List)、「riskFlags」(List)、「recommendation」(String)、「humanReview」(HumanReview)、「assetAudit」(Map)、「summary」(String) 初始化「EvidenceAssessment」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
        // 上游调用：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,HumanReview,Map,String)」的上游创建点包括 「EvidenceAgentTurnServiceTest.assessment」。
        // 下游影响：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,HumanReview,Map,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,HumanReview,Map,String)」负责主链路中的“证据Assessment”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        public EvidenceAssessment(
                String evidenceId,
                String analysisMethod,
                List<String> inspectedModalities,
                List<Map<String, Object>> factLinks,
                double authenticityScore,
                double relevanceScore,
                double completenessScore,
                double assessmentConfidence,
                List<Map<String, Object>> findings,
                List<String> limitations,
                List<Map<String, Object>> riskFlags,
                String recommendation,
                HumanReview humanReview,
                Map<String, Object> assetAudit,
                String summary) {
            this(
                    evidenceId,
                    analysisMethod,
                    inspectedModalities,
                    factLinks,
                    authenticityScore,
                    relevanceScore,
                    completenessScore,
                    assessmentConfidence,
                    List.of("旧构造器未提供独立来源依据。"),
                    List.of(),
                    List.of(),
                    "形成时间尚待核验。",
                    findings,
                    limitations,
                    riskFlags,
                    recommendation,
                    humanReview,
                    assetAudit,
                    summary);
        }

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,List,List,List,String,HumanReview,Map,String)」。
        // 具体功能：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,List,List,List,String,HumanReview,Map,String)」：在不可变「EvidenceAssessment」写入组件前校验 「evidenceId」(String)、「analysisMethod」(String)、「inspectedModalities」(List)、「factLinks」(List)、「authenticityScore」(double)、「relevanceScore」(double)、「completenessScore」(double)、「assessmentConfidence」(double)、「sourceBasis」(List)、「supportedFactIds」(List)、「unsupportedClaims」(List)、「formationTimeAssessment」(String)、「findings」(List)、「limitations」(List)、「riskFlags」(List)、「recommendation」(String)、「humanReview」(HumanReview)、「assetAudit」(Map)、「summary」(String)，非法输入会抛出 「IllegalArgumentException」；并通过 「HumanReview.notRequired」、「immutableList」、「validateScore」 做标准化或防御性复制。
        // 上游调用：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,List,List,List,String,HumanReview,Map,String)」的上游创建点包括 「EvidenceAgentTurnServiceTest.assessment」。
        // 下游影响：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,List,List,List,String,HumanReview,Map,String)」向下依次触达 「HumanReview.notRequired」、「immutableList」、「validateScore」。
        // 系统意义：「EvidenceAgentTurnResult.EvidenceAssessment.EvidenceAssessment(String,String,List,List,double,double,double,double,List,List,List,String,List,List,List,String,HumanReview,Map,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public EvidenceAssessment {
            inspectedModalities = immutableList(inspectedModalities);
            factLinks = immutableList(factLinks);
            sourceBasis = immutableList(sourceBasis);
            supportedFactIds = immutableList(supportedFactIds);
            unsupportedClaims = immutableList(unsupportedClaims);
            formationTimeAssessment =
                    formationTimeAssessment == null ? "" : formationTimeAssessment;
            findings = immutableList(findings);
            limitations = immutableList(limitations);
            riskFlags = immutableList(riskFlags);
            assetAudit = assetAudit == null ? Map.of() : Map.copyOf(assetAudit);
            humanReview = humanReview == null ? HumanReview.notRequired() : humanReview;
            validateScore(authenticityScore, "authenticity_score");
            validateScore(relevanceScore, "relevance_score");
            validateScore(completenessScore, "completeness_score");
            validateScore(assessmentConfidence, "assessment_confidence");
            if (!List.of("TEXT_ONLY", "MULTIMODAL", "HYBRID").contains(analysisMethod)) {
                throw new IllegalArgumentException("invalid evidence assessment analysis_method");
            }
            if (!List.of("PLAUSIBLE", "SUSPICIOUS", "NEEDS_HUMAN_REVIEW")
                    .contains(recommendation)) {
                throw new IllegalArgumentException("invalid evidence assessment recommendation");
            }
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「HumanReview」。
    // 类型职责：定义人工审核跨层传递时使用的不可变数据契约；本类型显式提供 「HumanReview」、「notRequired」。
    // 协作关系：主要由 「EvidenceAssessment.EvidenceAssessment」、「EvidenceAgentTurnServiceTest.assessment」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HumanReview(
            boolean required,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            List<String> instructions) {

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.HumanReview.HumanReview(boolean,List,List)」。
        // 具体功能：「EvidenceAgentTurnResult.HumanReview.HumanReview(boolean,List,List)」：在不可变「HumanReview」写入组件前校验 「required」(boolean)、「reasonCodes」(List)、「instructions」(List)，并通过 「immutableList」 做标准化或防御性复制。
        // 上游调用：「EvidenceAgentTurnResult.HumanReview.HumanReview(boolean,List,List)」的上游创建点包括 「HumanReview.notRequired」、「EvidenceAgentTurnServiceTest.assessment」。
        // 下游影响：「EvidenceAgentTurnResult.HumanReview.HumanReview(boolean,List,List)」向下依次触达 「immutableList」。
        // 系统意义：「EvidenceAgentTurnResult.HumanReview.HumanReview(boolean,List,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public HumanReview {
            reasonCodes = immutableList(reasonCodes);
            instructions = immutableList(instructions);
        }

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.HumanReview.notRequired()」。
        // 具体功能：「EvidenceAgentTurnResult.HumanReview.notRequired()」：构建不是否需要，最终返回「HumanReview」。
        // 上游调用：「EvidenceAgentTurnResult.HumanReview.notRequired()」的上游调用点包括 「EvidenceAssessment.EvidenceAssessment」。
        // 下游影响：「EvidenceAgentTurnResult.HumanReview.notRequired()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HumanReview」交给调用方。
        // 系统意义：「EvidenceAgentTurnResult.HumanReview.notRequired()」负责主链路中的“不是否需要”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static HumanReview notRequired() {
            return new HumanReview(false, List.of(), List.of());
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,boolean,boolean,String,double)」。
    // 具体功能：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,boolean,boolean,String,double)」：使用 「roomUtterance」(String)、「memoryPatch」(JsonNode)、「canvasOperations」(JsonNode)、「referencedEvidenceIds」(List)、「liabilityDetermined」(boolean)、「remedyRecommended」(boolean)、「knowledgeAnswerMode」(String)、「confidence」(double) 初始化「EvidenceAgentTurnResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,boolean,boolean,String,double)」的上游创建点包括 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」。
    // 下游影响：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,boolean,boolean,String,double)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,boolean,boolean,String,double)」负责主链路中的“证据Agent轮次结果”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceAgentTurnResult(
            String roomUtterance,
            JsonNode memoryPatch,
            JsonNode canvasOperations,
            List<String> referencedEvidenceIds,
            boolean liabilityDetermined,
            boolean remedyRecommended,
            String knowledgeAnswerMode,
            double confidence) {
        this(
                roomUtterance,
                memoryPatch,
                canvasOperations,
                referencedEvidenceIds,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                liabilityDetermined,
                remedyRecommended,
                knowledgeAnswerMode,
                confidence);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,boolean,boolean,String,double)」。
    // 具体功能：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,boolean,boolean,String,double)」：使用 「roomUtterance」(String)、「memoryPatch」(JsonNode)、「canvasOperations」(JsonNode)、「referencedEvidenceIds」(List)、「verificationSuggestions」(List)、「authenticityFlags」(List)、「evidenceAssessments」(List)、「liabilityDetermined」(boolean)、「remedyRecommended」(boolean)、「knowledgeAnswerMode」(String)、「confidence」(double) 初始化「EvidenceAgentTurnResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,boolean,boolean,String,double)」的上游创建点包括 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」。
    // 下游影响：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,boolean,boolean,String,double)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,boolean,boolean,String,double)」负责主链路中的“证据Agent轮次结果”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceAgentTurnResult(
            String roomUtterance,
            JsonNode memoryPatch,
            JsonNode canvasOperations,
            List<String> referencedEvidenceIds,
            List<EvidenceVerificationSuggestion> verificationSuggestions,
            List<EvidenceAuthenticityFlag> authenticityFlags,
            List<EvidenceAssessment> evidenceAssessments,
            boolean liabilityDetermined,
            boolean remedyRecommended,
            String knowledgeAnswerMode,
            double confidence) {
        this(
                roomUtterance,
                memoryPatch,
                canvasOperations,
                referencedEvidenceIds,
                verificationSuggestions,
                authenticityFlags,
                evidenceAssessments,
                List.of(),
                List.of(),
                Map.of(),
                liabilityDetermined,
                remedyRecommended,
                knowledgeAnswerMode,
                confidence);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,List,List,Map,boolean,boolean,String,double)」。
    // 具体功能：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,List,List,Map,boolean,boolean,String,double)」：在不可变「EvidenceAgentTurnResult」写入组件前校验 「roomUtterance」(String)、「memoryPatch」(JsonNode)、「canvasOperations」(JsonNode)、「referencedEvidenceIds」(List)、「verificationSuggestions」(List)、「authenticityFlags」(List)、「evidenceAssessments」(List)、「factMatrixPatch」(List)、「humanReviewTasks」(List)、「internalHandoff」(Map)、「liabilityDetermined」(boolean)、「remedyRecommended」(boolean)、「knowledgeAnswerMode」(String)、「confidence」(double)，并通过 「JsonNodeFactory.instance.objectNode」、「JsonNodeFactory.instance.arrayNode」 做标准化或防御性复制。
    // 上游调用：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,List,List,Map,boolean,boolean,String,double)」的上游创建点包括 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」。
    // 下游影响：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,List,List,Map,boolean,boolean,String,double)」向下依次触达 「JsonNodeFactory.instance.objectNode」、「JsonNodeFactory.instance.arrayNode」。
    // 系统意义：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,List,List,List,Map,boolean,boolean,String,double)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceAgentTurnResult {
        memoryPatch = memoryPatch == null ? JsonNodeFactory.instance.objectNode() : memoryPatch;
        canvasOperations =
                canvasOperations == null ? JsonNodeFactory.instance.arrayNode() : canvasOperations;
        referencedEvidenceIds =
                referencedEvidenceIds == null ? List.of() : List.copyOf(referencedEvidenceIds);
        verificationSuggestions =
                verificationSuggestions == null ? List.of() : List.copyOf(verificationSuggestions);
        authenticityFlags = authenticityFlags == null ? List.of() : List.copyOf(authenticityFlags);
        evidenceAssessments =
                evidenceAssessments == null ? List.of() : List.copyOf(evidenceAssessments);
        factMatrixPatch = factMatrixPatch == null ? List.of() : List.copyOf(factMatrixPatch);
        humanReviewTasks = humanReviewTasks == null ? List.of() : List.copyOf(humanReviewTasks);
        internalHandoff = internalHandoff == null ? Map.of() : Map.copyOf(internalHandoff);
        knowledgeAnswerMode = knowledgeAnswerMode == null ? "NONE" : knowledgeAnswerMode;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,boolean,boolean,String,double)」。
    // 具体功能：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,boolean,boolean,String,double)」：使用 「roomUtterance」(String)、「memoryPatch」(JsonNode)、「canvasOperations」(JsonNode)、「referencedEvidenceIds」(List)、「verificationSuggestions」(List)、「authenticityFlags」(List)、「liabilityDetermined」(boolean)、「remedyRecommended」(boolean)、「knowledgeAnswerMode」(String)、「confidence」(double) 初始化「EvidenceAgentTurnResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,boolean,boolean,String,double)」的上游创建点包括 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」。
    // 下游影响：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,boolean,boolean,String,double)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceAgentTurnResult.EvidenceAgentTurnResult(String,JsonNode,JsonNode,List,List,List,boolean,boolean,String,double)」负责主链路中的“证据Agent轮次结果”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceAgentTurnResult(
            String roomUtterance,
            JsonNode memoryPatch,
            JsonNode canvasOperations,
            List<String> referencedEvidenceIds,
            List<EvidenceVerificationSuggestion> verificationSuggestions,
            List<EvidenceAuthenticityFlag> authenticityFlags,
            boolean liabilityDetermined,
            boolean remedyRecommended,
            String knowledgeAnswerMode,
            double confidence) {
        this(
                roomUtterance,
                memoryPatch,
                canvasOperations,
                referencedEvidenceIds,
                verificationSuggestions,
                authenticityFlags,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                liabilityDetermined,
                remedyRecommended,
                knowledgeAnswerMode,
                confidence);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.validateScore(double,String)」。
    // 具体功能：「EvidenceAgentTurnResult.validateScore(double,String)」：校验分数；实际协作者为 「Double.isFinite」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnResult.validateScore(double,String)」只由「EvidenceAgentTurnResult」内部流程使用，负责封装“分数”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceAgentTurnResult.validateScore(double,String)」向下依次触达 「Double.isFinite」。
    // 系统意义：「EvidenceAgentTurnResult.validateScore(double,String)」在“分数”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void validateScore(double score, String field) {
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnResult.immutableList(List)」。
    // 具体功能：「EvidenceAgentTurnResult.immutableList(List)」：构建immutable列表，最终返回「List<T>」。
    // 上游调用：「EvidenceAgentTurnResult.immutableList(List)」只由「EvidenceAgentTurnResult」内部流程使用，负责封装“immutable列表”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceAgentTurnResult.immutableList(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<T>」交给调用方。
    // 系统意义：「EvidenceAgentTurnResult.immutableList(List)」负责主链路中的“immutable列表”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
