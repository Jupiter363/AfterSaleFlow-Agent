package com.example.dispute.room.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared Intake branch mutations and invariants.
 *
 * <p>This service never chooses a writer mode or activates an epoch. Its caller must first prove
 * either the LEGACY or exact TEMPORAL epoch authority and supply already locked case/room facts.
 */
public final class IntakeBranchDomainService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository phaseClockRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final IntakeProgressService intakeProgressService;
    private final ParticipantService participantService;
    private final NotificationService notificationService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final CaseEventService caseEventService;
    private final DisputeProperties disputeProperties;
    private final ObjectMapper objectMapper;
    private final IntakeMatrixLifecycleService matrixLifecycle;

    public IntakeBranchDomainService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository phaseClockRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            IntakeProgressService intakeProgressService,
            ParticipantService participantService,
            NotificationService notificationService,
            CaseLifecycleNotificationService lifecycleNotifications,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            CaseEventService caseEventService,
            DisputeProperties disputeProperties,
            ObjectMapper objectMapper) {
        this.caseRepository = Objects.requireNonNull(caseRepository, "caseRepository");
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
        this.phaseClockRepository = Objects.requireNonNull(phaseClockRepository, "phaseClockRepository");
        this.intakeDossierRepository =
                Objects.requireNonNull(intakeDossierRepository, "intakeDossierRepository");
        this.intakeProgressService =
                Objects.requireNonNull(intakeProgressService, "intakeProgressService");
        this.participantService = Objects.requireNonNull(participantService, "participantService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.lifecycleNotifications =
                Objects.requireNonNull(lifecycleNotifications, "lifecycleNotifications");
        this.evidenceWindowCoordinator =
                Objects.requireNonNull(evidenceWindowCoordinator, "evidenceWindowCoordinator");
        this.caseEventService = Objects.requireNonNull(caseEventService, "caseEventService");
        this.disputeProperties = Objects.requireNonNull(disputeProperties, "disputeProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.matrixLifecycle =
                new IntakeMatrixLifecycleService(intakeDossierRepository, objectMapper);
    }

    public BranchResult acceptInitiator(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            IntakeConfirmationCommand command,
            OffsetDateTime now,
            TimelineEventMode eventMode) {
        Objects.requireNonNull(eventMode, "eventMode");
        requireParty(dispute, actor, true);
        requireOpenIntake(dispute, intakeRoom);
        if (!command.admissible()) {
            throw new IllegalArgumentException("initiator acceptance requires admissible=true");
        }
        IntakeMatrixLifecycleService.FreezeResult matrix =
                matrixLifecycle.freezeInitiatorIfPossible(dispute, actor.actorId());
        participantService.inviteBoth(dispute, actor, now);
        intakeProgressService.completeInitiator(dispute, actor, now);
        String resultJson = acceptedIntakeResultJson(dispute);
        dispute.completeIntake(
                command.disputeType(),
                com.example.dispute.domain.model.CaseStatus.INTAKE_COMPLETED,
                command.riskLevel(),
                resultJson,
                actor.actorId());
        caseRepository.save(dispute);
        if (eventMode == TimelineEventMode.LEGACY_LIFECYCLE) {
            caseEventService.recordLifecycleEvent(
                    dispute.getId(),
                    intakeRoom.getId(),
                    "INITIATOR_INTAKE_COMPLETED",
                    Map.of("case_status", dispute.getCaseStatus().name()),
                    "intake-confirmed:" + dispute.getId(),
                    actor.actorId());
        }
        sendCounterpartySummons(dispute, actor, null);
        return new BranchResult(
                new IntakeConfirmationView(
                        dispute.getId(), dispute.getCaseStatus(), RoomType.INTAKE, null),
                intakeRoom.getId(),
                null,
                matrix.matrixKind(),
                matrix.contentHash());
    }

    public BranchResult rejectInitiator(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            IntakeConfirmationCommand command,
            OffsetDateTime now,
            TimelineEventMode eventMode) {
        Objects.requireNonNull(eventMode, "eventMode");
        requireParty(dispute, actor, true);
        requireOpenIntake(dispute, intakeRoom);
        if (command.admissible()) {
            throw new IllegalArgumentException("initiator rejection requires admissible=false");
        }
        intakeRoom.close(now, actor.actorId());
        roomRepository.save(intakeRoom);
        participantService.addInitiator(dispute, actor, now);
        dispute.rejectAsNotAdmissible(
                command.disputeType(),
                command.riskLevel(),
                dispute.getIntakeResultJson(),
                actor.actorId());
        caseRepository.save(dispute);
        if (eventMode == TimelineEventMode.LEGACY_LIFECYCLE) {
            caseEventService.recordLifecycleEvent(
                    dispute.getId(),
                    intakeRoom.getId(),
                    "INTAKE_REJECTED",
                    Map.of("case_status", dispute.getCaseStatus().name()),
                    "intake-confirmed:" + dispute.getId(),
                    actor.actorId());
        }
        return new BranchResult(
                new IntakeConfirmationView(dispute.getId(), dispute.getCaseStatus(), null, null),
                intakeRoom.getId(),
                null,
                null,
                null);
    }

    public BranchResult cancel(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            String reason,
            OffsetDateTime now,
            TimelineEventMode eventMode) {
        Objects.requireNonNull(eventMode, "eventMode");
        requireParty(dispute, actor, true);
        requireOpenIntake(dispute, intakeRoom);
        intakeRoom.close(now, actor.actorId());
        roomRepository.save(intakeRoom);
        dispute.cancelIntake(actor.actorId(), now);
        caseRepository.save(dispute);
        if (eventMode == TimelineEventMode.LEGACY_LIFECYCLE) {
            caseEventService.recordLifecycleEvent(
                    dispute.getId(),
                    intakeRoom.getId(),
                    "INTAKE_CANCELLED",
                    Map.of(
                            "case_status", dispute.getCaseStatus().name(),
                            "reason", reason == null ? "" : reason),
                    "intake-cancelled:" + dispute.getId(),
                    actor.actorId());
        }
        return new BranchResult(
                new IntakeConfirmationView(dispute.getId(), dispute.getCaseStatus(), null, null),
                intakeRoom.getId(),
                null,
                null,
                null);
    }

    public BranchResult confirmRespondent(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            IntakeConfirmationCommand command,
            OffsetDateTime now,
            TimelineEventMode eventMode) {
        Objects.requireNonNull(eventMode, "eventMode");
        requireParty(dispute, actor, false);
        requireOpenIntake(dispute, intakeRoom);
        String finalIntakeResultJson = acceptedIntakeResultJson(dispute);
        assertBilateralMatrixReady(dispute.getId(), finalIntakeResultJson);
        intakeProgressService.completeRespondent(dispute, actor, now);
        participantService.inviteBoth(dispute, actor, now);
        Duration evidenceWindow = disputeProperties.evidenceWindow();
        OffsetDateTime deadline = now.plus(evidenceWindow);
        CaseRoomEntity evidenceRoom =
                roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE)
                        .orElseGet(() -> roomRepository.save(
                                CaseRoomEntity.open(
                                        roomId(),
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        now,
                                        actor.actorId())));
        phaseClockRepository.save(
                CasePhaseClockEntity.running(
                        clockId(),
                        dispute.getId(),
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        now,
                        deadline,
                        "evidence-window-" + dispute.getId(),
                        actor.actorId()));
        intakeRoom.close(now, actor.actorId());
        roomRepository.save(intakeRoom);
        dispute.admitToEvidence(
                command.disputeType(),
                command.riskLevel(),
                finalIntakeResultJson,
                deadline,
                actor.actorId());
        caseRepository.save(dispute);
        if (eventMode == TimelineEventMode.LEGACY_LIFECYCLE) {
            caseEventService.recordLifecycleEvent(
                    dispute.getId(),
                    intakeRoom.getId(),
                    "RESPONDENT_INTAKE_COMPLETED",
                    Map.of(
                            "case_status", dispute.getCaseStatus().name(),
                            "deadline_at", deadline.toString(),
                            "respondent_role", actor.role().name()),
                    "respondent-intake-completed:" + dispute.getId(),
                    actor.actorId());
            caseEventService.recordLifecycleEvent(
                    dispute.getId(),
                    evidenceRoom.getId(),
                    "EVIDENCE_OPENED",
                    Map.of(
                            "case_status", dispute.getCaseStatus().name(),
                            "deadline_at", deadline.toString(),
                            "matrix_kind", "BILATERAL_FROZEN"),
                    "evidence-opened-after-bilateral-intake:" + dispute.getId(),
                    actor.actorId());
        }
        lifecycleNotifications.evidenceRoomOpened(dispute, deadline);
        evidenceWindowCoordinator.startAfterCommit(dispute.getId(), evidenceWindow);
        return new BranchResult(
                new IntakeConfirmationView(
                        dispute.getId(),
                        dispute.getCaseStatus(),
                        RoomType.EVIDENCE,
                        deadline),
                intakeRoom.getId(),
                evidenceRoom.getId(),
                "BILATERAL_FROZEN",
                null);
    }

    public ObjectNodeAuthority requireFormalInitiatorMatrix(FulfillmentCaseEntity dispute) {
        JsonNode matrix = matrixLifecycle.requireInitiatorFrozen(dispute);
        return new ObjectNodeAuthority(
                matrix.path("matrix_kind").asText(), matrix.path("content_hash").asText());
    }

    public ObjectNodeAuthority requireFormalBilateralMatrix(FulfillmentCaseEntity dispute) {
        JsonNode matrix = matrixLifecycle.requireBilateralFrozen(dispute);
        return new ObjectNodeAuthority(
                matrix.path("matrix_kind").asText(), matrix.path("content_hash").asText());
    }

    private String acceptedIntakeResultJson(FulfillmentCaseEntity dispute) {
        return intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                .map(dossier -> dossier.getDossierJson())
                .filter(json -> json != null && !json.isBlank())
                .orElse(dispute.getIntakeResultJson());
    }

    private void assertBilateralMatrixReady(String caseId, String intakeResultJson) {
        try {
            JsonNode matrix = objectMapper.readTree(intakeResultJson).path("case_fact_matrix");
            if ("case_fact_matrix.v2".equals(matrix.path("schema_version").asText())
                    && "BILATERAL_FROZEN".equals(matrix.path("matrix_kind").asText())) {
                return;
            }
        } catch (JsonProcessingException ignored) {
            // The stable business error below covers malformed and incomplete legacy dossiers.
        }
        throw new BadRequestException(
                "respondent must complete the bilateral intake matrix before entering evidence",
                Map.of("case_id", caseId, "required_matrix_kind", "BILATERAL_FROZEN"));
    }

    private static void requireParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, boolean initiator) {
        if (initiator && actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            return;
        }
        ActorRole expected = initiator ? dispute.getInitiatorRole() : dispute.getRespondentRole();
        String expectedId = dispute.partyAssignment().idFor(expected);
        if (actor.role() != expected || !actor.actorId().equals(expectedId)) {
            throw new ForbiddenException(
                    initiator
                            ? "only the intake initiator can perform this branch"
                            : "only the intake respondent can perform this branch");
        }
    }

    private static void requireOpenIntake(
            FulfillmentCaseEntity dispute, CaseRoomEntity intakeRoom) {
        boolean intakeStatus =
                dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_PENDING
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_IN_PROGRESS
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.WAITING_SLOT_COMPLETION
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_COMPLETED;
        if (!intakeStatus
                || !RoomType.INTAKE.name().equals(dispute.getCurrentRoom())
                || dispute.getCurrentDeadlineAt() != null
                || intakeRoom.getRoomStatus() != RoomStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "Intake branch requires the current open Intake room",
                    Map.of("case_id", dispute.getId()));
        }
    }

    private void sendCounterpartySummons(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime deadline) {
        if (initiator.role() == ActorRole.USER) {
            sendSummonsTo(dispute, ActorRole.MERCHANT, deadline);
            return;
        }
        if (initiator.role() == ActorRole.MERCHANT) {
            sendSummonsTo(dispute, ActorRole.USER, deadline);
            return;
        }
        sendSummonsTo(dispute, ActorRole.USER, deadline);
        sendSummonsTo(dispute, ActorRole.MERCHANT, deadline);
    }

    private void sendSummonsTo(
            FulfillmentCaseEntity dispute,
            ActorRole recipientRole,
            OffsetDateTime deadline) {
        String recipientId = dispute.partyAssignment().idFor(recipientRole);
        notificationService.send(
                new NotificationCommand(
                        dispute.getId(),
                        dispute.getId() + ":intake-accepted",
                        recipientId,
                        recipientRole,
                        NotificationType.DISPUTE_SUMMONS,
                        "案情接待通知",
                        "对方已完成案情接待，请先进入接待室独立补充你的陈述。双方陈述完成后，系统才会统一开放证据室。",
                        "/disputes/" + dispute.getId() + "/intake",
                        deadline == null ? "{}" : "{\"deadline_at\":\"" + deadline + "\"}"));
    }

    private static String roomId() {
        return "ROOM_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String clockId() {
        return "CLOCK_" + UUID.randomUUID().toString().replace("-", "");
    }

    public enum TimelineEventMode {
        LEGACY_LIFECYCLE,
        FORMAL_TYPED_ONLY
    }

    public record BranchResult(
            IntakeConfirmationView view,
            String intakeRoomId,
            String evidenceRoomId,
            String matrixKind,
            String matrixHash) {
        public BranchResult {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(intakeRoomId, "intakeRoomId");
        }
    }

    public record ObjectNodeAuthority(String matrixKind, String contentHash) {
        public ObjectNodeAuthority {
            Objects.requireNonNull(matrixKind, "matrixKind");
            if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("matrix content hash is invalid");
            }
        }
    }
}
