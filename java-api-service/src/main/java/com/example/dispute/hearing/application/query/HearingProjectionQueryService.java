package com.example.dispute.hearing.application.query;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.workflow.projection.hearing.HearingProjectionAdapter;
import com.example.dispute.workflow.projection.hearing.HearingProjectionSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Side-effect-free query boundary over the Java-owned Hearing projection. */
@Service
@Transactional(readOnly = true)
public class HearingProjectionQueryService {

    private final FulfillmentCaseRepository caseRepository;
    private final HearingFlowInstanceRepository instanceRepository;
    private final HearingFlowStageRepository stageRepository;
    private final HearingFlowActionRepository actionRepository;
    private final HearingFlowArtifactRepository artifactRepository;
    private final HearingTrialDossierRepository trialDossierRepository;
    private final RemedyPlanRepository remedyPlanRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final HearingProjectionAdapter adapter;
    private final ObjectMapper objectMapper;

    public HearingProjectionQueryService(
            FulfillmentCaseRepository caseRepository,
            HearingFlowInstanceRepository instanceRepository,
            HearingFlowStageRepository stageRepository,
            HearingFlowActionRepository actionRepository,
            HearingFlowArtifactRepository artifactRepository,
            HearingTrialDossierRepository trialDossierRepository,
            RemedyPlanRepository remedyPlanRepository,
            ReviewTaskRepository reviewTaskRepository,
            HearingProjectionAdapter adapter,
            ObjectMapper objectMapper) {
        this.caseRepository = Objects.requireNonNull(caseRepository, "caseRepository");
        this.instanceRepository = Objects.requireNonNull(instanceRepository, "instanceRepository");
        this.stageRepository = Objects.requireNonNull(stageRepository, "stageRepository");
        this.actionRepository = Objects.requireNonNull(actionRepository, "actionRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.trialDossierRepository =
                Objects.requireNonNull(trialDossierRepository, "trialDossierRepository");
        this.remedyPlanRepository =
                Objects.requireNonNull(remedyPlanRepository, "remedyPlanRepository");
        this.reviewTaskRepository =
                Objects.requireNonNull(reviewTaskRepository, "reviewTaskRepository");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public HearingFlowView get(String caseId, AuthenticatedActor actor) {
        String requiredCaseId = required(caseId, "caseId");
        Objects.requireNonNull(actor, "actor");
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(requiredCaseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);

        HearingFlowInstanceEntity instance =
                instanceRepository
                        .findByCaseId(requiredCaseId)
                        .orElseThrow(
                                () -> new IllegalStateException("hearing flow is not initialized"));
        HearingFlowStageEntity stage =
                stageRepository
                        .findByFlowInstanceIdAndStageSequence(
                                instance.getId(), instance.getStageSequence())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "active hearing stage row not found"));
        if (stage.getStageCode() != instance.getCurrentStage()) {
            throw new IllegalStateException("active hearing stage does not match the flow cursor");
        }

        List<HearingFlowActionEntity> actions =
                actionRepository.findAllByFlowInstanceIdOrderByCreatedAtAsc(instance.getId());
        PartyProjection parties = partyProjection(dispute, instance, stage, actions);
        EnumMap<HearingArtifactType, HearingFlowArtifactEntity> artifacts =
                readArtifacts(requiredCaseId);
        HearingFlowArtifactEntity draft = artifacts.get(HearingArtifactType.ADJUDICATION_DRAFT);

        HearingProjectionSnapshot snapshot =
                new HearingProjectionSnapshot(
                        instance.getSchemaVersion(),
                        instance.getCurrentStage(),
                        instance.getStageSequence(),
                        stage.getStageStatus().name(),
                        instance.getFlowStatus().name(),
                        stage.getSharedDeadlineAt(),
                        instance.getSharedDeadlineAt(),
                        parties.statuses(),
                        parties.participants(),
                        reviewGateReady(instance, draft),
                        draft == null ? null : draft.getId(),
                        systemPayload(actions, HearingFlowActionType.QUESTION_SET).orElse(null),
                        systemPayload(actions, HearingFlowActionType.EVIDENCE_REQUEST_SET).orElse(null),
                        trialDossierRepository
                                .findByCaseId(requiredCaseId)
                                .map(
                                        item ->
                                                new HearingFlowView.Reference(
                                                        item.getId(),
                                                        item.getSchemaVersion(),
                                                        item.getContentHash()))
                                .orElse(null),
                        decisionChain(artifacts));
        return adapter.adapt(snapshot);
    }

    /** POST /complete remains the same read-only projection gate as GET /hearing. */
    public HearingFlowView completeGate(String caseId, AuthenticatedActor actor) {
        return get(caseId, actor);
    }

