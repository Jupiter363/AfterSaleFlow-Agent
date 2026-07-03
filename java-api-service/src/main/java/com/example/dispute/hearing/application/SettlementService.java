package com.example.dispute.hearing.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementConfirmationEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementProposalEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementConfirmationRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final SettlementProposalRepository proposalRepository;
    private final SettlementConfirmationRepository confirmationRepository;
    private final CaseEventService eventService;
    private final NotificationService notificationService;
    private final HearingWorkflowCoordinator hearingWorkflowCoordinator;
    private final Clock clock;

    public SettlementService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            SettlementProposalRepository proposalRepository,
            SettlementConfirmationRepository confirmationRepository,
            CaseEventService eventService,
            NotificationService notificationService,
            HearingWorkflowCoordinator hearingWorkflowCoordinator,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.proposalRepository = proposalRepository;
        this.confirmationRepository = confirmationRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.hearingWorkflowCoordinator = hearingWorkflowCoordinator;
        this.clock = clock;
    }

    @Transactional
    public SettlementView propose(
            String caseId,
            SettlementProposalCommand command,
            AuthenticatedActor actor,
            String traceId) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertCanPropose(dispute, actor);
        Instant now = clock.instant();
        Optional<SettlementProposalEntity> previous =
                proposalRepository.findTopByCaseIdOrderByProposalVersionDesc(caseId);
        previous.ifPresent(
                proposal -> {
                    if (proposal.getProposalStatus() == SettlementStatus.CONFIRMED) {
                        throw new IllegalStateException(
                                "confirmed settlement cannot be superseded");
                    }
                    if (proposal.getProposalStatus()
                            == SettlementStatus.PENDING_CONFIRMATION) {
                        proposal.supersede(actor.actorId(), now);
                        proposalRepository.save(proposal);
                    }
                });
        int version =
                previous.map(SettlementProposalEntity::getProposalVersion).orElse(0) + 1;
        SettlementProposalEntity proposal =
                proposalRepository.save(
                        SettlementProposalEntity.propose(
                                "SETTLEMENT_" + compactUuid(),
                                caseId,
                                version,
                                actor.role(),
                                actor.actorId(),
                                command.proposalText(),
                                command.proposalJson(),
                                previous.map(SettlementProposalEntity::getId).orElse(null),
                                now,
                                traceId));
        CaseRoomEntity hearingRoom = hearingRoom(caseId);
        eventService.recordLifecycleEvent(
                caseId,
                hearingRoom.getId(),
                "SETTLEMENT_PROPOSED",
                Map.of("settlement_id", proposal.getId(), "version", version),
                "settlement-proposed:" + version,
                actor.actorId());
        notifyParties(dispute, version);
        return view(proposal);
    }

    @Transactional
    public SettlementView confirm(
            String caseId,
            int version,
            AuthenticatedActor actor,
            String idempotencyKey) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertParty(dispute, actor);
        SettlementProposalEntity current =
                proposalRepository
                        .findTopByCaseIdOrderByProposalVersionDesc(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("settlement not found"));
        if (current.getProposalVersion() != version
                || current.getProposalStatus()
                        != SettlementStatus.PENDING_CONFIRMATION) {
            throw new SettlementVersionConflictException(
                    caseId, version, current.getProposalVersion());
        }
        Optional<SettlementConfirmationEntity> byKey =
                confirmationRepository.findByCaseIdAndIdempotencyKey(
                        caseId, idempotencyKey);
        Optional<SettlementConfirmationEntity> byRole =
                confirmationRepository.findByProposalIdAndParticipantRole(
                        current.getId(), actor.role());
        if (byKey.isEmpty() && byRole.isEmpty()) {
            confirmationRepository.save(
                    SettlementConfirmationEntity.confirmed(
                            "SETTLEMENT_CONFIRM_" + compactUuid(),
                            caseId,
                            current.getId(),
                            version,
                            actor.role(),
                            actor.actorId(),
                            idempotencyKey,
                            clock.instant()));
        }
        long confirmations =
                confirmationRepository.countByProposalIdAndConfirmationStatus(
                        current.getId(), "CONFIRMED");
        if (confirmations >= 2) {
            current.confirm("system", clock.instant());
            proposalRepository.save(current);
            eventService.recordLifecycleEvent(
                    caseId,
                    hearingRoom(caseId).getId(),
                    "SETTLEMENT_CONFIRMED",
                    Map.of("settlement_id", current.getId(), "version", version),
                    "settlement-confirmed:" + version,
                    "system");
            hearingWorkflowCoordinator.settlementConfirmedAfterCommit(
                    caseId, version);
        }
        return view(current);
    }

    @Transactional(readOnly = true)
    public SettlementView get(
            String caseId, int version, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        return view(
                proposalRepository
                        .findByCaseIdAndProposalVersion(caseId, version)
                        .orElseThrow(() -> new IllegalArgumentException("settlement not found")));
    }

    @Transactional(readOnly = true)
    public List<SettlementView> list(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        return proposalRepository.findAllByCaseIdOrderByProposalVersionDesc(caseId)
                .stream()
                .map(this::view)
                .toList();
    }

    private SettlementView view(SettlementProposalEntity proposal) {
        List<ActorRole> confirmedRoles =
                confirmationRepository
                        .findAllByProposalIdAndConfirmationStatus(
                                proposal.getId(), "CONFIRMED")
                        .stream()
                        .map(SettlementConfirmationEntity::getParticipantRole)
                        .toList();
        return new SettlementView(
                proposal.getId(),
                proposal.getCaseId(),
                proposal.getProposalVersion(),
                proposal.getProposalStatus(),
                proposal.getProposedByRole(),
                proposal.getProposalText(),
                proposal.getProposalJson(),
                confirmedRoles,
                proposal.getCreatedAt());
    }

    private FulfillmentCaseEntity lockedHearing(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        if (dispute.getCaseStatus() != CaseStatus.HEARING_OPEN
                && dispute.getCaseStatus() != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "settlement is unavailable from " + dispute.getCaseStatus());
        }
        return dispute;
    }

    private CaseRoomEntity hearingRoom(String caseId) {
        return roomRepository
                .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
    }

    private void notifyParties(FulfillmentCaseEntity dispute, int version) {
        sendConfirmationNotice(dispute, dispute.getUserId(), ActorRole.USER, version);
        sendConfirmationNotice(
                dispute, dispute.getMerchantId(), ActorRole.MERCHANT, version);
    }

    private void sendConfirmationNotice(
            FulfillmentCaseEntity dispute,
            String recipientId,
            ActorRole role,
            int version) {
        notificationService.send(
                new NotificationCommand(
                        dispute.getId(),
                        "settlement-proposed:" + version,
                        recipientId,
                        role,
                        NotificationType.SETTLEMENT_CONFIRMATION_REQUIRED,
                        "新的和解方案待确认",
                        "请进入争议审判庭核对当前版本，双方确认同一版本后才视为达成一致。",
                        "/disputes/" + dispute.getId() + "/hearing",
                        "{\"settlement_version\":" + version + "}"));
    }

    private static void assertCanPropose(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (actor.role() == ActorRole.SYSTEM) return;
        assertParty(dispute, actor);
    }

    private static void assertParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER
                                && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) {
            throw new ForbiddenException("only case parties may confirm settlement");
        }
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
            throw new ForbiddenException("actor cannot access settlement");
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
