package com.example.dispute.hearing.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HearingRoundService {

    public static final int MAX_ROUNDS = 3;

    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository hearingStateRepository;
    private final HearingRoundRepository roundRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseEventService eventService;
    private final HearingWorkflowCoordinator workflowCoordinator;
    private final Clock clock;

    public HearingRoundService(
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository hearingStateRepository,
            HearingRoundRepository roundRepository,
            CaseRoomRepository roomRepository,
            CaseEventService eventService,
            HearingWorkflowCoordinator workflowCoordinator,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.roundRepository = roundRepository;
        this.roomRepository = roomRepository;
        this.eventService = eventService;
        this.workflowCoordinator = workflowCoordinator;
        this.clock = clock;
    }

    @Transactional
    public HearingRoundView completeNext(
            String caseId,
            CompleteHearingRoundCommand command,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertController(actor);
        int roundNo =
                roundRepository
                                .findTopByCaseIdOrderByRoundNoDesc(caseId)
                                .map(HearingRoundEntity::getRoundNo)
                                .orElse(0)
                        + 1;
        if (roundNo > MAX_ROUNDS) {
            throw new IllegalStateException("hearing round limit reached");
        }
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "HEARING_ROUND_" + compactUuid(),
                        caseId,
                        hearingStateRepository
                                .findByCaseId(caseId)
                                .map(state -> state.getId())
                                .orElse(null),
                        roundNo,
                        command.dossierVersion(),
                        clock.instant(),
                        actor.actorId());
        HearingStopReason stopReason =
                command.factsSufficient()
                        ? HearingStopReason.FACTS_SUFFICIENT
                        : roundNo == MAX_ROUNDS
                                ? HearingStopReason.MAX_ROUNDS
                                : null;
        round.complete(
                command.summaryJson(), stopReason, clock.instant(), actor.actorId());
        HearingRoundEntity saved = roundRepository.save(round);
        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        eventService.recordLifecycleEvent(
                caseId,
                hearingRoom.getId(),
                "HEARING_ROUND_COMPLETED",
                Map.of(
                        "round_no", roundNo,
                        "stop_reason",
                                stopReason == null ? "CONTINUE" : stopReason.name()),
                "hearing-round-completed:" + roundNo,
                actor.actorId());
        workflowCoordinator.roundCompletedAfterCommit(
                caseId, roundNo, command.factsSufficient());
        return view(caseId, saved);
    }

    @Transactional
    public HearingRoundView expire(
            String caseId, int dossierVersion, String actorId) {
        lockedHearing(caseId);
        HearingRoundEntity round =
                roundRepository
                        .findTopByCaseIdOrderByRoundNoDesc(caseId)
                        .filter(item -> item.getClosedAt() == null)
                        .orElseGet(
                                () -> {
                                    int next =
                                            roundRepository
                                                            .findTopByCaseIdOrderByRoundNoDesc(caseId)
                                                            .map(HearingRoundEntity::getRoundNo)
                                                            .orElse(0)
                                                    + 1;
                                    return HearingRoundEntity.open(
                                            "HEARING_ROUND_" + compactUuid(),
                                            caseId,
                                            hearingStateRepository
                                                    .findByCaseId(caseId)
                                                    .map(state -> state.getId())
                                                    .orElse(null),
                                            Math.min(next, MAX_ROUNDS),
                                            dossierVersion,
                                            clock.instant(),
                                            actorId);
                                });
        round.complete(
                "{\"deadline_expired\":true}",
                HearingStopReason.DEADLINE_EXPIRED,
                clock.instant(),
                actorId);
        return view(caseId, roundRepository.save(round));
    }

    @Transactional(readOnly = true)
    public List<HearingRoundView> list(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        return roundRepository.findAllByCaseIdOrderByRoundNoAsc(caseId)
                .stream()
                .map(round -> view(caseId, round))
                .toList();
    }

    private FulfillmentCaseEntity lockedHearing(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        if (dispute.getCaseStatus() != CaseStatus.HEARING_OPEN
                && dispute.getCaseStatus() != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "hearing round is unavailable from " + dispute.getCaseStatus());
        }
        return dispute;
    }

    private static void assertController(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.SYSTEM
                && actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN) {
            throw new ForbiddenException("hearing round completion requires trusted actor");
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
            throw new ForbiddenException("actor cannot access hearing rounds");
        }
    }

    private static HearingRoundView view(
            String caseId, HearingRoundEntity round) {
        return new HearingRoundView(
                round.getId(),
                caseId,
                round.getRoundNo(),
                round.getRoundStatus(),
                round.getDossierVersion(),
                round.getStopReason(),
                round.getSummaryJson(),
                round.getOpenedAt(),
                round.getClosedAt());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
