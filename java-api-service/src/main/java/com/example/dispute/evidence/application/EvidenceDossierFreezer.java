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

@Service
public class EvidenceDossierFreezer {

    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceDossierItemRepository dossierItemRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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

    @Transactional(readOnly = true)
    public int targetVersion(String caseId) {
        return dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .map(dossier -> dossier.getDossierVersion() + 1)
                        .orElse(1);
    }

    @Transactional(readOnly = true)
    public int latestVersion(String caseId) {
        return dossierRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .map(EvidenceDossierEntity::getDossierVersion)
                .orElseThrow(() -> new IllegalArgumentException("dossier not found"));
    }

    @Transactional
    public EvidenceDossierEntity freeze(
            String caseId, int targetVersion, String actorId) {
        return dossierRepository
                .findByCaseIdAndDossierVersion(caseId, targetVersion)
                .orElseGet(() -> createFrozen(caseId, targetVersion, actorId));
    }

    private EvidenceDossierEntity createFrozen(
            String caseId, int targetVersion, String actorId) {
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
        summary.put(
                "handoff_notes",
                included.isEmpty()
                        ? "证据室尚未收到正式提交的有效证据，庭审应提醒双方围绕争议事实进行说明。"
                        : "证据室已将正式提交材料装订为基础证明矩阵，庭审应围绕证明强度、来源链路和缺口继续核验。");
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

    private IncludedEvidence withLatestStatus(EvidenceItemEntity evidence) {
        return new IncludedEvidence(
                evidence,
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidence.getId())
                        .orElse(null));
    }

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

    private boolean hasStructuredFactLinks(IncludedEvidence item) {
        return agentFindings(item).has("fact_links");
    }

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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize frozen evidence dossier", exception);
        }
    }

    private static String statusName(EvidenceVerificationStatus status) {
        return status == null ? "UNVERIFIED" : status.name();
    }

    private static String parseStatusName(ParseStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }

    private static double authenticityScore(EvidenceVerificationStatus status) {
        return switch (status == null ? EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW : status) {
            case VERIFIED -> 0.90;
            case PLAUSIBLE -> 0.76;
            case NEEDS_HUMAN_REVIEW -> 0.55;
            case SUSPICIOUS -> 0.35;
            case REJECTED -> 0.0;
        };
    }

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

    private static Map<String, Object> partyEvidenceSummary(
            Map<String, PartySummaryAccumulator> partySummary) {
        Map<String, Object> result = new LinkedHashMap<>();
        partySummary.forEach((role, summary) -> result.put(role, summary.toMap()));
        return result;
    }

    private static List<String> verifiedFacts(Map<String, MatrixAccumulator> matrixByFact) {
        return matrixByFact.values().stream()
                .filter(MatrixAccumulator::strongEnough)
                .map(accumulator -> accumulator.fact)
                .toList();
    }

    private static List<String> contestedFacts(Map<String, MatrixAccumulator> matrixByFact) {
        return matrixByFact.values().stream()
                .filter(accumulator -> !accumulator.strongEnough())
                .map(accumulator -> accumulator.fact)
                .toList();
    }

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

    private static List<String> authenticityFlags(List<IncludedEvidence> included) {
        return included.stream()
                .filter(item -> item.status() == EvidenceVerificationStatus.SUSPICIOUS)
                .map(item -> item.evidence().getId() + " 真实性存疑")
                .toList();
    }

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

    private static String abbreviate(String value, int maxLength) {
        String normalized = defaultText(value, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record IncludedEvidence(
            EvidenceItemEntity evidence, EvidenceVerificationEntity verification) {

        private EvidenceVerificationStatus status() {
            return verification == null ? null : verification.getVerificationStatus();
        }
    }

    private record FactLinkSnapshot(
            String factId, String relation, String reason, double confidence) {}

    private static final class MatrixAccumulator {
        private final String factId;
        private final String fact;
        private final List<String> supportingEvidence = new ArrayList<>();
        private final List<String> opposingEvidence = new ArrayList<>();
        private final List<String> inconclusiveEvidence = new ArrayList<>();
        private double strongestSupportScore;
        private double strongestOppositionScore;
        private boolean requiresHumanReview;

        private MatrixAccumulator(String factId, String fact) {
            this.factId = factId;
            this.fact = fact;
        }

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

        private boolean strongEnough() {
            return !requiresHumanReview
                    && !supportingEvidence.isEmpty()
                    && strongestSupportScore >= 0.75
                    && strongestOppositionScore < 0.60;
        }

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

        private static String evidenceStrength(double score) {
            if (score >= 0.85) {
                return "HIGH";
            }
            if (score >= 0.60) {
                return "MEDIUM";
            }
            return "LOW";
        }

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

    private static final class PartySummaryAccumulator {
        private final List<String> strongPoints = new ArrayList<>();
        private final List<String> weakPoints = new ArrayList<>();
        private final List<String> missingItems = new ArrayList<>();
        private int total;

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

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strong_points", strongPoints);
            result.put("weak_points", weakPoints);
            result.put("missing_items", missingItems);
            return result;
        }
    }
}
