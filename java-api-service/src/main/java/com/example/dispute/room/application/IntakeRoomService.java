package com.example.dispute.room.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
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
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeRoomService {

    private static final Duration EVIDENCE_WINDOW = Duration.ofHours(2);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository phaseClockRepository;
    private final ParticipantService participantService;
    private final NotificationService notificationService;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final Clock clock;

    public IntakeRoomService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository phaseClockRepository,
            ParticipantService participantService,
            NotificationService notificationService,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.phaseClockRepository = phaseClockRepository;
        this.participantService = participantService;
        this.notificationService = notificationService;
        this.evidenceWindowCoordinator = evidenceWindowCoordinator;
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
        sendCounterpartySummons(dispute, actor, deadline);
        evidenceWindowCoordinator.startAfterCommit(caseId, EVIDENCE_WINDOW);
        return new IntakeConfirmationView(
                caseId,
                dispute.getCaseStatus(),
                RoomType.EVIDENCE,
                deadline);
    }

    private void sendCounterpartySummons(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime deadline) {
        boolean userInitiated = initiator.role() == ActorRole.USER;
        String recipientId =
                userInitiated ? dispute.getMerchantId() : dispute.getUserId();
        ActorRole recipientRole =
                userInitiated ? ActorRole.MERCHANT : ActorRole.USER;
        notificationService.send(
                new NotificationCommand(
                        dispute.getId(),
                        dispute.getId() + ":intake-accepted",
                        recipientId,
                        recipientRole,
                        NotificationType.DISPUTE_SUMMONS,
                        "争议审理传票",
                        "订单争议已受理，请在两小时内进入证据书记官室。",
                        "/disputes/" + dispute.getId() + "/evidence",
                        "{\"deadline_at\":\"" + deadline + "\"}"));
    }

    private static String roomId() {
        return "ROOM_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String clockId() {
        return "CLOCK_" + UUID.randomUUID().toString().replace("-", "");
    }
}
