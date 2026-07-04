package com.example.dispute.room.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
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

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository phaseClockRepository;
    private final ParticipantService participantService;
    private final NotificationService notificationService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final CaseEventService caseEventService;
    private final DisputeProperties disputeProperties;
    private final Clock clock;

    public IntakeRoomService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository phaseClockRepository,
            ParticipantService participantService,
            NotificationService notificationService,
            CaseLifecycleNotificationService lifecycleNotifications,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            CaseEventService caseEventService,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.phaseClockRepository = phaseClockRepository;
        this.participantService = participantService;
        this.notificationService = notificationService;
        this.lifecycleNotifications = lifecycleNotifications;
        this.evidenceWindowCoordinator = evidenceWindowCoordinator;
        this.caseEventService = caseEventService;
        this.disputeProperties = disputeProperties;
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
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .orElseGet(
                                () ->
                                        CaseRoomEntity.closed(
                                                roomId(),
                                                caseId,
                                                RoomType.INTAKE,
                                                now,
                                                actor.actorId()));
        intakeRoom.close(now, actor.actorId());
        roomRepository.save(intakeRoom);

        if (!command.admissible()) {
            participantService.addInitiator(dispute, actor, now);
            dispute.rejectAsNotAdmissible(
                    command.disputeType(),
                    command.riskLevel(),
                    dispute.getIntakeResultJson(),
                    actor.actorId());
            caseRepository.save(dispute);
            caseEventService.recordLifecycleEvent(
                    caseId,
                    intakeRoom.getId(),
                    "INTAKE_REJECTED",
                    Map.of("case_status", dispute.getCaseStatus().name()),
                    "intake-confirmed:" + caseId,
                    actor.actorId());
            return new IntakeConfirmationView(
                    caseId, dispute.getCaseStatus(), null, null);
        }

        participantService.inviteBoth(dispute, actor, now);
        Duration evidenceWindow = disputeProperties.evidenceWindow();
        OffsetDateTime deadline = now.plus(evidenceWindow);
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
        caseEventService.recordLifecycleEvent(
                caseId,
                evidenceRoom.getId(),
                "EVIDENCE_OPENED",
                Map.of(
                        "case_status",
                        dispute.getCaseStatus().name(),
                        "deadline_at",
                        deadline.toString()),
                "intake-confirmed:" + caseId,
                actor.actorId());
        sendCounterpartySummons(dispute, actor, deadline);
        lifecycleNotifications.evidenceRoomOpened(dispute, deadline);
        evidenceWindowCoordinator.startAfterCommit(caseId, evidenceWindow);
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
        if (initiator.role() == ActorRole.USER) {
            sendSummonsTo(dispute, dispute.getMerchantId(), ActorRole.MERCHANT, deadline);
            return;
        }
        if (initiator.role() == ActorRole.MERCHANT) {
            sendSummonsTo(dispute, dispute.getUserId(), ActorRole.USER, deadline);
            return;
        }
        sendSummonsTo(dispute, dispute.getUserId(), ActorRole.USER, deadline);
        sendSummonsTo(dispute, dispute.getMerchantId(), ActorRole.MERCHANT, deadline);
    }

    private void sendSummonsTo(
            FulfillmentCaseEntity dispute,
            String recipientId,
            ActorRole recipientRole,
            OffsetDateTime deadline) {
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
