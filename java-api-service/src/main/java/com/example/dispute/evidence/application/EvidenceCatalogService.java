/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「catalog」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceCatalogService」。
// 类型职责：编排证据规则、权限校验与事实读写；本类型显式提供 「EvidenceCatalogService」、「catalog」、「canSeeCatalogItem」、「project」、「readJson」、「confidenceScore」。
// 协作关系：主要由 「EvidenceController.catalog」、「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」、「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden」、「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceCatalogService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.EvidenceCatalogService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository)」。
    // 具体功能：「EvidenceCatalogService.EvidenceCatalogService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository) 并保存为「EvidenceCatalogService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceCatalogService.EvidenceCatalogService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository)」的上游创建点包括 「EvidenceCatalogServiceTest.setUp」、「EvidenceVerificationAndCatalogServiceTest.setUp」。
    // 下游影响：「EvidenceCatalogService.EvidenceCatalogService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceCatalogService.EvidenceCatalogService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository)」负责主链路中的“证据目录服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceCatalogService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.catalog(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceCatalogService.catalog(String,AuthenticatedActor)」：构建目录：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dispute.getInitiatorRole」、「assertCanAccess」，最终返回「RoleScopedEvidenceView」。
    // 上游调用：「EvidenceCatalogService.catalog(String,AuthenticatedActor)」的上游调用点包括 「EvidenceController.catalog」、「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」、「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」、「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden」。
    // 下游影响：「EvidenceCatalogService.catalog(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dispute.getInitiatorRole」、「assertCanAccess」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceCatalogService.catalog(String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public RoleScopedEvidenceView catalog(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        var items =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                        .stream()
                        .filter(item -> canSeeCatalogItem(dispute, item, actor))
                        .map(item -> project(caseId, dispute, item, actor))
                        .toList();
        String initiatorId =
                dispute.getInitiatorRole() == ActorRole.MERCHANT
                        ? dispute.getMerchantId()
                        : dispute.getUserId();
        return new RoleScopedEvidenceView(
                caseId, dispute.getInitiatorRole().name(), initiatorId, items);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.canSeeCatalogItem(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCatalogService.canSeeCatalogItem(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」：判断能否See目录Item；实际协作者为 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」，最终返回「boolean」。
    // 上游调用：「EvidenceCatalogService.canSeeCatalogItem(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCatalogService.catalog」。
    // 下游影响：「EvidenceCatalogService.canSeeCatalogItem(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceCatalogService.canSeeCatalogItem(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」负责主链路中的“See目录Item”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean canSeeCatalogItem(
            FulfillmentCaseEntity dispute, EvidenceItemEntity item, AuthenticatedActor actor) {
        if (actor.role().name().equals(item.getSubmittedByRole())
                && actor.actorId().equals(item.getSubmittedById())) {
            return true;
        }
        if (isHearingPartySharedEvidence(dispute, item, actor)) {
            return true;
        }
        return isPrivilegedEvidenceViewer(actor.role());
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.project(String,FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCatalogService.project(String,FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」：构建角色投影；实际协作者为 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「actor.role」、「item.getSubmittedByRole」、「actor.actorId」；处理的关键状态/协议值包括 「PLATFORM」、「human_review」、「authenticity_score」、「relevance_score」，最终返回「RoleScopedEvidenceView.Item」。
    // 上游调用：「EvidenceCatalogService.project(String,FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCatalogService.catalog」。
    // 下游影响：「EvidenceCatalogService.project(String,FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「actor.role」、「item.getSubmittedByRole」、「actor.actorId」；计算结果以「RoleScopedEvidenceView.Item」交给调用方。
    // 系统意义：「EvidenceCatalogService.project(String,FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」负责主链路中的“角色投影”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private RoleScopedEvidenceView.Item project(
            String caseId,
            FulfillmentCaseEntity dispute,
            EvidenceItemEntity item,
            AuthenticatedActor actor) {
        boolean privileged = isPrivilegedEvidenceViewer(actor.role());
        boolean owns =
                actor.role().name().equals(item.getSubmittedByRole())
                        && actor.actorId().equals(item.getSubmittedById());
        boolean visible =
                privileged
                        || owns
                        || isHearingPartySharedEvidence(dispute, item, actor)
                        || "PLATFORM".equals(item.getVisibility())
                                && actor.role() == ActorRole.CUSTOMER_SERVICE;
        String contentUrl =
                visible
                        ? "/api/disputes/" + caseId + "/evidence/" + item.getId() + "/content"
                        : null;
        Optional<EvidenceVerificationEntity> latestVerification =
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(item.getId())
                        .filter(itemVerification -> itemVerification.getVerificationStatus() != null);
        var status =
                latestVerification
                        .map(EvidenceVerificationEntity::getVerificationStatus)
                        .orElse(null);
        JsonNode agentFindings =
                latestVerification
                        .map(EvidenceVerificationEntity::getAgentFindingsJson)
                        .map(this::readJson)
                        .orElseGet(objectMapper::createObjectNode);
        JsonNode reasons =
                latestVerification
                        .map(EvidenceVerificationEntity::getReasonsJson)
                        .map(this::readJson)
                        .orElseGet(objectMapper::createObjectNode);
        JsonNode submissionMetadata = readJson(item.getMetadataJson());
        Double confidenceScore = confidenceScore(agentFindings);
        JsonNode humanReview = agentFindings.path("human_review");
        return new RoleScopedEvidenceView.Item(
                item.getId(),
                item.getEvidenceType(),
                item.getSubmittedByRole(),
                item.getSubmittedById(),
                item.getVisibility(),
                contentUrl,
                !visible,
                status,
                confidenceScore,
                confidenceLevel(agentFindings, confidenceScore),
                verificationFeedback(agentFindings, reasons),
                item.getSourceType(),
                item.getOriginalFilename(),
                item.getParsedText(),
                item.getSubmissionStatus().name(),
                item.getSubmittedAt(),
                item.getSubmissionBatchId(),
                score(agentFindings, "authenticity_score"),
                score(agentFindings, "relevance_score"),
                score(agentFindings, "completeness_score"),
                score(agentFindings, "assessment_confidence"),
                textList(agentFindings.path("inspected_modalities")),
                textList(agentFindings.path("limitations")),
                latestVerification
                        .map(EvidenceVerificationEntity::isRequiresHumanReview)
                        .orElse(false),
                firstNonEmptyTextList(
                        humanReview.path("reason_codes"),
                        reasons.isArray()
                                ? reasons
                                : reasons.path("human_review_reason_codes")),
                firstNonEmptyTextList(
                        humanReview.path("instructions"),
                        reasons.path("human_review_instructions")),
                textOrNull(submissionMetadata.path("claimed_fact")),
                submissionMetadata.path("truth_attested").asBoolean(false),
                textList(submissionMetadata.path("attestation_scope")),
                textOrNull(submissionMetadata.path("party_capacity")),
                textOrNull(submissionMetadata.path("attestation_version")),
                textOrNull(submissionMetadata.path("forgery_consequence_code")),
                textOrNull(submissionMetadata.path("enforcement_gate")));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.readJson(String)」。
    // 具体功能：「EvidenceCatalogService.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.createObjectNode」、「objectMapper.readTree」，最终返回「JsonNode」。
    // 上游调用：「EvidenceCatalogService.readJson(String)」只由「EvidenceCatalogService」内部流程使用，负责封装“JSON”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceCatalogService.readJson(String)」向下依次触达 「objectMapper.createObjectNode」、「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceCatalogService.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.confidenceScore(JsonNode)」。
    // 具体功能：「EvidenceCatalogService.confidenceScore(JsonNode)」：构建可信度分数；实际协作者为 「Math.max」、「Math.min」、「agentFindings.isMissingNode」、「agentFindings.isNull」；处理的关键状态/协议值包括 「confidence_score」、「confidence」，最终返回「Double」。
    // 上游调用：「EvidenceCatalogService.confidenceScore(JsonNode)」的上游调用点包括 「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.confidenceScore(JsonNode)」向下依次触达 「Math.max」、「Math.min」、「agentFindings.isMissingNode」、「agentFindings.isNull」；计算结果以「Double」交给调用方。
    // 系统意义：「EvidenceCatalogService.confidenceScore(JsonNode)」负责主链路中的“可信度分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static Double confidenceScore(JsonNode agentFindings) {
        if (agentFindings == null || agentFindings.isMissingNode() || agentFindings.isNull()) {
            return null;
        }
        JsonNode value = firstPresent(agentFindings, "confidence_score", "confidence");
        if (value == null || !value.isNumber()) {
            return null;
        }
        double score = value.asDouble();
        if (score > 1.0) {
            return Math.max(0.0, Math.min(1.0, score / 100.0));
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.score(JsonNode,String)」。
    // 具体功能：「EvidenceCatalogService.score(JsonNode,String)」：构建分数；实际协作者为 「Math.max」、「Math.min」、「agentFindings.isMissingNode」、「agentFindings.isNull」，最终返回「Double」。
    // 上游调用：「EvidenceCatalogService.score(JsonNode,String)」的上游调用点包括 「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.score(JsonNode,String)」向下依次触达 「Math.max」、「Math.min」、「agentFindings.isMissingNode」、「agentFindings.isNull」；计算结果以「Double」交给调用方。
    // 系统意义：「EvidenceCatalogService.score(JsonNode,String)」负责主链路中的“分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static Double score(JsonNode agentFindings, String fieldName) {
        if (agentFindings == null || agentFindings.isMissingNode() || agentFindings.isNull()) {
            return null;
        }
        JsonNode value = agentFindings.path(fieldName);
        if (!value.isNumber()) {
            return null;
        }
        double score = value.asDouble();
        return Math.max(0.0, Math.min(1.0, score > 1.0 ? score / 100.0 : score));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.firstNonEmptyTextList(JsonNode,JsonNode)」。
    // 具体功能：「EvidenceCatalogService.firstNonEmptyTextList(JsonNode,JsonNode)」：构建首版非为空文本列表；实际协作者为 「textList」，最终返回「List<String>」。
    // 上游调用：「EvidenceCatalogService.firstNonEmptyTextList(JsonNode,JsonNode)」的上游调用点包括 「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.firstNonEmptyTextList(JsonNode,JsonNode)」向下依次触达 「textList」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceCatalogService.firstNonEmptyTextList(JsonNode,JsonNode)」负责主链路中的“首版非为空文本列表”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static List<String> firstNonEmptyTextList(JsonNode primary, JsonNode fallback) {
        List<String> values = textList(primary);
        return values.isEmpty() ? textList(fallback) : values;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.textList(JsonNode)」。
    // 具体功能：「EvidenceCatalogService.textList(JsonNode)」：构建文本列表；实际协作者为 「node.isArray」、「item.isTextual」、「item.asText」，最终返回「List<String>」。
    // 上游调用：「EvidenceCatalogService.textList(JsonNode)」的上游调用点包括 「EvidenceCatalogService.project」、「EvidenceCatalogService.firstNonEmptyTextList」。
    // 下游影响：「EvidenceCatalogService.textList(JsonNode)」向下依次触达 「node.isArray」、「item.isTextual」、「item.asText」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceCatalogService.textList(JsonNode)」负责主链路中的“文本列表”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(
                item -> {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        values.add(item.asText());
                    }
                });
        return List.copyOf(values);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isBlank() ? null : value;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.confidenceLevel(JsonNode,Double)」。
    // 具体功能：「EvidenceCatalogService.confidenceLevel(JsonNode,Double)」：构建可信度级别；实际协作者为 「explicit.isTextual」、「explicit.asText」、「firstPresent」；处理的关键状态/协议值包括 「confidence_level」、「confidenceLevel」、「HIGH」、「MEDIUM」，最终返回「String」。
    // 上游调用：「EvidenceCatalogService.confidenceLevel(JsonNode,Double)」的上游调用点包括 「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.confidenceLevel(JsonNode,Double)」向下依次触达 「explicit.isTextual」、「explicit.asText」、「firstPresent」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceCatalogService.confidenceLevel(JsonNode,Double)」负责主链路中的“可信度级别”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String confidenceLevel(JsonNode agentFindings, Double confidenceScore) {
        JsonNode explicit = firstPresent(agentFindings, "confidence_level", "confidenceLevel");
        if (explicit != null && explicit.isTextual() && !explicit.asText().isBlank()) {
            return explicit.asText();
        }
        if (confidenceScore == null) {
            return null;
        }
        if (confidenceScore >= 0.8) {
            return "HIGH";
        }
        if (confidenceScore >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.verificationFeedback(JsonNode,JsonNode)」。
    // 具体功能：「EvidenceCatalogService.verificationFeedback(JsonNode,JsonNode)」：构建核验反馈；实际协作者为 「feedback.isTextual」、「feedback.asText」、「reasonSummary.isTextual」、「reasonSummary.asText」；处理的关键状态/协议值包括 「verification_feedback」、「verificationFeedback」、「suggestion」、「summary」，最终返回「String」。
    // 上游调用：「EvidenceCatalogService.verificationFeedback(JsonNode,JsonNode)」的上游调用点包括 「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.verificationFeedback(JsonNode,JsonNode)」向下依次触达 「feedback.isTextual」、「feedback.asText」、「reasonSummary.isTextual」、「reasonSummary.asText」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceCatalogService.verificationFeedback(JsonNode,JsonNode)」负责主链路中的“核验反馈”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String verificationFeedback(JsonNode agentFindings, JsonNode reasons) {
        JsonNode feedback =
                firstPresent(
                        agentFindings,
                        "verification_feedback",
                        "verificationFeedback",
                        "suggestion",
                        "summary");
        if (feedback != null && feedback.isTextual() && !feedback.asText().isBlank()) {
            return feedback.asText();
        }
        JsonNode reasonSummary = firstPresent(reasons, "summary", "reason", "feedback");
        if (reasonSummary != null && reasonSummary.isTextual() && !reasonSummary.asText().isBlank()) {
            return reasonSummary.asText();
        }
        return null;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.firstPresent(JsonNode)」。
    // 具体功能：「EvidenceCatalogService.firstPresent(JsonNode)」：构建首版存在值；实际协作者为 「node.isMissingNode」、「node.isNull」、「value.isMissingNode」、「value.isNull」，最终返回「JsonNode」。
    // 上游调用：「EvidenceCatalogService.firstPresent(JsonNode)」的上游调用点包括 「EvidenceCatalogService.confidenceScore」、「EvidenceCatalogService.confidenceLevel」、「EvidenceCatalogService.verificationFeedback」。
    // 下游影响：「EvidenceCatalogService.firstPresent(JsonNode)」向下依次触达 「node.isMissingNode」、「node.isNull」、「value.isMissingNode」、「value.isNull」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceCatalogService.firstPresent(JsonNode)」负责主链路中的“首版存在值”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static JsonNode firstPresent(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.isPrivilegedEvidenceViewer(ActorRole)」。
    // 具体功能：「EvidenceCatalogService.isPrivilegedEvidenceViewer(ActorRole)」：判断是否高权限级别证据Viewer，最终返回「boolean」。
    // 上游调用：「EvidenceCatalogService.isPrivilegedEvidenceViewer(ActorRole)」的上游调用点包括 「EvidenceCatalogService.canSeeCatalogItem」、「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.isPrivilegedEvidenceViewer(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceCatalogService.isPrivilegedEvidenceViewer(ActorRole)」负责主链路中的“高权限级别证据Viewer”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean isPrivilegedEvidenceViewer(ActorRole role) {
        return role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.isHearingPartySharedEvidence(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCatalogService.isHearingPartySharedEvidence(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」：判断是否庭审当事方Shared证据；实际协作者为 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」；处理的关键状态/协议值包括 「HEARING」、「PARTIES」，最终返回「boolean」。
    // 上游调用：「EvidenceCatalogService.isHearingPartySharedEvidence(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCatalogService.canSeeCatalogItem」、「EvidenceCatalogService.project」。
    // 下游影响：「EvidenceCatalogService.isHearingPartySharedEvidence(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceCatalogService.isHearingPartySharedEvidence(FulfillmentCaseEntity,EvidenceItemEntity,AuthenticatedActor)」负责主链路中的“庭审当事方Shared证据”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean isHearingPartySharedEvidence(
            FulfillmentCaseEntity dispute, EvidenceItemEntity item, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            return false;
        }
        if (actor.role().name().equals(item.getSubmittedByRole())
                && actor.actorId().equals(item.getSubmittedById())) {
            return true;
        }
        boolean hearingRoom = "HEARING".equalsIgnoreCase(dispute.getCurrentRoom());
        return hearingRoom
                && "PARTIES".equals(item.getVisibility())
                && item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCatalogService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCatalogService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can访问；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「EvidenceCatalogService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCatalogService.catalog」。
    // 下游影响：「EvidenceCatalogService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「EvidenceCatalogService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void assertCanAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) throw new ForbiddenException("actor cannot access evidence catalog");
    }
}