    private PartyProjection partyProjection(
            FulfillmentCaseEntity dispute,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            List<HearingFlowActionEntity> actions) {
        HearingFlowActionType actionType =
                switch (instance.getCurrentStage()) {
                    case PARTY_ANSWERS_OPEN -> HearingFlowActionType.ANSWER_BUNDLE;
                    case PARTY_EVIDENCE_OPEN -> HearingFlowActionType.EVIDENCE_BATCH;
                    default -> null;
                };
        if (actionType == null) {
            return PartyProjection.empty();
        }

        Map<String, String> statuses = new LinkedHashMap<>();
        List<HearingFlowView.ParticipantStatus> participants = new ArrayList<>();
        addParty(statuses, participants, actions, stage, actionType, dispute.getUserId(), ActorRole.USER);
        addParty(
                statuses,
                participants,
                actions,
                stage,
                actionType,
                dispute.getMerchantId(),
                ActorRole.MERCHANT);
        return new PartyProjection(statuses, participants);
    }

    private static void addParty(
            Map<String, String> statuses,
            List<HearingFlowView.ParticipantStatus> participants,
            List<HearingFlowActionEntity> actions,
            HearingFlowStageEntity stage,
            HearingFlowActionType actionType,
            String participantId,
            ActorRole participantRole) {
        String status =
                actions.stream()
                        .filter(item -> item.getActionType() == actionType)
                        .filter(item -> stage.getId().equals(item.getStageId()))
                        .filter(item -> participantId.equals(item.getParticipantId()))
                        .filter(item -> participantRole == item.getParticipantRole())
                        .findFirst()
                        .map(item -> item.getSubmissionStatus().name())
                        .orElse("PENDING");
        statuses.put(participantRole.name(), status);
        participants.add(
                new HearingFlowView.ParticipantStatus(
                        participantId, participantRole.name(), status));
    }

    private Optional<JsonNode> systemPayload(
            List<HearingFlowActionEntity> actions, HearingFlowActionType actionType) {
        return actions.stream()
                .filter(item -> item.getActionType() == actionType)
                .filter(item -> item.getParticipantId() == null)
                .filter(item -> item.getParticipantRole() == null)
                .findFirst()
                .map(item -> read(item.getPayloadJson()));
    }

    private EnumMap<HearingArtifactType, HearingFlowArtifactEntity> readArtifacts(String caseId) {
        EnumMap<HearingArtifactType, HearingFlowArtifactEntity> result =
                new EnumMap<>(HearingArtifactType.class);
        for (HearingArtifactType type : HearingArtifactType.values()) {
            artifactRepository.findByCaseIdAndArtifactType(caseId, type).ifPresent(item -> result.put(type, item));
        }
        return result;
    }

    private static Map<String, HearingFlowView.Reference> decisionChain(
            Map<HearingArtifactType, HearingFlowArtifactEntity> artifacts) {
        Map<String, HearingFlowView.Reference> result = new LinkedHashMap<>();
        for (HearingArtifactType type : HearingArtifactType.values()) {
            HearingFlowArtifactEntity item = artifacts.get(type);
            if (item != null) {
                result.put(
                        type.name(),
                        new HearingFlowView.Reference(
                                item.getId(), item.getSchemaVersion(), item.getContentHash()));
            }
        }
        return result;
    }

    private boolean reviewGateReady(
            HearingFlowInstanceEntity instance, HearingFlowArtifactEntity draft) {
        if (draft == null
                || (instance.getCurrentStage() != HearingFlowStage.HUMAN_REVIEW_OPEN
                        && instance.getCurrentStage() != HearingFlowStage.CLOSED)) {
            return false;
        }
        return remedyPlanRepository
                .findFirstByCaseIdOrderByPlanVersionDesc(instance.getCaseId())
                .filter(plan -> draft.getId().equals(plan.getAdjudicationDraftId()))
                .flatMap(
                        plan ->
                                reviewTaskRepository
                                        .findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                                                instance.getCaseId(), plan.getId()))
                .isPresent();
    }

    private void assertCanAccess(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access this hearing");
        }
    }

    private JsonNode read(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("persisted hearing projection must be an object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted hearing projection JSON", exception);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record PartyProjection(
            Map<String, String> statuses,
            List<HearingFlowView.ParticipantStatus> participants) {

        private PartyProjection {
            statuses = Map.copyOf(statuses);
            participants = List.copyOf(participants);
        }

        private static PartyProjection empty() {
            return new PartyProjection(Map.of(), List.of());
        }
    }
}
