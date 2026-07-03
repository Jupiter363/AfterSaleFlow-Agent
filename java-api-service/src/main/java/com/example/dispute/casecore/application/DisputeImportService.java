package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently imports dispute candidates from trusted platform adapters.
 *
 * <p>The external source pair is the business idempotency key; request keys may
 * change across adapter retries and therefore cannot be the sole identity.
 */
@Service
public class DisputeImportService {

    private final FulfillmentCaseRepository repository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final DisputeProperties properties;
    private final Clock clock;

    public DisputeImportService(
            FulfillmentCaseRepository repository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            DisputeProperties properties,
            Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute import requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        return repository
                .findBySourceSystemAndExternalCaseRef(
                        command.sourceSystem(), command.externalCaseReference())
                .map(
                        existing -> {
                            materializeCurrentRoom(existing, command, actor.actorId());
                            return view(existing);
                        })
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
                            return view(saved);
                        });
    }

    private void materializeCurrentRoom(
            FulfillmentCaseEntity dispute,
            ImportDisputeCommand command,
            String actorId) {
        RoomType roomType = currentRoom(command);
        OffsetDateTime now = OffsetDateTime.now(clock);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                isTerminal(command.caseStatus())
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
                command.currentDeadlineAt() == null
                        ? now.plus(
                                clockType == PhaseClockType.EVIDENCE_SUBMISSION
                                        ? properties.evidenceWindow()
                                        : properties.hearingWindow())
                        : command.currentDeadlineAt();
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
        if (command.currentRoom() != null && !command.currentRoom().isBlank()) {
            return RoomType.valueOf(command.currentRoom());
        }
        return switch (command.caseStatus()) {
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
                entity.getSourceType().name(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getCaseStatus(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt());
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
