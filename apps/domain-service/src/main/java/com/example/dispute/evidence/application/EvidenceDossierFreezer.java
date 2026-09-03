/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：承载证据卷宗版本冻结在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「targetVersion」、「latestVersion」、「freeze」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.evidence.domain.FactEvidenceRelationCanonicalizer;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceDossierFreezer」。
// 类型职责：承载证据卷宗版本冻结在当前业务模块中的规则与协作边界；本类型显式提供 「EvidenceDossierFreezer」、「targetVersion」、「latestVersion」、「freeze」、「createFrozen」、「withLatestStatus」。
// 协作关系：主要由 「EvidenceAgentTurnService.freezeHearingSupplementDossier」、「EvidenceCompletionService.complete」、「EvidenceCompletionService.completionVersion」、「EvidenceCompletionService.expire」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceDossierFreezer {

    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceDossierItemRepository dossierItemRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」。
    // 具体功能：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」：通过构造器接收 「dossierRepository」(EvidenceDossierRepository)、「dossierItemRepository」(EvidenceDossierItemRepository)、「evidenceRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「EvidenceDossierFreezer」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」的上游创建点包括 「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」、「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus」、「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」。
    // 下游影响：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」负责主链路中的“证据卷宗冻结器”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public EvidenceDossierFreezer(
            EvidenceDossierRepository dossierRepository,
            EvidenceDossierItemRepository dossierItemRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dossierRepository = dossierRepository;
        this.dossierItemRepository = dossierItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.targetVersion(String)」。
    // 具体功能：「EvidenceDossierFreezer.targetVersion(String)」：读取案件最新卷宗版本并返回下一可冻结版本；没有历史卷宗时从 1 开始，最终返回「int」。
    // 上游调用：「EvidenceDossierFreezer.targetVersion(String)」的上游调用点包括 「EvidenceCompletionService.completionVersion」、「EvidenceAgentTurnService.freezeHearingSupplementDossier」、「EvidenceCompletionServiceTest.setUp」。
    // 下游影响：「EvidenceDossierFreezer.targetVersion(String)」向下依次触达 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「dossier.getDossierVersion」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierFreezer.targetVersion(String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public int targetVersion(String caseId) {
        return dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .map(dossier -> dossier.getDossierVersion() + 1)
                        .orElse(1);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.latestVersion(String)」。
    // 具体功能：「EvidenceDossierFreezer.latestVersion(String)」：读取案件当前最高卷宗版本；尚未生成卷宗时返回 0，供完成状态和庭审入口查询，最终返回「int」。
    // 上游调用：「EvidenceDossierFreezer.latestVersion(String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.status」。
    // 下游影响：「EvidenceDossierFreezer.latestVersion(String)」向下依次触达 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierFreezer.latestVersion(String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public int latestVersion(String caseId) {
        return dossierRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .map(EvidenceDossierEntity::getDossierVersion)
                .orElseThrow(() -> new IllegalArgumentException("dossier not found"));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.freeze(String,int,String)」。
    // 具体功能：「EvidenceDossierFreezer.freeze(String,int,String)」：以 caseId+version 实现封卷幂等：已有同版本直接返回，否则调用 createFrozen 生成一次不可变快照，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierFreezer.freeze(String,int,String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」、「EvidenceAgentTurnService.freezeHearingSupplementDossier」。
    // 下游影响：「EvidenceDossierFreezer.freeze(String,int,String)」向下依次触达 「dossierRepository.findByCaseIdAndDossierVersion」、「createFrozen」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierFreezer.freeze(String,int,String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceDossierEntity freeze(
            String caseId, int targetVersion, String actorId) {
        return dossierRepository
                .findByCaseIdAndDossierVersion(caseId, targetVersion)
                .orElseGet(() -> createFrozen(caseId, targetVersion, actorId));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.createFrozen(String,int,String)」。
    // 具体功能：「EvidenceDossierFreezer.createFrozen(String,int,String)」：按发生时间读取所有未删除证据并绑定各自最新核验版本，计算真实性、相关性、完整性和评估置信度；生成事实-证据矩阵、双方摘要、已确认/争议事实、证据缺口、人工任务与内部移交，最后保存卷宗头和顺序快照项，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierFreezer.createFrozen(String,int,String)」的上游调用点包括 「EvidenceDossierFreezer.freeze」。
    // 下游影响：「EvidenceDossierFreezer.createFrozen(String,int,String)」向下依次触达 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossierRepository.save」、「dossierItemRepository.saveAll」、「EvidenceDossierEntity.frozen」；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.createFrozen(String,int,String)」负责主链路中的“冻结”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private EvidenceDossierEntity createFrozen(
            String caseId, int targetVersion, String actorId) {
        ObjectNode caseMatrix = authoritativeCaseFactMatrix(caseId);
        // 封卷只纳入已正式提交且未被核验拒绝的证据；原始 EvidenceItem 不会被修改。
        // 每项同时绑定“冻结时刻的最新核验版本”，日后重跑不会改写旧卷宗结论。
        List<IncludedEvidence> included =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId)
                        .stream()
                        .filter(item -> item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED)
                        .map(this::withLatestStatus)
                        .filter(
                                item ->
                                        item.status()
                                                != EvidenceVerificationStatus.REJECTED)
                        .toList();

        List<Map<String, Object>> timeline = new ArrayList<>();
        List<Map<String, Object>> evidenceItems = new ArrayList<>();
        List<String> unmappedEvidence = new ArrayList<>();
        List<FrozenFactEvidenceLink> frozenLinks = new ArrayList<>();
        Map<String, PartySummaryAccumulator> partySummary = new LinkedHashMap<>();
        partySummary.put("USER", new PartySummaryAccumulator());
        partySummary.put("MERCHANT", new PartySummaryAccumulator());
        for (IncludedEvidence item : included) {
            EvidenceItemEntity evidence = item.evidence();
            JsonNode assessment = agentFindings(item);
            // v3 scores are independent model authority.  The freezer copies them
            // verbatim and never supplies a default, normalizes, clamps or combines them.
            Double authenticityScore = verificationScore(item, "authenticity_score");
            Double relevanceScore = verificationScore(item, "relevance_score");
            Double completenessScore = verificationScore(item, "completeness_score");
            Double assessmentConfidence = verificationScore(item, "assessment_confidence");
            boolean assessmentComplete =
                    assessmentComplete(
                            assessment,
                            authenticityScore,
                            relevanceScore,
                            completenessScore,
                            assessmentConfidence);
            String claimedFact = claimedFact(evidence);
            List<FactLinkSnapshot> factLinks = factLinks(item);
            boolean structuredFactLinks = hasStructuredFactLinks(item);
            boolean requiresHumanReview =
                    item.verification() != null && item.verification().isRequiresHumanReview();
            Map<String, Object> timelineEntry = new LinkedHashMap<>();
            timelineEntry.put("evidence_id", evidence.getId());
            timelineEntry.put("evidence_type", evidence.getEvidenceType());
            timelineEntry.put("party_role", evidence.getSubmittedByRole());
            timelineEntry.put("file_name", evidence.getOriginalFilename());
            timelineEntry.put(
                    "occurred_at",
                    evidence.getOccurredAt() == null
                            ? evidence.getCreatedAt()
                            : evidence.getOccurredAt());
            timelineEntry.put("verification_status", statusName(item.status()));
            timeline.add(timelineEntry);

            Map<String, Object> evidenceItem = new LinkedHashMap<>();
            evidenceItem.put("evidence_id", evidence.getId());
            evidenceItem.put("party_role", evidence.getSubmittedByRole());
            evidenceItem.put("file_name", evidence.getOriginalFilename());
            evidenceItem.put("evidence_type", evidence.getEvidenceType());
            evidenceItem.put("parsed_text", abbreviate(evidence.getParsedText(), 180));
            evidenceItem.put("claimed_fact", claimedFact);
            evidenceItem.put("submission_attestation", submissionAttestation(evidence));
            evidenceItem.put(
                    "party_capacity", evidenceMetadata(evidence).path("party_capacity").asText(null));
            evidenceItem.put(
                    "forgery_consequence_code",
                    evidenceMetadata(evidence).path("forgery_consequence_code").asText(null));
            evidenceItem.put(
                    "enforcement_gate",
                    evidenceMetadata(evidence).path("enforcement_gate").asText(null));
            evidenceItem.put(
                    "supports_fact_ids",
                    factLinks.stream()
                            .filter(link -> "CONTENT_SUPPORTS".equals(link.relation()))
                            .map(FactLinkSnapshot::factId)
                            .toList());
            evidenceItem.put(
                    "opposes_fact_ids",
                    factLinks.stream()
                            .filter(link -> "CONTENT_CONTRADICTS".equals(link.relation()))
                            .map(FactLinkSnapshot::factId)
                            .toList());
            evidenceItem.put("authenticity_score", authenticityScore);
            evidenceItem.put(
                    "authenticity_score_explanation",
                    assessment.path("authenticity_score_explanation").asText());
            evidenceItem.put("relevance_score", relevanceScore);
            evidenceItem.put(
                    "relevance_score_explanation",
                    assessment.path("relevance_score_explanation").asText());
            evidenceItem.put("completeness_score", completenessScore);
            evidenceItem.put(
                    "completeness_score_explanation",
                    assessment.path("completeness_score_explanation").asText());
            evidenceItem.put("assessment_confidence", assessmentConfidence);
            evidenceItem.put(
                    "assessment_confidence_explanation",
                    assessment.path("assessment_confidence_explanation").asText());
            evidenceItem.put("risk_level", assessment.path("risk_level").asText());
            evidenceItem.put("risk_explanation", assessment.path("risk_explanation").asText());
            evidenceItem.put("source_basis", assessment.path("source_basis").deepCopy());
            evidenceItem.put(
                    "formation_time_assessment",
                    assessment.path("formation_time_assessment").asText());
            evidenceItem.put("findings", assessment.path("findings").deepCopy());
            evidenceItem.put("limitations", assessment.path("limitations").deepCopy());
            evidenceItem.put(
                    "unsupported_claims", assessment.path("unsupported_claims").deepCopy());
            evidenceItem.put(
                    "assessment_public_text",
                    assessment.path("assessment_public_text").asText());
            evidenceItem.put("assessment_complete", assessmentComplete);
            evidenceItem.put("requires_human_review", requiresHumanReview);
            evidenceItem.put("reason_details", assessment.path("reason_details").deepCopy());
            evidenceItem.put("verification_status", statusName(item.status()));
            evidenceItems.add(evidenceItem);

            if (structuredFactLinks) {
                // Agent 已给结构化 fact_links 时严格使用该映射；低相关材料允许空 links，
                // 只进入 unmapped_evidence 与人工复核区，绝不制造伪 fact_id 污染正式矩阵。
                if (factLinks.isEmpty()) {
                    unmappedEvidence.add(evidence.getId());
                }
                for (FactLinkSnapshot link : factLinks) {
                    frozenLinks.add(
                            new FrozenFactEvidenceLink(
                                    evidence.getId(),
                                    evidence.getSubmissionBatchId(),
                                    link,
                                    requiresHumanReview));
                }
            } else {
                // 正式版不再为缺少结构化映射的历史结果合成事实行。该材料仍保留在
                // evidence_items/unmapped_evidence 中，供人工复核，但不能进入事实矩阵。
                unmappedEvidence.add(evidence.getId());
            }
            partySummary
                    .computeIfAbsent(
                            defaultText(evidence.getSubmittedByRole(), "UNKNOWN"),
                            ignored -> new PartySummaryAccumulator())
                    .add(
                            evidence,
                            item.status(),
                            requiresHumanReview);
        }

        // summary 面向 API/法官快速阅读；matrixSummary 保存事实-证据关系、
        // 人工任务和内部移交，避免内部关注点直接暴露给普通当事人。
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evidence_count", included.size());
        summary.put("evidence_items", evidenceItems);
        summary.put(
                "verification_statuses",
                included.stream().map(item -> statusName(item.status())).toList());
        summary.put("party_evidence_summary", partyEvidenceSummary(partySummary));
        summary.put("evidence_gaps", evidenceGaps(partySummary));
        summary.put("handoff_notes", evidenceHandoffNotes(included));
        summary.put("frozen", true);

        String dossierId = "DOSSIER_" + compactUuid();
        Map<String, Object> matrixSummary = new LinkedHashMap<>();
        matrixSummary.put("schema_version", "evidence-dossier-matrix-summary.v3");
        matrixSummary.put(
                "fact_evidence_matrix",
                frozenFactEvidenceMatrix(
                        caseId,
                        targetVersion,
                        dossierId,
                        caseMatrix,
                        frozenLinks));
        matrixSummary.put(
                "unmapped_evidence",
                unmappedEvidence);
        matrixSummary.put(
                "handoff_notes",
                summary.get("handoff_notes"));
        matrixSummary.put("human_review_reasons", humanReviewReasons(included));
        matrixSummary.put("internal_handoffs", internalHandoffs(included));

        EvidenceDossierEntity dossier =
                dossierRepository.save(
                        EvidenceDossierEntity.frozen(
                                dossierId,
                                caseId,
                                targetVersion,
                                actorId,
                                json(summary),
                                json(timeline),
                                json(matrixSummary)));

        int sequence = 1;
        // 卷宗头保存聚合摘要，DossierItem 逐项保存冻结快照与稳定顺序。
        // 后续 EvidenceItem/OCR 文本更新不会改变本版本中法官实际看到的材料。
        List<EvidenceDossierItemEntity> snapshots = new ArrayList<>();
        for (IncludedEvidence item : included) {
            EvidenceItemEntity evidence = item.evidence();
            JsonNode assessment = agentFindings(item);
            Double authenticityScore = verificationScore(item, "authenticity_score");
            Double relevanceScore = verificationScore(item, "relevance_score");
            Double completenessScore = verificationScore(item, "completeness_score");
            Double assessmentConfidence = verificationScore(item, "assessment_confidence");
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("evidence_type", evidence.getEvidenceType());
            snapshot.put("source_type", evidence.getSourceType());
            snapshot.put("file_hash", evidence.getFileHash());
            snapshot.put("visibility", evidence.getVisibility());
            snapshot.put("submitted_by_role", evidence.getSubmittedByRole());
            snapshot.put("original_filename", evidence.getOriginalFilename());
            snapshot.put("parse_status", parseStatusName(evidence.getParseStatus()));
            snapshot.put("claimed_fact", claimedFact(evidence));
            snapshot.put("submission_attestation", submissionAttestation(evidence));
            snapshot.put(
                    "party_capacity", evidenceMetadata(evidence).path("party_capacity").asText(null));
            snapshot.put(
                    "forgery_consequence_code",
                    evidenceMetadata(evidence).path("forgery_consequence_code").asText(null));
            snapshot.put(
                    "enforcement_gate",
                    evidenceMetadata(evidence).path("enforcement_gate").asText(null));
            snapshot.put("verification_status", statusName(item.status()));
            snapshot.put("authenticity_score", authenticityScore);
            snapshot.put(
                    "authenticity_score_explanation",
                    assessment.path("authenticity_score_explanation").asText());
            snapshot.put("relevance_score", relevanceScore);
            snapshot.put(
                    "relevance_score_explanation",
                    assessment.path("relevance_score_explanation").asText());
            snapshot.put("completeness_score", completenessScore);
            snapshot.put(
                    "completeness_score_explanation",
                    assessment.path("completeness_score_explanation").asText());
            snapshot.put(
                    "assessment_confidence",
                    assessmentConfidence);
            snapshot.put(
                    "assessment_confidence_explanation",
                    assessment.path("assessment_confidence_explanation").asText());
            snapshot.put("risk_level", assessment.path("risk_level").asText());
            snapshot.put("risk_explanation", assessment.path("risk_explanation").asText());
            snapshot.put("source_basis", assessment.path("source_basis").deepCopy());
            snapshot.put(
                    "formation_time_assessment",
                    assessment.path("formation_time_assessment").asText());
            snapshot.put("findings", assessment.path("findings").deepCopy());
            snapshot.put("limitations", assessment.path("limitations").deepCopy());
            snapshot.put(
                    "unsupported_claims", assessment.path("unsupported_claims").deepCopy());
            snapshot.put(
                    "assessment_public_text",
                    assessment.path("assessment_public_text").asText());
            snapshot.put(
                    "assessment_complete",
                    assessmentComplete(
                            assessment,
                            authenticityScore,
                            relevanceScore,
                            completenessScore,
                            assessmentConfidence));
            snapshot.put("requires_human_review", item.verification().isRequiresHumanReview());
            snapshot.put("reason_details", assessment.path("reason_details").deepCopy());
            snapshots.add(
                    EvidenceDossierItemEntity.snapshot(
                            "DOSSIER_ITEM_" + compactUuid(),
                            caseId,
                            dossier.getId(),
                            evidence.getId(),
                            sequence++,
                            json(snapshot),
                            clock.instant(),
                            actorId));
        }
        dossierItemRepository.saveAll(snapshots);
        // The caller may continue the same transaction with direct JDBC authority writes.
        // Flush the complete immutable aggregate so its header and snapshots are visible there.
        dossierRepository.flush();
        return dossier;
    }

    private ObjectNode authoritativeCaseFactMatrix(String caseId) {
        if (intakeDossierRepository == null) {
            throw new IllegalStateException(
                    "formal intake dossier authority is required for evidence freeze");
        }
        String dossierJson =
                intakeDossierRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "formal intake dossier is required for evidence freeze"))
                        .getDossierJson();
        try {
            JsonNode candidate = objectMapper.readTree(dossierJson).path("case_fact_matrix");
            if (!candidate.isObject()
                    || !"case_fact_matrix.v2"
                            .equals(candidate.path("schema_version").asText())
                    || !caseId.equals(candidate.path("case_id").asText())
                    || candidate.path("matrix_id").asText("").isBlank()
                    || candidate.path("matrix_version").asInt(0) < 1
                    || !isContentHash(candidate.path("content_hash").asText())) {
                throw new IllegalStateException(
                        "formal case_fact_matrix.v2 authority is invalid for evidence freeze");
            }
            JsonNode rows = candidate.path("fact_rows");
            if (!rows.isArray() || rows.isEmpty()) {
                throw new IllegalStateException(
                        "formal case_fact_matrix.v2 facts are required for evidence freeze");
            }
            Set<String> factIds = new LinkedHashSet<>();
            for (JsonNode row : rows) {
                String factId = row.path("fact_id").asText("").trim();
                if (factId.isBlank() || !factIds.add(factId)) {
                    throw new IllegalStateException(
                            "formal case_fact_matrix.v2 fact authority is invalid");
                }
            }
            return ((ObjectNode) candidate).deepCopy();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("formal intake dossier is invalid JSON", exception);
        }
    }

    private ObjectNode frozenFactEvidenceMatrix(
            String caseId,
            int targetVersion,
            String dossierId,
            ObjectNode caseMatrix,
            List<FrozenFactEvidenceLink> frozenLinks) {
        ObjectNode previous = previousFactEvidenceMatrix(caseId, targetVersion);
        int matrixVersion = previous == null ? 1 : previous.path("matrix_version").asInt() + 1;
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "fact_evidence_matrix.v3");
        matrix.put("case_id", caseId);
        matrix.put(
                "matrix_id",
                "FACT_EVIDENCE_MATRIX_"
                        + UUID.nameUUIDFromBytes(
                                        (caseId + ':' + dossierId + ':' + matrixVersion)
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "")
                                .toUpperCase());
        matrix.put("matrix_version", matrixVersion);
        matrix.put("matrix_status", "FROZEN");
        if (previous == null) {
            matrix.putNull("parent_ref");
        } else {
            ObjectNode parent = matrix.putObject("parent_ref");
            parent.put("matrix_id", previous.path("matrix_id").asText());
            parent.put("matrix_version", previous.path("matrix_version").asInt());
            parent.put("content_hash", previous.path("content_hash").asText());
        }
        matrix.put("case_fact_matrix_id", caseMatrix.path("matrix_id").asText());
        matrix.put("case_fact_matrix_version", caseMatrix.path("matrix_version").asInt());
        matrix.put("case_fact_matrix_hash", caseMatrix.path("content_hash").asText());
        matrix.put("content_hash", "0".repeat(64));
        matrix.putArray("source_refs").add(dossierId);

        Set<String> knownFacts = new LinkedHashSet<>();
        for (JsonNode row : caseMatrix.path("fact_rows")) {
            knownFacts.add(row.path("fact_id").asText());
        }
        Set<String> linkKeys = new LinkedHashSet<>();
        ArrayNode links = matrix.putArray("links");
        for (FrozenFactEvidenceLink frozenLink : frozenLinks) {
            FactLinkSnapshot link = frozenLink.link();
            if (!knownFacts.contains(link.factId())) {
                throw new IllegalStateException(
                        "frozen evidence link references unknown formal fact_id: "
                                + link.factId());
            }
            if (!linkKeys.add(
                    link.factId()
                            + '\u0000'
                            + frozenLink.evidenceId()
                            + '\u0000'
                            + link.sourceUnitId()
                            + '\u0000'
                            + link.observationSlot())) {
                throw new IllegalStateException(
                        "frozen evidence links contain a duplicate formal binding");
            }
            ObjectNode item = links.addObject();
            item.put("fact_id", link.factId());
            item.put("evidence_id", frozenLink.evidenceId());
            item.put("relation", link.relation());
            item.put("reason", defaultText(link.reason(), link.factId()));
            item.put("source_unit_id", link.sourceUnitId());
            item.put("observation_slot", link.observationSlot());
            if (frozenLink.sourceBatchId() == null
                    || frozenLink.sourceBatchId().isBlank()) {
                item.putNull("source_batch_id");
            } else {
                item.put("source_batch_id", frozenLink.sourceBatchId());
            }
        }

        ArrayNode coverage = matrix.putArray("fact_coverage");
        for (String factId : knownFacts) {
            LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
            boolean requiresHumanReview = false;
            for (FrozenFactEvidenceLink link : frozenLinks) {
                if (!factId.equals(link.link().factId())) {
                    continue;
                }
                evidenceIds.add(link.evidenceId());
                requiresHumanReview |= link.requiresHumanReview();
            }
            ObjectNode item = coverage.addObject();
            item.put("fact_id", factId);
            item.put(
                    "coverage_status",
                    requiresHumanReview
                            ? "REQUIRES_HUMAN_REVIEW"
                            : evidenceIds.isEmpty()
                                    ? "NOT_COVERED_BY_FROZEN_DOSSIER"
                                    : "COVERED_BY_FROZEN_DOSSIER");
            item.set("evidence_ids", objectMapper.valueToTree(evidenceIds));
            item.put(
                    "note",
                    evidenceIds.isEmpty()
                            ? "该事实尚未被庭前冻结证据卷宗覆盖。"
                            : requiresHumanReview
                                    ? "该事实已有材料，但至少一份材料需要人工复核。"
                                    : "该事实的关联材料来自庭前冻结证据卷宗。");
        }
        matrix.put("content_hash", pythonContentHash(matrix, "content_hash"));
        return matrix;
    }

    private ObjectNode previousFactEvidenceMatrix(String caseId, int targetVersion) {
        Optional<EvidenceDossierEntity> previous =
                dossierRepository.findTopByCaseIdOrderByDossierVersionDesc(caseId);
        if (previous.isEmpty()) {
            return null;
        }
        EvidenceDossierEntity dossier = previous.get();
        if (dossier.getDossierVersion() >= targetVersion) {
            throw new IllegalStateException("evidence dossier version authority regressed");
        }
        try {
            JsonNode candidate =
                    objectMapper
                            .readTree(dossier.getMatrixSummaryJson())
                            .path("fact_evidence_matrix");
            if (candidate.isMissingNode() || candidate.isNull()) {
                return null;
            }
            if (!candidate.isObject()) {
                throw new IllegalStateException("previous frozen evidence matrix is not an object");
            }
            ObjectNode matrix = (ObjectNode) candidate;
            if (!"fact_evidence_matrix.v3".equals(matrix.path("schema_version").asText())
                    || !caseId.equals(matrix.path("case_id").asText())
                    || !"FROZEN".equals(matrix.path("matrix_status").asText())
                    || matrix.path("matrix_id").asText("").isBlank()
                    || matrix.path("matrix_version").asInt(0) < 1
                    || !isContentHash(matrix.path("content_hash").asText())
                    || !matrix.path("content_hash")
                            .asText()
                            .equals(pythonContentHash(matrix, "content_hash"))) {
                throw new IllegalStateException("previous frozen evidence matrix authority is invalid");
            }
            return matrix.deepCopy();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("previous frozen evidence matrix is invalid JSON", exception);
        }
    }

    private String pythonContentHash(ObjectNode value, String hashField) {
        ObjectNode unsigned = value.deepCopy();
        unsigned.remove(hashField);
        return ContractJson.sha256Hex(unsigned);
    }

    private static boolean isContentHash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.withLatestStatus(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.withLatestStatus(EvidenceItemEntity)」：构建包含最新版本状态；实际协作者为 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「evidence.getId」，最终返回「IncludedEvidence」。
    // 上游调用：「EvidenceDossierFreezer.withLatestStatus(EvidenceItemEntity)」只由「EvidenceDossierFreezer」内部流程使用，负责封装“包含最新版本状态”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceDossierFreezer.withLatestStatus(EvidenceItemEntity)」向下依次触达 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「evidence.getId」；计算结果以「IncludedEvidence」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.withLatestStatus(EvidenceItemEntity)」负责主链路中的“包含最新版本状态”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private IncludedEvidence withLatestStatus(EvidenceItemEntity evidence) {
        return new IncludedEvidence(
                evidence,
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidence.getId())
                        .orElse(null));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.verificationScore(IncludedEvidence,String,double)」。
    // 具体功能：「EvidenceDossierFreezer.verificationScore(IncludedEvidence,String,double)」：构建核验分数；实际协作者为 「Double.isFinite」、「Math.max」、「Math.min」、「item.verification」，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.verificationScore(IncludedEvidence,String,double)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.verificationScore(IncludedEvidence,String,double)」向下依次触达 「Double.isFinite」、「Math.max」、「Math.min」、「item.verification」；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.verificationScore(IncludedEvidence,String,double)」负责主链路中的“核验分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private Double verificationScore(IncludedEvidence item, String fieldName) {
        if (item.verification() == null) {
            throw new IllegalStateException(
                    "frozen Evidence material has no v3 model assessment");
        }
        try {
            var value =
                    objectMapper
                            .readTree(item.verification().getAgentFindingsJson())
                            .path(fieldName);
            if (!value.isNumber()) {
                return null;
            }
            double score = value.doubleValue();
            return Double.isFinite(score) && score >= 0.0 && score <= 1.0 ? score : null;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("frozen Evidence assessment is invalid JSON", exception);
        }
    }

    private static boolean assessmentComplete(
            JsonNode assessment,
            Double authenticityScore,
            Double relevanceScore,
            Double completenessScore,
            Double assessmentConfidence) {
        boolean explicitlyIncomplete =
                assessment.has("assessment_complete")
                        && !assessment.path("assessment_complete").asBoolean(false);
        return !explicitlyIncomplete
                && authenticityScore != null
                && relevanceScore != null
                && completenessScore != null
                && assessmentConfidence != null
                && !assessment.path("risk_level").asText("").trim().isEmpty();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.hasStructuredFactLinks(IncludedEvidence)」。
    // 具体功能：「EvidenceDossierFreezer.hasStructuredFactLinks(IncludedEvidence)」：判断是否存在Structured事实关联；实际协作者为 「agentFindings」、「agentFindings(item).has」；处理的关键状态/协议值包括 「fact_links」，最终返回「boolean」。
    // 上游调用：「EvidenceDossierFreezer.hasStructuredFactLinks(IncludedEvidence)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.hasStructuredFactLinks(IncludedEvidence)」向下依次触达 「agentFindings」、「agentFindings(item).has」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.hasStructuredFactLinks(IncludedEvidence)」负责主链路中的“Structured事实关联”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private boolean hasStructuredFactLinks(IncludedEvidence item) {
        return agentFindings(item).has("fact_links");
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.factLinks(IncludedEvidence)」。
    // 具体功能：「EvidenceDossierFreezer.factLinks(IncludedEvidence)」：原样投影 Evidence v3 已正式绑定的 fact_links；不执行文本匹配、关系重判或分数换算，最终返回「List<FactLinkSnapshot>」。
    // 上游调用：「EvidenceDossierFreezer.factLinks(IncludedEvidence)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.factLinks(IncludedEvidence)」向下依次触达 「Double.isFinite」、「Math.max」、「Math.min」、「rawLinks.isArray」；计算结果以「List<FactLinkSnapshot>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.factLinks(IncludedEvidence)」负责主链路中的“事实关联”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<FactLinkSnapshot> factLinks(IncludedEvidence item) {
        JsonNode rawLinks = agentFindings(item).path("fact_links");
        if (!rawLinks.isArray()) {
            return List.of();
        }
        List<FactLinkSnapshot> links = new ArrayList<>();
        for (JsonNode rawLink : rawLinks) {
            String factId = rawLink.path("fact_id").asText("").trim();
            String relation =
                    FactEvidenceRelationCanonicalizer.canonicalize(
                            rawLink.path("relation").asText(""));
            String sourceUnitId = rawLink.path("source_unit_id").asText("").trim();
            String observationSlot = rawLink.path("observation_slot").asText("").trim();
            if (factId.isBlank()
                    || sourceUnitId.isBlank()
                    || observationSlot.isBlank()) {
                throw new IllegalStateException(
                        "frozen Evidence v3 fact binding identity is incomplete");
            }
            links.add(
                    new FactLinkSnapshot(
                            factId,
                            relation,
                            abbreviate(rawLink.path("reason").asText(""), 180),
                            sourceUnitId,
                            observationSlot));
        }
        return List.copyOf(links);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.agentFindings(IncludedEvidence)」。
    // 具体功能：「EvidenceDossierFreezer.agentFindings(IncludedEvidence)」：解析AgentFindings：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「item.verification」、「objectMapper.createObjectNode」、「objectMapper.readTree」、「item.verification().getAgentFindingsJson」，最终返回「JsonNode」。
    // 上游调用：「EvidenceDossierFreezer.agentFindings(IncludedEvidence)」的上游调用点包括 「EvidenceDossierFreezer.hasStructuredFactLinks」、「EvidenceDossierFreezer.factLinks」、「EvidenceDossierFreezer.evidenceHandoffNotes」、「EvidenceDossierFreezer.humanReviewTasks」。
    // 下游影响：「EvidenceDossierFreezer.agentFindings(IncludedEvidence)」向下依次触达 「item.verification」、「objectMapper.createObjectNode」、「objectMapper.readTree」、「item.verification().getAgentFindingsJson」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.agentFindings(IncludedEvidence)」负责主链路中的“AgentFindings”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private JsonNode agentFindings(IncludedEvidence item) {
        if (item.verification() == null
                || item.verification().getAgentFindingsJson() == null
                || item.verification().getAgentFindingsJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(item.verification().getAgentFindingsJson());
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.evidenceHandoffNotes(List)」。
    // 具体功能：「EvidenceDossierFreezer.evidenceHandoffNotes(List)」：构建证据移交Notes；实际协作者为 「String.join」、「attentionPoints.isArray」、「point.asText」、「agentFindings」；处理的关键状态/协议值包括 「internal_handoff」、「evidence_change_summary」、「matrix_change_summary」、「judge_attention_points」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.evidenceHandoffNotes(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.evidenceHandoffNotes(List)」向下依次触达 「String.join」、「attentionPoints.isArray」、「point.asText」、「agentFindings」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.evidenceHandoffNotes(List)」负责主链路中的“证据移交Notes”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String evidenceHandoffNotes(List<IncludedEvidence> included) {
        List<String> notes = new ArrayList<>();
        for (IncludedEvidence item : included) {
            JsonNode handoff = agentFindings(item).path("internal_handoff");
            for (String field : List.of("evidence_change_summary", "matrix_change_summary")) {
                String value = handoff.path(field).asText("").trim();
                if (!value.isBlank() && !notes.contains(value)) {
                    notes.add(value);
                }
            }
            JsonNode attentionPoints = handoff.path("judge_attention_points");
            if (attentionPoints.isArray()) {
                for (JsonNode point : attentionPoints) {
                    String value = point.asText("").trim();
                    if (!value.isBlank() && !notes.contains(value)) {
                        notes.add(value);
                    }
                }
            }
        }
        if (!notes.isEmpty()) {
            return String.join("；", notes);
        }
        return included.isEmpty()
                ? "证据室尚未收到正式提交的有效证据，庭审应提醒双方围绕争议事实进行说明。"
                : "证据室已将正式提交材料装订为基础证明矩阵，庭审应围绕证明强度、来源链路和缺口继续核验。";
    }

    // 汇总后端由四项分数与综合风险派生的人工复核原因；不生成审核任务、目标或指引。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<JsonNode> humanReviewReasons(List<IncludedEvidence> included) {
        List<JsonNode> reasons = new ArrayList<>();
        for (IncludedEvidence item : included) {
            if (item.verification() == null || !item.verification().isRequiresHumanReview()) {
                continue;
            }
            ObjectNode reason = objectMapper.createObjectNode();
            reason.put("evidence_id", item.evidence().getId());
            reason.set(
                    "reason_details",
                    agentFindings(item).path("reason_details").deepCopy());
            reasons.add(reason);
        }
        return List.copyOf(reasons);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.internalHandoffs(List)」。
    // 具体功能：「EvidenceDossierFreezer.internalHandoffs(List)」：收集证据 Agent 面向法官的内部移交对象；没有结构化 handoff 时用证据变更摘要和矩阵关注点生成兜底移交，最终返回「List<JsonNode>」。
    // 上游调用：「EvidenceDossierFreezer.internalHandoffs(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.internalHandoffs(List)」向下依次触达 「handoff.isObject」、「handoff.deepCopy」、「agentFindings」、「handoffs.stream().noneMatch」；计算结果以「List<JsonNode>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.internalHandoffs(List)」负责主链路中的“内部移交信息”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<JsonNode> internalHandoffs(List<IncludedEvidence> included) {
        List<JsonNode> handoffs = new ArrayList<>();
        for (IncludedEvidence item : included) {
            JsonNode handoff = agentFindings(item).path("internal_handoff");
            if (handoff.isObject()
                    && handoff.size() > 0
                    && handoffs.stream().noneMatch(existing -> existing.equals(handoff))) {
                handoffs.add(handoff.deepCopy());
            }
        }
        return List.copyOf(handoffs);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.json(Object)」。
    // 具体功能：「EvidenceDossierFreezer.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.json(Object)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize frozen evidence dossier", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.statusName(EvidenceVerificationStatus)」。
    // 具体功能：「EvidenceDossierFreezer.statusName(EvidenceVerificationStatus)」：构建状态名称；处理的关键状态/协议值包括 「UNVERIFIED」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.statusName(EvidenceVerificationStatus)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.statusName(EvidenceVerificationStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.statusName(EvidenceVerificationStatus)」负责主链路中的“状态名称”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String statusName(EvidenceVerificationStatus status) {
        return status == null ? "UNVERIFIED" : status.name();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.parseStatusName(ParseStatus)」。
    // 具体功能：「EvidenceDossierFreezer.parseStatusName(ParseStatus)」：解析状态名称；处理的关键状态/协议值包括 「UNKNOWN」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.parseStatusName(ParseStatus)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.parseStatusName(ParseStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.parseStatusName(ParseStatus)」负责主链路中的“状态名称”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String parseStatusName(ParseStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.authenticityScore(EvidenceVerificationStatus)」。
    // 具体功能：「EvidenceDossierFreezer.authenticityScore(EvidenceVerificationStatus)」：构建真实性分数，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.authenticityScore(EvidenceVerificationStatus)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.authenticityScore(EvidenceVerificationStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.authenticityScore(EvidenceVerificationStatus)」负责主链路中的“真实性分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」：构建相关性分数；实际协作者为 「evidence.getEvidenceType」、「evidence.getSourceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」；处理的关键状态/协议值包括 「logistics」、「signed」、「delivery」、「物流」，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」向下依次触达 「evidence.getEvidenceType」、「evidence.getSourceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」负责主链路中的“相关性分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」：完成completeness分数；实际协作者为 「Math.min」、「evidence.getParseStatus」、「evidence.getOccurredAt」、「evidence.getFileHash」，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」向下依次触达 「Math.min」、「evidence.getParseStatus」、「evidence.getOccurredAt」、「evidence.getFileHash」；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」负责主链路中的“completeness分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」：只读取提交方在上传声明中填写的证明目标；缺失时明确标记未声明，不再按文件类型或 OCR 文本推断事实。
    // 上游调用：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」读取 evidence metadata；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」负责主链路中的“claimed事实”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String claimedFact(EvidenceItemEntity evidence) {
        String claimedFact = evidenceMetadata(evidence).path("claimed_fact").asText("").trim();
        return claimedFact.isBlank() ? "提交方未声明该证据的证明目标" : claimedFact;
    }

    private JsonNode evidenceMetadata(EvidenceItemEntity evidence) {
        if (evidence.getMetadataJson() == null || evidence.getMetadataJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(evidence.getMetadataJson());
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private Map<String, Object> submissionAttestation(EvidenceItemEntity evidence) {
        JsonNode metadata = evidenceMetadata(evidence);
        List<String> scope = new ArrayList<>();
        if (metadata.path("attestation_scope").isArray()) {
            metadata.path("attestation_scope")
                    .forEach(value -> {
                        if (value.isTextual() && !value.asText().isBlank()) {
                            scope.add(value.asText());
                        }
                    });
        }
        Map<String, Object> attestation = new LinkedHashMap<>();
        attestation.put("truth_attested", metadata.path("truth_attested").asBoolean(false));
        attestation.put(
                "attestation_version", metadata.path("attestation_version").asText(null));
        attestation.put("attestation_scope", List.copyOf(scope));
        attestation.put("attestation_role", metadata.path("attestation_role").asText(null));
        attestation.put("attested_by", metadata.path("attested_by").asText(null));
        attestation.put("attested_at", metadata.path("attested_at").asText(null));
        return attestation;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」：构建风险标记；实际协作者为 「evidence.getParseStatus」；处理的关键状态/协议值包括 「仍需人工复核真实性」、「证据真实性存在疑点」、「材料解析不完整」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」向下依次触达 「evidence.getParseStatus」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」负责主链路中的“风险标记”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.partyEvidenceSummary(Map)」。
    // 具体功能：「EvidenceDossierFreezer.partyEvidenceSummary(Map)」：构建当事方证据Summary；实际协作者为 「summary.toMap」，最终返回「Map<String, Object>」。
    // 上游调用：「EvidenceDossierFreezer.partyEvidenceSummary(Map)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.partyEvidenceSummary(Map)」向下依次触达 「summary.toMap」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.partyEvidenceSummary(Map)」负责主链路中的“当事方证据Summary”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static Map<String, Object> partyEvidenceSummary(
            Map<String, PartySummaryAccumulator> partySummary) {
        Map<String, Object> result = new LinkedHashMap<>();
        partySummary.forEach((role, summary) -> result.put(role, summary.toMap()));
        return result;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.evidenceGaps(Map)」。
    // 具体功能：「EvidenceDossierFreezer.evidenceGaps(Map)」：构建证据缺口；处理的关键状态/协议值包括 「USER」、「MERCHANT」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.evidenceGaps(Map)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.evidenceGaps(Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.evidenceGaps(Map)」负责主链路中的“证据缺口”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static List<String> evidenceGaps(Map<String, PartySummaryAccumulator> partySummary) {
        List<String> gaps = new ArrayList<>();
        for (String role : List.of("USER", "MERCHANT")) {
            PartySummaryAccumulator summary = partySummary.get(role);
            if (summary == null || summary.total == 0) {
                gaps.add(role + " 尚未形成有效证据材料");
            }
        }
        return gaps;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.authenticityFlags(List)」。
    // 具体功能：「EvidenceDossierFreezer.authenticityFlags(List)」：构建真实性标记；实际协作者为 「item.status」、「item.evidence」、「item.evidence().getId」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.authenticityFlags(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.authenticityFlags(List)」向下依次触达 「item.status」、「item.evidence」、「item.evidence().getId」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.authenticityFlags(List)」负责主链路中的“真实性标记”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.overallConfidenceScore(List)」。
    // 具体功能：「EvidenceDossierFreezer.overallConfidenceScore(List)」：构建总体可信度分数；实际协作者为 「Math.round」、「average」、「evidenceItems.stream().mapToDouble」、「((Number)item.get("authenticity_score")).doubleValue」；处理的关键状态/协议值包括 「authenticity_score」、「relevance_score」、「completeness_score」、「assessment_confidence」，最终返回「int」。
    // 上游调用：「EvidenceDossierFreezer.overallConfidenceScore(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.overallConfidenceScore(List)」向下依次触达 「Math.round」、「average」、「evidenceItems.stream().mapToDouble」、「((Number)item.get("authenticity_score")).doubleValue」；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.overallConfidenceScore(List)」负责主链路中的“总体可信度分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.abbreviate(String,int)」。
    // 具体功能：「EvidenceDossierFreezer.abbreviate(String,int)」：构建摘要；实际协作者为 「Math.max」、「defaultText」、「defaultText(value,"").replaceAll」；处理的关键状态/协议值包括 「\\s+」、「…」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.abbreviate(String,int)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」、「EvidenceDossierFreezer.factLinks」、「EvidenceDossierFreezer.claimedFact」。
    // 下游影响：「EvidenceDossierFreezer.abbreviate(String,int)」向下依次触达 「Math.max」、「defaultText」、「defaultText(value,"").replaceAll」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.abbreviate(String,int)」负责主链路中的“摘要”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String abbreviate(String value, int maxLength) {
        String normalized = defaultText(value, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.containsAny(String)」。
    // 具体功能：「EvidenceDossierFreezer.containsAny(String)」：判断是否包含任一关键词，最终返回「boolean」。
    // 上游调用：「EvidenceDossierFreezer.containsAny(String)」的上游调用点包括 「EvidenceDossierFreezer.relevanceScore」、「EvidenceDossierFreezer.factId」。
    // 下游影响：「EvidenceDossierFreezer.containsAny(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.containsAny(String)」负责主链路中的“任一关键词”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.defaultText(String,String)」。
    // 具体功能：「EvidenceDossierFreezer.defaultText(String,String)」：构建默认文本，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.defaultText(String,String)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」、「EvidenceDossierFreezer.relevanceScore」、「EvidenceDossierFreezer.factId」、「EvidenceDossierFreezer.abbreviate」。
    // 下游影响：「EvidenceDossierFreezer.defaultText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.defaultText(String,String)」负责主链路中的“默认文本”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.compactUuid()」。
    // 具体功能：「EvidenceDossierFreezer.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.compactUuid()」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「IncludedEvidence」。
    // 类型职责：定义Included证据跨层传递时使用的不可变数据契约；本类型显式提供 「status」。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record IncludedEvidence(
            EvidenceItemEntity evidence, EvidenceVerificationEntity verification) {

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.IncludedEvidence.status()」。
        // 具体功能：「EvidenceDossierFreezer.IncludedEvidence.status()」：更新状态：先更新内部状态 「verification」；实际协作者为 「verification.getVerificationStatus」，最终返回「EvidenceVerificationStatus」。
        // 上游调用：「EvidenceDossierFreezer.IncludedEvidence.status()」只由「IncludedEvidence」内部流程使用，负责封装“状态”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.IncludedEvidence.status()」向下依次触达 「verification.getVerificationStatus」；计算结果以「EvidenceVerificationStatus」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.IncludedEvidence.status()」负责主链路中的“状态”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private EvidenceVerificationStatus status() {
            return verification == null ? null : verification.getVerificationStatus();
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「FactLinkSnapshot」。
    // 类型职责：定义事实Link快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record FactLinkSnapshot(
            String factId,
            String relation,
            String reason,
            String sourceUnitId,
            String observationSlot) {}

    private record FrozenFactEvidenceLink(
            String evidenceId,
            String sourceBatchId,
            FactLinkSnapshot link,
            boolean requiresHumanReview) {}

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「PartySummaryAccumulator」。
    // 类型职责：承载当事方SummaryAccumulator在当前业务模块中的规则与协作边界；本类型显式提供 「add」、「toMap」。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class PartySummaryAccumulator {
        private final List<String> strongPoints = new ArrayList<>();
        private final List<String> weakPoints = new ArrayList<>();
        private final List<String> missingItems = new ArrayList<>();
        private int total;

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.PartySummaryAccumulator.add(EvidenceItemEntity,EvidenceVerificationStatus,double,boolean)」。
        // 具体功能：「EvidenceDossierFreezer.PartySummaryAccumulator.add(EvidenceItemEntity,EvidenceVerificationStatus,double,boolean)」：添加当事方SummaryAccumulator：先更新内部状态 「total」；实际协作者为 「evidence.getOriginalFilename」、「evidence.getId」、「defaultText」、「statusName」；处理的关键状态/协议值包括 「（」、「）」，最终返回「void」。
        // 上游调用：「EvidenceDossierFreezer.PartySummaryAccumulator.add(EvidenceItemEntity,EvidenceVerificationStatus,double,boolean)」只由「PartySummaryAccumulator」内部流程使用，负责封装“当事方SummaryAccumulator”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.PartySummaryAccumulator.add(EvidenceItemEntity,EvidenceVerificationStatus,double,boolean)」向下依次触达 「evidence.getOriginalFilename」、「evidence.getId」、「defaultText」、「statusName」。
        // 系统意义：「EvidenceDossierFreezer.PartySummaryAccumulator.add(EvidenceItemEntity,EvidenceVerificationStatus,double,boolean)」负责主链路中的“当事方SummaryAccumulator”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private void add(
                EvidenceItemEntity evidence,
                EvidenceVerificationStatus status,
                boolean requiresHumanReview) {
            total++;
            String label =
                    defaultText(evidence.getOriginalFilename(), evidence.getId())
                            + "（"
                            + statusName(status)
                            + "）";
            if (!requiresHumanReview && status == EvidenceVerificationStatus.PLAUSIBLE) {
                strongPoints.add(label);
            } else {
                weakPoints.add(label);
            }
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.PartySummaryAccumulator.toMap()」。
        // 具体功能：「EvidenceDossierFreezer.PartySummaryAccumulator.toMap()」：转换映射；处理的关键状态/协议值包括 「strong_points」、「weak_points」、「missing_items」，最终返回「Map<String, Object>」。
        // 上游调用：「EvidenceDossierFreezer.PartySummaryAccumulator.toMap()」只由「PartySummaryAccumulator」内部流程使用，负责封装“映射”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.PartySummaryAccumulator.toMap()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Map<String, Object>」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.PartySummaryAccumulator.toMap()」统一“映射”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strong_points", strongPoints);
            result.put("weak_points", weakPoints);
            result.put("missing_items", missingItems);
            return result;
        }
    }
}
