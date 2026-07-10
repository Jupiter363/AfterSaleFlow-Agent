package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically imports one dispute candidate from a trusted platform adapter.
 *
 * <p>The external source pair is the business idempotency key; request keys may
 * change across adapter retries and therefore cannot be the sole identity.
 */
@Service
public class ExternalCaseImportTransactionService {

    private final FulfillmentCaseRepository repository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final ParticipantService participantService;
    private final IntakeAgentTurnService intakeAgentTurnService;
    private final DisputeProperties properties;
    private final Clock clock;

    public ExternalCaseImportTransactionService(
            FulfillmentCaseRepository repository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            ParticipantService participantService,
            IntakeAgentTurnService intakeAgentTurnService,
            DisputeProperties properties,
            Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.participantService = participantService;
        this.intakeAgentTurnService = intakeAgentTurnService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute import requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        ActorRole initiatorRole = partyInitiatorRole(command.initiatorRole());
        var requestReplay =
                repository.findByCreationIdempotencyKey(idempotencyKey);
        if (requestReplay.isPresent()) {
            FulfillmentCaseEntity existing = requestReplay.orElseThrow();
            if (!Objects.equals(existing.getSourceSystem(), command.sourceSystem())
                    || !Objects.equals(
                            existing.getExternalCaseRef(),
                            command.externalCaseReference())) {
                throw new IdempotencyConflictException(
                        "import idempotency key already belongs to another external case");
            }
            return restoreExisting(existing, actor);
        }
        return repository
                .findBySourceSystemAndExternalCaseRef(
                        command.sourceSystem(), command.externalCaseReference())
                .map(existing -> restoreExisting(existing, actor))
                .orElseGet(
                        () -> {
                            FulfillmentCaseEntity entity =
                                    FulfillmentCaseEntity.imported(
                                            "CASE_" + compactUuid(),
                                            command.orderReference(),
                                            command.afterSalesReference(),
                                            command.logisticsReference(),
                                            command.userId(),
                                            command.merchantId(),
                                            initiatorRole,
                                            idempotencyKey,
                                            command.disputeType(),
                                            command.title(),
                                            command.description(),
                                            command.riskLevel(),
                                            command.caseStatus(),
                                            command.currentRoom(),
                                            command.currentDeadlineAt(),
                                            command.sourceSystem(),
                                            command.externalCaseReference(),
                                            actor.actorId());
                            FulfillmentCaseEntity saved = repository.save(entity);
                            materializeCurrentRoom(saved, command, actor.actorId());
                            participantService.ensureImportedParties(
                                    saved, actor, OffsetDateTime.now(clock));
                            startIntakeIfNeeded(saved, command, initiatorRole, traceId, requestId);
                            return view(saved);
                        });
    }

    private ImportedDisputeView restoreExisting(
            FulfillmentCaseEntity existing,
            AuthenticatedActor actor) {
        materializePersistedCurrentRoom(existing, actor.actorId());
        participantService.ensureImportedParties(
                existing,
                actor,
                OffsetDateTime.now(clock));
        return view(existing);
    }

    private void startIntakeIfNeeded(
            FulfillmentCaseEntity saved,
            ImportDisputeCommand command,
            ActorRole initiatorRole,
            String traceId,
            String requestId) {
        if (isTerminal(command.caseStatus()) || currentRoom(command) != RoomType.INTAKE) {
            return;
        }
        AuthenticatedActor intakeActor =
                new AuthenticatedActor(
                        initiatorRole == ActorRole.MERCHANT
                                ? command.merchantId()
                                : command.userId(),
                        initiatorRole);
        IntakeLobbySeed seed =
                new IntakeLobbySeed(
                        command.orderReference(),
                        command.afterSalesReference(),
                        command.logisticsReference(),
                        initiatorRole.name(),
                        command.description(),
                        command.requestedOutcomeHint(),
                        command.claimResolutionSeed(),
                        command.respondentAttitudeSeed());
        intakeAgentTurnService.startInitialTurn(
                saved.getId(),
                intakeActor,
                seed,
                traceId,
                requestId);
    }

    private void materializeCurrentRoom(
            FulfillmentCaseEntity dispute,
            ImportDisputeCommand command,
            String actorId) {
        materializeCurrentRoom(
                dispute,
                command.caseStatus(),
                command.currentRoom(),
                command.currentDeadlineAt(),
                actorId);
    }

    private void materializePersistedCurrentRoom(
            FulfillmentCaseEntity dispute,
            String actorId) {
        materializeCurrentRoom(
                dispute,
                dispute.getCaseStatus(),
                dispute.getCurrentRoom(),
                dispute.getCurrentDeadlineAt(),
                actorId);
    }

