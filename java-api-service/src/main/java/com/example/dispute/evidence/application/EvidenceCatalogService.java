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
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceCatalogService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvidenceCatalogService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
    }

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
        return new RoleScopedEvidenceView(caseId, dispute.getInitiatorRole().name(), items);
    }

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
        Double confidenceScore = confidenceScore(agentFindings);
        return new RoleScopedEvidenceView.Item(
                item.getId(),
                item.getEvidenceType(),
                item.getSubmittedByRole(),
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
                item.getSubmissionBatchId());
    }

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

    private static boolean isPrivilegedEvidenceViewer(ActorRole role) {
        return role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM;
    }

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
