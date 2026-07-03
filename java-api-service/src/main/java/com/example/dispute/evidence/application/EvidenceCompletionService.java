package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceCompletionService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidencePartyCompletionRepository completionRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final EvidenceDossierFreezer dossierFreezer;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final CaseEventService caseEventService;
    private final NotificationService notificationService;
    private final HearingWorkflowCoordinator hearingWorkflowCoordinator;
    private final DisputeProperties disputeProperties;
    private final Clock clock;

    public EvidenceCompletionService(
            FulfillmentCaseRepository caseRepository,
            EvidencePartyCompletionRepository completionRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            EvidenceDossierFreezer dossierFreezer,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            CaseEventService caseEventService,
            NotificationService notificationService,
            HearingWorkflowCoordinator hearingWorkflowCoordinator,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.completionRepository = completionRepository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.dossierFreezer = dossierFreezer;
        this.evidenceWindowCoordinator = evidenceWindowCoordinator;
        this.caseEventService = caseEventService;
        this.notificationService = notificationService;
        this.hearingWorkflowCoordinator = hearingWorkflowCoordinator;
        this.disputeProperties = disputeProperties;
        this.clock = clock;
    }

    @Transactional
    public EvidenceCompletionView complete(
            String caseId,
            AuthenticatedActor actor,
            String idempotencyKey) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        int dossierVersion =
                isEvidenceOpen(dispute)
                        ? completionVersion(caseId)
                        : dossierFreezer.latestVersion(caseId);
        Optional<EvidencePartyCompletionEntity> existing =
                completionRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        Optional<EvidencePartyCompletionEntity> roleCompletion =
                completionRepository.findByCaseIdAndDossierVersionAndParticipantRole(
                        caseId, dossierVersion, actor.role());
        if (existing.isEmpty() && roleCompletion.isEmpty()) {
            completionRepository.save(
                    EvidencePartyCompletionEntity.completed(
                            "EVIDENCE_COMPLETE_" + compactUuid(),
                            caseId,
                            dossierVersion,
                            actor.role(),
                            actor.actorId(),
                            idempotencyKey,
                            clock.instant()));
        }
        evidenceWindowCoordinator.signalPartyCompletedAfterCommit(
                caseId, actor.role().name());
        long completed =
                completionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(
                        caseId, dossierVersion, "COMPLETED");
        if (completed < 2) {
            return new EvidenceCompletionView(
                    caseId, dossierVersion, actor.role(), false, "EVIDENCE",
                    dispute.getCurrentDeadlineAt());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        dossierFreezer.freeze(caseId, dossierVersion, actor.actorId());
        boolean transitioning = isEvidenceOpen(dispute);
        CaseRoomEntity hearingRoom =
                sealEvidenceAndOpenHearing(dispute, now, actor.actorId(), true);
        if (transitioning) {
            announceHearingOpened(
                    dispute, hearingRoom, dossierVersion, "BOTH_PARTIES_COMPLETED");
            hearingWorkflowCoordinator.startAfterCommit(caseId, dossierVersion);
        }
        return new EvidenceCompletionView(
                caseId,
                dossierVersion,
                actor.role(),
                true,
                "HEARING",
                dispute.getCurrentDeadlineAt());
    }

    @Transactional(readOnly = true)
    public EvidenceCompletionStatusView status(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        int dossierVersion =
                isEvidenceOpen(dispute)
                        ? completionVersion(caseId)
                        : dossierFreezer.latestVersion(caseId);
        var completions =
                completionRepository
                        .findAllByCaseIdAndDossierVersionAndCompletionStatus(
                                caseId, dossierVersion, "COMPLETED");
        boolean userCompleted =
                completions.stream()
                        .anyMatch(item -> item.getParticipantRole() == ActorRole.USER);
        boolean merchantCompleted =
                completions.stream()
                        .anyMatch(item -> item.getParticipantRole() == ActorRole.MERCHANT);
        boolean sealed = dispute.getCaseStatus() != CaseStatus.EVIDENCE_OPEN;
        return new EvidenceCompletionStatusView(
                caseId,
                dossierVersion,
                userCompleted,
                merchantCompleted,
                sealed,
                dispute.getCurrentRoom(),
                dispute.getCurrentDeadlineAt());
    }

    @Transactional
    public EvidenceCompletionStatusView expire(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        int dossierVersion = completionVersion(caseId);
        if (dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                || dispute.getCaseStatus() == CaseStatus.EVIDENCE_SEALED) {
            dossierFreezer.freeze(caseId, dossierVersion, "evidence-deadline");
            CaseRoomEntity hearingRoom =
                    sealEvidenceAndOpenHearing(
                            dispute,
                            OffsetDateTime.now(clock),
                            "evidence-deadline",
                            false);
            announceHearingOpened(
                    dispute, hearingRoom, dossierVersion, "DEADLINE_EXPIRED");
            hearingWorkflowCoordinator.startAfterCommit(caseId, dossierVersion);
        }
        return status(caseId, new AuthenticatedActor("evidence-deadline", ActorRole.SYSTEM));
    }

    private int completionVersion(String caseId) {
        return completionRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .map(EvidencePartyCompletionEntity::getDossierVersion)
                .orElseGet(() -> dossierFreezer.targetVersion(caseId));
    }

    private CaseRoomEntity sealEvidenceAndOpenHearing(
            FulfillmentCaseEntity dispute,
            OffsetDateTime now,
            String actorId,
            boolean completedEarly) {
        CaseRoomEntity evidenceRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE)
                        .orElseThrow(() -> new IllegalArgumentException("evidence room not found"));
        if (evidenceRoom.getRoomStatus() != RoomStatus.SEALED) {
            evidenceRoom.seal(now, actorId);
            roomRepository.save(evidenceRoom);
        }
        CasePhaseClockEntity evidenceClock =
                clockRepository
                        .findByCaseIdAndClockType(
                                dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION)
                        .orElseThrow(() -> new IllegalArgumentException("evidence clock not found"));
        if (completedEarly) {
            evidenceClock.completeEarly(now, actorId);
        } else {
            evidenceClock.expire(now, actorId);
        }
        clockRepository.save(evidenceClock);

        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                CaseRoomEntity.open(
                                                        "ROOM_" + compactUuid(),
                                                        dispute.getId(),
                                                        RoomType.HEARING,
                                                        now,
                                                        actorId)));
        OffsetDateTime hearingDeadline =
                now.plus(disputeProperties.hearingWindow());
        if (clockRepository
                .findByCaseIdAndClockType(dispute.getId(), PhaseClockType.HEARING)
                .isEmpty()) {
            clockRepository.save(
                    CasePhaseClockEntity.running(
                            "CLOCK_" + compactUuid(),
                            dispute.getId(),
                            hearingRoom.getId(),
                            PhaseClockType.HEARING,
                            now,
                            hearingDeadline,
                            "hearing-window-" + dispute.getId(),
                            actorId));
        }
        if (dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                || dispute.getCaseStatus() == CaseStatus.EVIDENCE_SEALED) {
            dispute.openHearing(hearingDeadline, actorId);
            caseRepository.save(dispute);
        }
        return hearingRoom;
    }

    private static void assertParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER
                                && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) throw new SecurityException("only case parties can complete evidence");
    }

    private static void assertCanAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new SecurityException("actor cannot access evidence completion");
        }
    }

    private void announceHearingOpened(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity hearingRoom,
            int dossierVersion,
            String reason) {
        String eventKey = "hearing-opened:" + dossierVersion;
        caseEventService.recordLifecycleEvent(
                dispute.getId(),
                hearingRoom.getId(),
                "HEARING_OPENED",
                Map.of(
                        "dossier_version", dossierVersion,
                        "reason", reason,
                        "deadline_at", dispute.getCurrentDeadlineAt().toString()),
                eventKey,
                "system");
        sendHearingNotice(
                dispute, dispute.getUserId(), ActorRole.USER, eventKey, reason);
        sendHearingNotice(
                dispute,
                dispute.getMerchantId(),
                ActorRole.MERCHANT,
                eventKey,
                reason);
    }

    private void sendHearingNotice(
            FulfillmentCaseEntity dispute,
            String recipientId,
            ActorRole recipientRole,
            String eventKey,
            String reason) {
        notificationService.send(
                new NotificationCommand(
                        dispute.getId(),
                        eventKey,
                        recipientId,
                        recipientRole,
                        NotificationType.HEARING_OPENED,
                        "争议审判庭已开放",
                        "证据卷宗已封存，请在三小时内进入审判庭参与处理。",
                        "/disputes/" + dispute.getId() + "/hearing",
                        "{\"reason\":\""
                                + reason
                                + "\",\"deadline_at\":\""
                                + dispute.getCurrentDeadlineAt()
                                + "\"}"));
    }

    private static boolean isEvidenceOpen(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                || dispute.getCaseStatus() == CaseStatus.EVIDENCE_SEALED;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