    private void materializeCurrentRoom(
            FulfillmentCaseEntity dispute,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt,
            String actorId) {
        RoomType roomType = currentRoom(caseStatus, currentRoom);
        OffsetDateTime now = OffsetDateTime.now(clock);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                isTerminal(caseStatus)
                                                        ? CaseRoomEntity.closed(
                                                                roomId(),
                                                                dispute.getId(),
                                                                roomType,
                                                                now,
                                                                actorId)
                                                        : CaseRoomEntity.open(
                                                                roomId(),
                                                                dispute.getId(),
                                                                roomType,
                                                                now,
                                                                actorId)));
        PhaseClockType clockType = phaseClock(roomType);
        if (clockType == null
                || clockRepository
                        .findByCaseIdAndClockType(dispute.getId(), clockType)
                        .isPresent()) {
            return;
        }
        OffsetDateTime deadline =
                currentDeadlineAt == null
                        ? now.plus(
                                clockType == PhaseClockType.EVIDENCE_SUBMISSION
                                        ? properties.evidenceWindow()
                                        : properties.hearingWindow())
                        : currentDeadlineAt;
        if (!deadline.isAfter(now)) {
            deadline =
                    now.plus(
                            clockType == PhaseClockType.EVIDENCE_SUBMISSION
                                    ? properties.evidenceWindow()
                                    : properties.hearingWindow());
        }
        clockRepository.save(
                CasePhaseClockEntity.running(
                        clockId(),
                        dispute.getId(),
                        room.getId(),
                        clockType,
                        now,
                        deadline,
                        workflowId(clockType, dispute.getId()),
                        actorId));
    }

    private static RoomType currentRoom(ImportDisputeCommand command) {
        return currentRoom(command.caseStatus(), command.currentRoom());
    }

    private static RoomType currentRoom(
            CaseStatus caseStatus,
            String currentRoom) {
        if (currentRoom != null && !currentRoom.isBlank()) {
            return RoomType.valueOf(currentRoom);
        }
        return switch (caseStatus) {
            case INTAKE_PENDING, INTAKE_IN_PROGRESS, INTAKE_COMPLETED, NOT_ADMISSIBLE ->
                    RoomType.INTAKE;
            case EVIDENCE_OPEN, EVIDENCE_SEALED, DOSSIER_BUILDING, DOSSIER_BUILT ->
                    RoomType.EVIDENCE;
            case HEARING, HEARING_OPEN, WAITING_EVIDENCE, SETTLEMENT_PENDING,
                    DRAFT_READY, DELIBERATION_RUNNING -> RoomType.HEARING;
            default -> RoomType.REVIEW;
        };
    }

    private static PhaseClockType phaseClock(RoomType roomType) {
        return switch (roomType) {
            case EVIDENCE -> PhaseClockType.EVIDENCE_SUBMISSION;
            case HEARING -> PhaseClockType.HEARING;
            case INTAKE, REVIEW -> null;
        };
    }

    private static boolean isTerminal(CaseStatus status) {
        return status == CaseStatus.CLOSED
                || status == CaseStatus.CANCELLED
                || status == CaseStatus.NOT_ADMISSIBLE;
    }

    private static String workflowId(PhaseClockType type, String caseId) {
        return type == PhaseClockType.EVIDENCE_SUBMISSION
                ? "evidence-window-" + caseId
                : "hearing-window-" + caseId;
    }

    private static String roomId() {
        return "ROOM_" + compactUuid();
    }

    private static String clockId() {
        return "CLOCK_" + compactUuid();
    }

    private static ImportedDisputeView view(FulfillmentCaseEntity entity) {
        return new ImportedDisputeView(
                entity.getId(),
                entity.getOrderId(),
                entity.getAfterSaleId(),
                entity.getLogisticsId(),
                entity.getUserId(),
                entity.getMerchantId(),
                entity.getDisputeType(),
                entity.getSourceType().name(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getRiskLevel(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getCaseStatus(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt(),
                pendingAction(entity.getCaseStatus()),
                entity.getInitiatorRole().name());
    }

    private static String pendingAction(CaseStatus status) {
        return switch (status) {
            case INTAKE_PENDING,
                    INTAKE_IN_PROGRESS,
                    WAITING_SLOT_COMPLETION,
                    INTAKE_COMPLETED -> "COMPLETE_INTAKE";
            case EVIDENCE_OPEN, WAITING_EVIDENCE -> "SUBMIT_EVIDENCE";
            case EVIDENCE_SEALED -> "ENTER_HEARING";
            case HEARING_OPEN, HEARING -> "PARTICIPATE_HEARING";
            case SETTLEMENT_PENDING -> "REVIEW_SETTLEMENT";
            case DRAFT_READY,
                    DELIBERATION_RUNNING,
                    REVIEW_PENDING,
                    WAITING_HUMAN_REVIEW,
                    REMEDY_PLANNED -> "AWAIT_REVIEW";
            case APPROVED_FOR_EXECUTION, EXECUTING -> "TRACK_EXECUTION";
            case CLOSED, CANCELLED, MANUAL_HANDOFF, NOT_ADMISSIBLE -> "VIEW_OUTCOME";
            default -> "CONTINUE_CASE";
        };
    }

    private static ActorRole partyInitiatorRole(String role) {
        ActorRole parsed = ActorRole.valueOf(role);
        if (parsed != ActorRole.USER && parsed != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("imported dispute initiator must be USER or MERCHANT");
        }
        return parsed;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
