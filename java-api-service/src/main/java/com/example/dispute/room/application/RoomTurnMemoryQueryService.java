package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomTurnMemoryQueryService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final ObjectMapper objectMapper;

    public RoomTurnMemoryQueryService(
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.memoryRepository = memoryRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<RoomTurnMemoryView> latestAgentMemory(
            String caseId, RoomType roomType, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanRead(dispute, actor);
        return memoryRepository
                .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                        caseId, roomType)
                .map(this::view);
    }

    private void assertCanRead(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot access turn memory");
        }
    }

    private RoomTurnMemoryView view(RoomTurnMemoryEntity memory) {
        JsonNode dossierPatch = readJson(memory.getDossierPatchJson(), false);
        return new RoomTurnMemoryView(
                memory.getCaseId(),
                memory.getRoomType(),
                memory.getTurnNo(),
                memory.getAgentRole(),
                memory.getAgentResponse(),
                dossierPatch,
                readJson(memory.getScrollSnapshotJson(), false),
                readJson(memory.getCanvasOperationsJson(), true),
                dossierPatch.path("memory_frame").isMissingNode()
                        ? objectMapper.createObjectNode()
                        : dossierPatch.path("memory_frame"),
                intakeDossierRepository
                        .findByCaseIdAndRoomType(memory.getCaseId(), RoomType.INTAKE)
                        .map(this::intakeDossierView)
                        .orElse(null),
                memory.getCreatedAt());
    }

    private CaseIntakeDossierView intakeDossierView(
            com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity dossier) {
        return new CaseIntakeDossierView(
                dossier.getCaseId(),
                dossier.getRoomType(),
                dossier.getDossierVersion(),
                readJson(dossier.getDossierJson(), false),
                dossier.getQualityScore(),
                dossier.isReadyForNextStep(),
                dossier.getAdmissionRecommendation(),
                dossier.getSourceTurnNo(),
                dossier.getUpdatedAt());
    }

    private JsonNode readJson(String json, boolean arrayDefault) {
        if (json == null || json.isBlank()) {
            return arrayDefault ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return arrayDefault ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
    }
}
