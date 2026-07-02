package com.example.dispute.room.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeRoomService {

    private static final Duration EVIDENCE_WINDOW = Duration.ofHours(2);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository phaseClockRepository;
    private final ParticipantService participantService;
    private final Clock clock;

    public IntakeRoomService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository phaseClockRepository,
            ParticipantService participantService,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.phaseClockRepository = phaseClockRepository;
        this.participantService = participantService;
        this.clock = clock;
    }

    @Transactional
    public IntakeConfirmationView confirm(
            String caseId,
            AuthenticatedActor actor,
            IntakeConfirmationCommand command) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        OffsetDateTime now = OffsetDateTime.now(clock);
        CaseRoomEntity intakeRoom =
                CaseRoomEntity.closed(
                        roomId(), caseId, RoomType.INTAKE, now, actor.actorId());
        roomRepository.save(intakeRoom);

        if (!command.admissible()) {
            participantService.addInitiator(dispute, actor, now);
            dispute.rejectAsNotAdmissible(
                    command.disputeType(),
                    command.riskLevel(),
                    dispute.getIntakeResultJson(),
                    actor.actorId());
            caseRepository.save(dispute);
            return new IntakeConfirmationView(
                    caseId, dispute.getCaseStatus(), null, null);
        }

        participantService.inviteBoth(dispute, actor, now);
        OffsetDateTime deadline = now.plus(EVIDENCE_WINDOW);
        CaseRoomEntity evidenceRoom =
                CaseRoomEntity.open(
                        roomId(), caseId, RoomType.EVIDENCE, now, actor.actorId());
        roomRepository.save(evidenceRoom);
        phaseClockRepository.save(
                CasePhaseClockEntity.running(
                        clockId(),
                        caseId,
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        now,
                        deadline,
                        "evidence-window-" + caseId,
                        actor.actorId()));
        dispute.admitToEvidence(
                command.disputeType(),
                command.riskLevel(),
                dispute.getIntakeResultJson(),
                deadline,
                actor.actorId());
        caseRepository.save(dispute);
        return new IntakeConfirmationView(
                caseId,
                dispute.getCaseStatus(),
                RoomType.EVIDENCE,
                deadline);
    }

    private static String roomId() {
        return "ROOM_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String clockId() {
        return "CLOCK_" + UUID.randomUUID().toString().replace("-", "");
    }
}
