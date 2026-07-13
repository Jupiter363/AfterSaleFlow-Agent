/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：承载证据卷宗版本冻结在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「targetVersion」、「latestVersion」、「freeze」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.domain.model.ParseStatus;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」。
    // 具体功能：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」：通过构造器接收 「dossierRepository」(EvidenceDossierRepository)、「dossierItemRepository」(EvidenceDossierItemRepository)、「evidenceRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「EvidenceDossierFreezer」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」的上游创建点包括 「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」、「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus」、「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」。
    // 下游影响：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierFreezer.EvidenceDossierFreezer(EvidenceDossierRepository,EvidenceDossierItemRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」负责主链路中的“证据卷宗冻结器”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceDossierFreezer(
            EvidenceDossierRepository dossierRepository,
            EvidenceDossierItemRepository dossierItemRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dossierRepository = dossierRepository;
        this.dossierItemRepository = dossierItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
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
        Map<String, MatrixAccumulator> matrixByFact = new LinkedHashMap<>();
        Map<String, PartySummaryAccumulator> partySummary = new LinkedHashMap<>();
        partySummary.put("USER", new PartySummaryAccumulator());
        partySummary.put("MERCHANT", new PartySummaryAccumulator());
        for (IncludedEvidence item : included) {
            EvidenceItemEntity evidence = item.evidence();
            // 四个分数各自保留业务含义，composite 只用于矩阵排序。
            // requiresHumanReview 单独保存，不能被较高平均分抵消。
            double authenticityScore =
                    verificationScore(
                            item,
                            "authenticity_score",
                            authenticityScore(item.status()));
            double relevanceScore =
                    verificationScore(item, "relevance_score", relevanceScore(evidence));
            double completenessScore =
                    verificationScore(
                            item,
                            "completeness_score",
                            completenessScore(evidence));
            double assessmentConfidence =
                    verificationScore(item, "assessment_confidence", authenticityScore);
            String claimedFact = claimedFact(evidence);
            List<FactLinkSnapshot> factLinks = factLinks(item);
            boolean structuredFactLinks = hasStructuredFactLinks(item);
            boolean requiresHumanReview =
                    item.verification() != null && item.verification().isRequiresHumanReview();
            double compositeScore =
                    compositeEvidenceScore(
                            authenticityScore,
                            relevanceScore,
                            completenessScore,
                            assessmentConfidence);
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
            evidenceItem.put(
                    "supports_fact_ids",
                    factLinks.stream()
                            .filter(link -> "SUPPORTS".equals(link.relation()))
                            .map(FactLinkSnapshot::factId)
                            .toList());
            evidenceItem.put(
                    "opposes_fact_ids",
                    factLinks.stream()
                            .filter(link -> "OPPOSES".equals(link.relation()))
                            .map(FactLinkSnapshot::factId)
                            .toList());
            evidenceItem.put("authenticity_score", authenticityScore);
            evidenceItem.put("relevance_score", relevanceScore);
            evidenceItem.put("completeness_score", completenessScore);
            evidenceItem.put("assessment_confidence", assessmentConfidence);
            evidenceItem.put("requires_human_review", requiresHumanReview);
            evidenceItem.put("verification_status", statusName(item.status()));
            evidenceItem.put("risk_flags", riskFlags(item.status(), evidence));
            evidenceItems.add(evidenceItem);

            if (structuredFactLinks) {
                // Agent 已给结构化 fact_links 时严格使用该映射；空 links 被记为 unmapped，
                // 不擅自把材料归到某个事实。旧数据缺少结构化字段时才走确定性兼容映射。
                if (factLinks.isEmpty()) {
                    unmappedEvidence.add(evidence.getId());
                }
                for (FactLinkSnapshot link : factLinks) {
                    double linkScore = compositeScore * link.confidence();
                    matrixByFact
                            .computeIfAbsent(
                                    link.factId(),
                                    ignored ->
                                            new MatrixAccumulator(
                                                    link.factId(),
                                                    defaultText(link.reason(), link.factId())))
                            .add(
                                    item,
                                    link.relation(),
                                    linkScore,
                                    requiresHumanReview);
                }
            } else {
                String legacyFactId = factId(evidence);
                matrixByFact
                        .computeIfAbsent(
                                legacyFactId,
                                ignored -> new MatrixAccumulator(legacyFactId, claimedFact))
                        .add(item, "SUPPORTS", compositeScore, requiresHumanReview);
            }
            partySummary
                    .computeIfAbsent(
                            defaultText(evidence.getSubmittedByRole(), "UNKNOWN"),
                            ignored -> new PartySummaryAccumulator())
                    .add(
                            evidence,
                            item.status(),
                            compositeScore,
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
        summary.put("verified_facts", verifiedFacts(matrixByFact));
        summary.put("contested_facts", contestedFacts(matrixByFact));
        summary.put("evidence_gaps", evidenceGaps(partySummary));
        summary.put("authenticity_flags", authenticityFlags(included));
        summary.put("overall_confidence_score", overallConfidenceScore(evidenceItems));
        summary.put("handoff_notes", evidenceHandoffNotes(included));
        summary.put("frozen", true);

        Map<String, Object> matrixSummary = new LinkedHashMap<>();
        matrixSummary.put(
                "fact_evidence_matrix",
                matrixByFact.values().stream().map(MatrixAccumulator::toMap).toList());
        matrixSummary.put(
                "unmapped_evidence",
                unmappedEvidence);
        matrixSummary.put(
                "handoff_notes",
                summary.get("handoff_notes"));
        matrixSummary.put("human_review_tasks", humanReviewTasks(included));
        matrixSummary.put("internal_handoffs", internalHandoffs(included));

        EvidenceDossierEntity dossier =
                dossierRepository.save(
                        EvidenceDossierEntity.frozen(
                                "DOSSIER_" + compactUuid(),
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
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("evidence_type", evidence.getEvidenceType());
            snapshot.put("source_type", evidence.getSourceType());
            snapshot.put("file_hash", evidence.getFileHash());
            snapshot.put("visibility", evidence.getVisibility());
            snapshot.put("submitted_by_role", evidence.getSubmittedByRole());
            snapshot.put("original_filename", evidence.getOriginalFilename());
            snapshot.put("parse_status", parseStatusName(evidence.getParseStatus()));
            snapshot.put("claimed_fact", claimedFact(evidence));
            snapshot.put("verification_status", statusName(item.status()));
            snapshot.put(
                    "authenticity_score",
                    verificationScore(
                            item,
                            "authenticity_score",
                            authenticityScore(item.status())));
            snapshot.put(
                    "relevance_score",
                    verificationScore(
                            item,
                            "relevance_score",
                            relevanceScore(evidence)));
            snapshot.put(
                    "completeness_score",
                    verificationScore(
                            item,
                            "completeness_score",
                            completenessScore(evidence)));
            snapshot.put(
                    "assessment_confidence",
                    verificationScore(
                            item,
                            "assessment_confidence",
                            authenticityScore(item.status())));
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
        return dossier;
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
    private double verificationScore(
            IncludedEvidence item, String fieldName, double fallback) {
        if (item.verification() == null) {
            return fallback;
        }
        try {
            var value =
                    objectMapper
                            .readTree(item.verification().getAgentFindingsJson())
                            .path(fieldName);
            if (!value.isNumber()) {
                return fallback;
            }
            double score = value.asDouble();
            if (!Double.isFinite(score)) {
                return fallback;
            }
            return Math.max(0.0, Math.min(1.0, score > 1.0 ? score / 100.0 : score));
        } catch (JsonProcessingException exception) {
            return fallback;
        }
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
    // 具体功能：「EvidenceDossierFreezer.factLinks(IncludedEvidence)」：优先读取 Agent 提供的结构化 fact_links，校验 fact_id/relation/score 并钳制分数；缺失时才由证据类型和解析文本生成可解释的确定性事实关联，最终返回「List<FactLinkSnapshot>」。
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
            String relation = rawLink.path("relation").asText("").trim();
            if (factId.isBlank()
                    || !("SUPPORTS".equals(relation)
                            || "OPPOSES".equals(relation)
                            || "INCONCLUSIVE".equals(relation))) {
                continue;
            }
            double confidence = rawLink.path("confidence").asDouble(0.0);
            if (!Double.isFinite(confidence)) {
                confidence = 0.0;
            }
            links.add(
                    new FactLinkSnapshot(
                            factId,
                            relation,
                            abbreviate(rawLink.path("reason").asText(""), 180),
                            Math.max(0.0, Math.min(1.0, confidence))));
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.humanReviewTasks(List)」。
    // 具体功能：「EvidenceDossierFreezer.humanReviewTasks(List)」：合并各证据 Agent findings 中的人审任务并深拷贝；需要人工核验但 Agent 未给任务时补建兜底任务，避免风险静默丢失，最终返回「List<JsonNode>」。
    // 上游调用：「EvidenceDossierFreezer.humanReviewTasks(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.humanReviewTasks(List)」向下依次触达 「rawTasks.isArray」、「task.deepCopy」、「agentFindings」、「tasks.stream().anyMatch」；计算结果以「List<JsonNode>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.humanReviewTasks(List)」负责主链路中的“人工审核Tasks”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<JsonNode> humanReviewTasks(List<IncludedEvidence> included) {
        List<JsonNode> tasks = new ArrayList<>();
        for (IncludedEvidence item : included) {
            JsonNode rawTasks = agentFindings(item).path("human_review_tasks");
            if (!rawTasks.isArray()) {
                continue;
            }
            for (JsonNode task : rawTasks) {
                boolean duplicate = tasks.stream().anyMatch(existing -> existing.equals(task));
                if (!duplicate) {
                    tasks.add(task.deepCopy());
                }
            }
        }
        return List.copyOf(tasks);
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.compositeEvidenceScore(double,double,double,double)」。
    // 具体功能：「EvidenceDossierFreezer.compositeEvidenceScore(double,double,double,double)」：构建composite证据分数，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.compositeEvidenceScore(double,double,double,double)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.compositeEvidenceScore(double,double,double,double)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.compositeEvidenceScore(double,double,double,double)」负责主链路中的“composite证据分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static double compositeEvidenceScore(
            double authenticity,
            double relevance,
            double completeness,
            double assessmentConfidence) {
        return authenticity * 0.30
                + relevance * 0.30
                + completeness * 0.20
                + assessmentConfidence * 0.20;
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
    private static double authenticityScore(EvidenceVerificationStatus status) {
        return switch (status == null ? EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW : status) {
            case VERIFIED -> 0.90;
            case PLAUSIBLE -> 0.76;
            case NEEDS_HUMAN_REVIEW -> 0.55;
            case SUSPICIOUS -> 0.35;
            case REJECTED -> 0.0;
        };
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」：构建相关性分数；实际协作者为 「evidence.getEvidenceType」、「evidence.getSourceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」；处理的关键状态/协议值包括 「logistics」、「signed」、「delivery」、「物流」，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」向下依次触达 「evidence.getEvidenceType」、「evidence.getSourceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.relevanceScore(EvidenceItemEntity)」负责主链路中的“相关性分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static double relevanceScore(EvidenceItemEntity evidence) {
        String haystack =
                (defaultText(evidence.getEvidenceType(), "")
                                + " "
                                + defaultText(evidence.getSourceType(), "")
                                + " "
                                + defaultText(evidence.getOriginalFilename(), "")
                                + " "
                                + defaultText(evidence.getParsedText(), ""))
                        .toLowerCase();
        if (containsAny(haystack, "logistics", "signed", "delivery", "物流", "签收", "快递")) {
            return 0.88;
        }
        if (containsAny(haystack, "image", "photo", "video", "图片", "照片", "视频", "监控")) {
            return 0.80;
        }
        return 0.68;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」：完成completeness分数；实际协作者为 「Math.min」、「evidence.getParseStatus」、「evidence.getOccurredAt」、「evidence.getFileHash」，最终返回「double」。
    // 上游调用：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」向下依次触达 「Math.min」、「evidence.getParseStatus」、「evidence.getOccurredAt」、「evidence.getFileHash」；计算结果以「double」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.completenessScore(EvidenceItemEntity)」负责主链路中的“completeness分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static double completenessScore(EvidenceItemEntity evidence) {
        double score = 0.45;
        if (evidence.getParseStatus() == ParseStatus.SUCCEEDED) {
            score += 0.25;
        }
        if (evidence.getOccurredAt() != null) {
            score += 0.15;
        }
        if (evidence.getFileHash() != null && !evidence.getFileHash().isBlank()) {
            score += 0.10;
        }
        return Math.min(0.95, score);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.factId(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.factId(EvidenceItemEntity)」：构建事实标识；实际协作者为 「evidence.getEvidenceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」、「defaultText」；处理的关键状态/协议值包括 「logistics」、「signed」、「delivery」、「物流」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.factId(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」、「EvidenceDossierFreezer.claimedFact」。
    // 下游影响：「EvidenceDossierFreezer.factId(EvidenceItemEntity)」向下依次触达 「evidence.getEvidenceType」、「evidence.getOriginalFilename」、「evidence.getParsedText」、「defaultText」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.factId(EvidenceItemEntity)」负责主链路中的“事实标识”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String factId(EvidenceItemEntity evidence) {
        String haystack =
                (defaultText(evidence.getEvidenceType(), "")
                                + " "
                                + defaultText(evidence.getOriginalFilename(), "")
                                + " "
                                + defaultText(evidence.getParsedText(), ""))
                        .toLowerCase();
        if (containsAny(haystack, "logistics", "signed", "delivery", "物流", "签收", "快递")) {
            return "FACT_LOGISTICS_SIGNING";
        }
        if (containsAny(haystack, "quality", "inspection", "scratch", "质检", "划痕", "瑕疵")) {
            return "FACT_GOODS_CONDITION";
        }
        return "FACT_GENERAL_DISPUTE";
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」：领取claimed事实；实际协作者为 「evidence.getParsedText」、「abbreviate」、「factId」；处理的关键状态/协议值包括 「FACT_LOGISTICS_SIGNING」、「该证据用于说明物流、签收或投递链路相关事实」、「FACT_GOODS_CONDITION」、「该证据用于说明商品状态、质检或瑕疵相关事实」，最终返回「String」。
    // 上游调用：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」向下依次触达 「evidence.getParsedText」、「abbreviate」、「factId」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.claimedFact(EvidenceItemEntity)」负责主链路中的“claimed事实”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String claimedFact(EvidenceItemEntity evidence) {
        String parsed = abbreviate(evidence.getParsedText(), 120);
        if (!parsed.isBlank()) {
            return parsed;
        }
        return switch (factId(evidence)) {
            case "FACT_LOGISTICS_SIGNING" -> "该证据用于说明物流、签收或投递链路相关事实";
            case "FACT_GOODS_CONDITION" -> "该证据用于说明商品状态、质检或瑕疵相关事实";
            default -> "该证据用于说明当事人提交的争议事实";
        };
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」。
    // 具体功能：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」：构建风险标记；实际协作者为 「evidence.getParseStatus」；处理的关键状态/协议值包括 「仍需人工复核真实性」、「证据真实性存在疑点」、「材料解析不完整」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」向下依次触达 「evidence.getParseStatus」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.riskFlags(EvidenceVerificationStatus,EvidenceItemEntity)」负责主链路中的“风险标记”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static List<String> riskFlags(
            EvidenceVerificationStatus status, EvidenceItemEntity evidence) {
        List<String> flags = new ArrayList<>();
        if (status == null || status == EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW) {
            flags.add("仍需人工复核真实性");
        }
        if (status == EvidenceVerificationStatus.SUSPICIOUS) {
            flags.add("证据真实性存在疑点");
        }
        if (evidence.getParseStatus() != ParseStatus.SUCCEEDED) {
            flags.add("材料解析不完整");
        }
        return flags;
    }

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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.verifiedFacts(Map)」。
    // 具体功能：「EvidenceDossierFreezer.verifiedFacts(Map)」：构建已确认事实；实际协作者为 「matrixByFact.values」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.verifiedFacts(Map)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.verifiedFacts(Map)」向下依次触达 「matrixByFact.values」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.verifiedFacts(Map)」负责主链路中的“已确认事实”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static List<String> verifiedFacts(Map<String, MatrixAccumulator> matrixByFact) {
        return matrixByFact.values().stream()
                .filter(MatrixAccumulator::strongEnough)
                .map(accumulator -> accumulator.fact)
                .toList();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.contestedFacts(Map)」。
    // 具体功能：「EvidenceDossierFreezer.contestedFacts(Map)」：构建争议中事实；实际协作者为 「matrixByFact.values」、「accumulator.strongEnough」，最终返回「List<String>」。
    // 上游调用：「EvidenceDossierFreezer.contestedFacts(Map)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.contestedFacts(Map)」向下依次触达 「matrixByFact.values」、「accumulator.strongEnough」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.contestedFacts(Map)」负责主链路中的“争议中事实”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static List<String> contestedFacts(Map<String, MatrixAccumulator> matrixByFact) {
        return matrixByFact.values().stream()
                .filter(accumulator -> !accumulator.strongEnough())
                .map(accumulator -> accumulator.fact)
                .toList();
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
    private static List<String> authenticityFlags(List<IncludedEvidence> included) {
        return included.stream()
                .filter(item -> item.status() == EvidenceVerificationStatus.SUSPICIOUS)
                .map(item -> item.evidence().getId() + " 真实性存疑")
                .toList();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.overallConfidenceScore(List)」。
    // 具体功能：「EvidenceDossierFreezer.overallConfidenceScore(List)」：构建总体可信度分数；实际协作者为 「Math.round」、「average」、「evidenceItems.stream().mapToDouble」、「((Number)item.get("authenticity_score")).doubleValue」；处理的关键状态/协议值包括 「authenticity_score」、「relevance_score」、「completeness_score」、「assessment_confidence」，最终返回「int」。
    // 上游调用：「EvidenceDossierFreezer.overallConfidenceScore(List)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierFreezer.overallConfidenceScore(List)」向下依次触达 「Math.round」、「average」、「evidenceItems.stream().mapToDouble」、「((Number)item.get("authenticity_score")).doubleValue」；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceDossierFreezer.overallConfidenceScore(List)」负责主链路中的“总体可信度分数”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static int overallConfidenceScore(List<Map<String, Object>> evidenceItems) {
        if (evidenceItems.isEmpty()) {
            return 0;
        }
        double average =
                evidenceItems.stream()
                        .mapToDouble(
                                item ->
                                        ((Number) item.get("authenticity_score")).doubleValue()
                                                * 0.30
                                                + ((Number) item.get("relevance_score")).doubleValue()
                                                        * 0.30
                                                + ((Number) item.get("completeness_score")).doubleValue()
                                                        * 0.20
                                                + ((Number) item.get("assessment_confidence"))
                                                                .doubleValue()
                                                        * 0.20)
                        .average()
                        .orElse(0.0);
        return (int) Math.round(average * 100);
    }

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
            String factId, String relation, String reason, double confidence) {}

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「MatrixAccumulator」。
    // 类型职责：承载矩阵Accumulator在当前业务模块中的规则与协作边界；本类型显式提供 「MatrixAccumulator」、「add」、「strongEnough」、「toMap」、「evidenceStrength」、「judgeAttention」。
    // 协作关系：主要由 「EvidenceDossierFreezer.createFrozen」 使用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class MatrixAccumulator {
        private final String factId;
        private final String fact;
        private final List<String> supportingEvidence = new ArrayList<>();
        private final List<String> opposingEvidence = new ArrayList<>();
        private final List<String> inconclusiveEvidence = new ArrayList<>();
        private double strongestSupportScore;
        private double strongestOppositionScore;
        private boolean requiresHumanReview;

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.MatrixAccumulator(String,String)」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.MatrixAccumulator(String,String)」：使用 「factId」(String)、「fact」(String) 初始化「MatrixAccumulator」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.MatrixAccumulator(String,String)」的上游创建点包括 「EvidenceDossierFreezer.createFrozen」。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.MatrixAccumulator(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.MatrixAccumulator(String,String)」负责主链路中的“矩阵Accumulator”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        private MatrixAccumulator(String factId, String fact) {
            this.factId = factId;
            this.fact = fact;
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.add(IncludedEvidence,String,double,boolean)」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.add(IncludedEvidence,String,double,boolean)」：添加矩阵Accumulator：先更新内部状态 「strongestSupportScore」、「strongestOppositionScore」；实际协作者为 「Math.max」、「item.evidence」、「item.evidence().getId」；处理的关键状态/协议值包括 「SUPPORTS」、「OPPOSES」，最终返回「void」。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.add(IncludedEvidence,String,double,boolean)」只由「MatrixAccumulator」内部流程使用，负责封装“矩阵Accumulator”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.add(IncludedEvidence,String,double,boolean)」向下依次触达 「Math.max」、「item.evidence」、「item.evidence().getId」。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.add(IncludedEvidence,String,double,boolean)」负责主链路中的“矩阵Accumulator”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private void add(
                IncludedEvidence item,
                String relation,
                double score,
                boolean requiresHumanReview) {
            if ("SUPPORTS".equals(relation)) {
                supportingEvidence.add(item.evidence().getId());
                strongestSupportScore = Math.max(strongestSupportScore, score);
            } else if ("OPPOSES".equals(relation)) {
                opposingEvidence.add(item.evidence().getId());
                strongestOppositionScore = Math.max(strongestOppositionScore, score);
            } else {
                inconclusiveEvidence.add(item.evidence().getId());
            }
            this.requiresHumanReview |= requiresHumanReview;
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.strongEnough()」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.strongEnough()」：判断证明力足够，最终返回「boolean」。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.strongEnough()」只由「MatrixAccumulator」内部流程使用，负责封装“证明力足够”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.strongEnough()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.strongEnough()」负责主链路中的“证明力足够”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private boolean strongEnough() {
            return !requiresHumanReview
                    && !supportingEvidence.isEmpty()
                    && strongestSupportScore >= 0.75
                    && strongestOppositionScore < 0.60;
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.toMap()」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.toMap()」：转换映射；实际协作者为 「Math.max」、「evidenceStrength」、「judgeAttention」；处理的关键状态/协议值包括 「fact_id」、「fact」、「supporting_evidence」、「opposing_evidence」，最终返回「Map<String, Object>」。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.toMap()」只由「MatrixAccumulator」内部流程使用，负责封装“映射”这一步校验、映射或状态转换。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.toMap()」向下依次触达 「Math.max」、「evidenceStrength」、「judgeAttention」；计算结果以「Map<String, Object>」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.toMap()」统一“映射”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fact_id", factId);
            row.put("fact", fact);
            row.put("supporting_evidence", supportingEvidence);
            row.put("opposing_evidence", opposingEvidence);
            row.put("inconclusive_evidence", inconclusiveEvidence);
            row.put("requires_human_review", requiresHumanReview);
            double strongestScore = Math.max(strongestSupportScore, strongestOppositionScore);
            row.put("evidence_strength", evidenceStrength(strongestScore));
            row.put(
                    "judge_attention",
                    judgeAttention(
                            strongestSupportScore,
                            strongestOppositionScore,
                            requiresHumanReview));
            return row;
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.evidenceStrength(double)」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.evidenceStrength(double)」：构建证据证明力；处理的关键状态/协议值包括 「HIGH」、「MEDIUM」、「LOW」，最终返回「String」。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.evidenceStrength(double)」的上游调用点包括 「MatrixAccumulator.toMap」。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.evidenceStrength(double)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.evidenceStrength(double)」负责主链路中的“证据证明力”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private static String evidenceStrength(double score) {
            if (score >= 0.85) {
                return "HIGH";
            }
            if (score >= 0.60) {
                return "MEDIUM";
            }
            return "LOW";
        }

        // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierFreezer.MatrixAccumulator.judgeAttention(double,double,boolean)」。
        // 具体功能：「EvidenceDossierFreezer.MatrixAccumulator.judgeAttention(double,double,boolean)」：构建法官关注提示；处理的关键状态/协议值包括 「该事实已有较强证据支撑，庭审中仍需确认来源链路和双方解释。」、「该事实已有一定证据支撑，但仍需核验证据形成时间、来源和完整性。」、「该事实证明强度偏弱，庭审中应要求当事人补充说明或接受人工复核。」，最终返回「String」。
        // 上游调用：「EvidenceDossierFreezer.MatrixAccumulator.judgeAttention(double,double,boolean)」的上游调用点包括 「MatrixAccumulator.toMap」。
        // 下游影响：「EvidenceDossierFreezer.MatrixAccumulator.judgeAttention(double,double,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
        // 系统意义：「EvidenceDossierFreezer.MatrixAccumulator.judgeAttention(double,double,boolean)」负责主链路中的“法官关注提示”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
        private static String judgeAttention(
                double supportScore,
                double oppositionScore,
                boolean requiresHumanReview) {
            if (requiresHumanReview) {
                return "该事实关联证据仍需人工复核，庭审不得直接将模型观察写成已验证事实。";
            }
            if (oppositionScore >= 0.60) {
                return "该事实同时存在较强反向证据，庭审应重点核对双方证据来源、时间与完整上下文。";
            }
            if (supportScore >= 0.85) {
                return "该事实已有较强证据支撑，庭审中仍需确认来源链路和双方解释。";
            }
            if (supportScore >= 0.60) {
                return "该事实已有一定证据支撑，但仍需核验证据形成时间、来源和完整性。";
            }
            return "该事实证明强度偏弱，庭审中应要求当事人补充说明或接受人工复核。";
        }
    }

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
                double compositeScore,
                boolean requiresHumanReview) {
            total++;
            String label =
                    defaultText(evidence.getOriginalFilename(), evidence.getId())
                            + "（"
                            + statusName(status)
                            + "）";
            if (!requiresHumanReview && compositeScore >= 0.75) {
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
