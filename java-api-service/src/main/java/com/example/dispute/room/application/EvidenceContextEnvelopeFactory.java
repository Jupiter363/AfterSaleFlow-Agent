package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class EvidenceContextEnvelopeFactory {

    private static final int RECENT_TURN_LIMIT = 20;

    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceContextEnvelopeFactory(
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceItemRepository evidenceItemRepository,
            RoomTurnMemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.intakeDossierRepository = intakeDossierRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public EvidenceContextEnvelopeV1 create(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AuthenticatedActor actor,
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            String eventType,
            String eventId,
            MessageType messageType,
            String text,
            List<String> attachmentRefs,
            int turnNo,
            Instant occurredAt) {
        CaseIntakeDossierEntity intakeDossier =
                intakeDossierRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                        .orElse(null);
        JsonNode intakeDossierJson =
                intakeDossier == null ? null : readJson(intakeDossier.getDossierJson());
        RecentTurnsWindow recentTurns = recentTurns(agentSession);
        List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence =
                visibleEvidence(dispute.getId(), actor);
        validateEvidenceReferences(attachmentRefs, visibleEvidence);

        return new EvidenceContextEnvelopeV1(
                EvidenceContextEnvelopeV1.SCHEMA_VERSION,
                clock.instant().toString(),
                caseSnapshot(dispute),
                intakeDossierSnapshot(intakeDossier, intakeDossierJson),
                actorSnapshot(dispute, actor, accessSession, agentSession),
                new EvidenceContextEnvelopeV1.CurrentEvent(
                        eventId,
                        eventType,
                        messageType,
                        actor.actorId(),
                        actor.role().name(),
                        text,
                        attachmentRefs,
                        turnNo,
                        occurredAt.toString()),
                visibleEvidence,
                new EvidenceContextEnvelopeV1.PrivateConversation(
                        agentSession.getId(),
                        agentSession.getConversationScope(),
                        recentTurns.sourceCount(),
                        recentTurns.truncated(),
                        recentTurns.turns()),
                new EvidenceContextEnvelopeV1.RoomPolicy(
                        room.getId(),
                        room.getRoomType(),
                        room.getRoomStatus().name(),
                        isoTimestamp(dispute.getCurrentDeadlineAt()),
                        dispute.getInitiatorRole().name(),
                        true));
    }

    private EvidenceContextEnvelopeV1.CaseSnapshot caseSnapshot(
            FulfillmentCaseEntity dispute) {
        return new EvidenceContextEnvelopeV1.CaseSnapshot(
                dispute.getId(),
                dispute.getVersion(),
                dispute.getCaseStatus().name(),
                dispute.getCaseType(),
                dispute.getDisputeType(),
                dispute.getInitiatorRole().name(),
                dispute.getTitle(),
                dispute.getDescription(),
                dispute.getRiskLevel().name(),
                dispute.getRouteType() == null ? null : dispute.getRouteType().name(),
                dispute.getOrderId(),
                dispute.getAfterSaleId(),
                dispute.getLogisticsId(),
                dispute.getSourceType().name(),
                dispute.getSourceSystem(),
                dispute.getExternalCaseRef(),
                dispute.getCurrentRoom(),
                isoTimestamp(dispute.getCurrentDeadlineAt()));
    }

    private static EvidenceContextEnvelopeV1.IntakeDossierSnapshot intakeDossierSnapshot(
            CaseIntakeDossierEntity intakeDossier, JsonNode payload) {
        if (intakeDossier == null) {
            return null;
        }
        String payloadSchemaVersion =
                payload == null ? null : payload.path("schema_version").asText(null);
        return new EvidenceContextEnvelopeV1.IntakeDossierSnapshot(
                intakeDossier.getId(),
                payloadSchemaVersion,
                intakeDossier.getDossierVersion(),
                intakeDossier.getSourceTurnNo(),
                intakeDossier.getQualityScore(),
                intakeDossier.isReadyForNextStep(),
                intakeDossier.getAdmissionRecommendation(),
                isoTimestamp(intakeDossier.getUpdatedAt()),
                payload);
    }

    private static EvidenceContextEnvelopeV1.ActorSnapshot actorSnapshot(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor actor,
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession) {
        return new EvidenceContextEnvelopeV1.ActorSnapshot(
                actor.actorId(),
                actor.role().name(),
                dispute.getInitiatorRole().name(),
                accessSession.getId(),
                agentSession.getId(),
                agentSession.getConversationScope(),
                agentSession.getPromptProfileId(),
                agentSession.getMemoryPolicyId());
    }

    private List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence(
            String caseId, AuthenticatedActor actor) {
        return evidenceItemRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                .stream()
                .filter(item -> visibleEvidenceTo(item, actor))
                .map(item -> visibleEvidence(caseId, item))
                .toList();
    }

    private EvidenceContextEnvelopeV1.VisibleEvidence visibleEvidence(
            String caseId, EvidenceItemEntity item) {
        return new EvidenceContextEnvelopeV1.VisibleEvidence(
                item.getId(),
                item.getDossierId(),
                item.getEvidenceType(),
                item.getSourceType(),
                item.getSubmittedByRole(),
                item.getSubmittedById(),
                item.getOriginalFilename(),
                item.getContentType(),
                item.getFileSize(),
                item.getFileHash(),
                item.getParsedText(),
                item.getParseStatus().name(),
                item.getVisibility(),
                item.isDesensitized(),
                readJson(item.getMetadataJson()),
                readJson(item.getExtractionJson()),
                isoTimestamp(item.getOccurredAt()),
                isoTimestamp(item.getCreatedAt()),
                isoTimestamp(item.getSubmittedAt()),
                item.getSubmissionStatus().name(),
                item.getSubmissionBatchId(),
                "/api/disputes/" + caseId + "/evidence/" + item.getId() + "/content");
    }

    private static void validateEvidenceReferences(
            List<String> attachmentRefs,
            List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence) {
        if (attachmentRefs == null || attachmentRefs.isEmpty()) {
            return;
        }
        Set<String> visibleEvidenceIds =
                visibleEvidence.stream()
                        .map(EvidenceContextEnvelopeV1.VisibleEvidence::evidenceId)
                        .collect(Collectors.toSet());
        List<String> unauthorizedRefs =
                attachmentRefs.stream()
                        .filter(ref -> !visibleEvidenceIds.contains(ref))
                        .toList();
        if (!unauthorizedRefs.isEmpty()) {
            throw new ForbiddenException(
                    "evidence references are not visible to the current actor");
        }
    }

    private RecentTurnsWindow recentTurns(AgentConversationSessionEntity agentSession) {
        List<RoomTurnMemoryEntity> memories =
                memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(agentSession.getId());
        List<IntakeRecentTurn> scopedTurns =
                memories.stream()
                        .filter(
                                memory ->
                                        agentSession
                                                .getId()
                                                .equals(memory.getAgentSessionId()))
                        .sorted(Comparator.comparingInt(RoomTurnMemoryEntity::getTurnNo))
                        .map(
                                memory ->
                                        new IntakeRecentTurn(
                                                memory.getTurnNo(),
                                                memory.getActorId(),
                                                memory.getAnswerRole(),
                                                memory.getAnswerContent(),
                                                memory.getAgentRole(),
                                                memory.getAgentResponse(),
                                                readJson(memory.getScrollSnapshotJson()),
                                                memory.getAgentSessionId(),
                                                memory.getConversationScope()))
                        .toList();
        if (scopedTurns.size() <= RECENT_TURN_LIMIT) {
            return new RecentTurnsWindow(scopedTurns.size(), false, scopedTurns);
        }
        return new RecentTurnsWindow(
                scopedTurns.size(),
                true,
                scopedTurns.subList(
                        scopedTurns.size() - RECENT_TURN_LIMIT, scopedTurns.size()));
    }

    private static boolean visibleEvidenceTo(
            EvidenceItemEntity item, AuthenticatedActor actor) {
        ActorRole role = actor.role();
        if (role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM) {
            return true;
        }
        if (role == ActorRole.CUSTOMER_SERVICE) {
            return "PARTIES".equals(item.getVisibility())
                    || "PLATFORM".equals(item.getVisibility());
        }
        return role.name().equals(item.getSubmittedByRole())
                && actor.actorId().equals(item.getSubmittedById())
                && item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String isoTimestamp(java.time.OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private record RecentTurnsWindow(
            int sourceCount, boolean truncated, List<IntakeRecentTurn> turns) {}
}
