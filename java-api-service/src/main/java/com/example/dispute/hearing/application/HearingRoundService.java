package com.example.dispute.hearing.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HearingRoundService {

    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository hearingStateRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseEventService eventService;
    private final HearingWorkflowCoordinator workflowCoordinator;
    private final HearingCourtOrchestrator courtOrchestrator;
    private final DisputeProperties disputeProperties;
    private final Clock clock;

    public HearingRoundService(
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository hearingStateRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            CaseRoomRepository roomRepository,
            CaseEventService eventService,
            HearingWorkflowCoordinator workflowCoordinator,
            HearingCourtOrchestrator courtOrchestrator,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.roomRepository = roomRepository;
        this.eventService = eventService;
        this.workflowCoordinator = workflowCoordinator;
        this.courtOrchestrator = courtOrchestrator;
        this.disputeProperties = disputeProperties;
        this.clock = clock;
    }

    @Transactional
    public HearingRoundView ensureInitialRoundOpen(
            String caseId,
            int dossierVersion,
            String actorId) {
        lockedHearing(caseId);
        return roundRepository
                .findTopByCaseIdOrderByRoundNoDesc(caseId)
                .map(existing -> view(caseId, existing, null))
                .orElseGet(
                        () -> {
                            Instant now = clock.instant();
                            HearingRoundEntity round =
                                    roundRepository.save(
                                            HearingRoundEntity.open(
                                                    "HEARING_ROUND_" + compactUuid(),
                                                    caseId,
                                                    hearingStateRepository
                                                            .findByCaseId(caseId)
                                                            .map(state -> state.getId())
                                                            .orElse(null),
                                                    1,
                                                    dossierVersion,
                                                    now.plus(
                                                            disputeProperties
                                                                    .hearingRoundWindow()),
                                                    now,
                                                    actorId));
                            recordRoundEvent(
                                    caseId,
                                    "HEARING_ROUND_OPENED",
                                    Map.of(
                                            "round_no",
                                            1,
                                            "dossier_version",
                                            dossierVersion,
                                            "round_deadline_at",
                                            round.getRoundDeadlineAt().toString()),
                                    "hearing-round-opened:1",
                                    actorId);
                            courtOrchestrator.afterRoundOpenedAfterCommit(
                                    caseId, 1, traceId(1));
                            return view(caseId, round, null);
                        });
    }

    @Transactional
    public HearingRoundView completeNext(
            String caseId,
            CompleteHearingRoundCommand command,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertController(actor);
        Instant now = clock.instant();
        int roundNo =
                roundRepository
                                .findTopByCaseIdOrderByRoundNoDesc(caseId)
                                .map(HearingRoundEntity::getRoundNo)
                                .orElse(0)
                        + 1;
        int maxRounds = disputeProperties.maxHearingRounds();
        if (roundNo > maxRounds) {
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
                        now.plus(disputeProperties.hearingRoundWindow()),
                        now,
                        actor.actorId());
        HearingStopReason stopReason =
                roundNo == maxRounds
                        ? HearingStopReason.MAX_ROUNDS
                        : null;
        round.complete(
                command.summaryJson(), stopReason, now, actor.actorId());
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
        courtOrchestrator.afterRoundClosedAfterCommit(
                caseId,
                roundNo,
                stopReason != null,
                traceId(roundNo));
        workflowCoordinator.roundCompletedAfterCommit(caseId, roundNo, false);
        return view(caseId, saved, actor);
    }

    @Transactional
    public HearingRoundView submitParty(
            String caseId,
            SubmitHearingRoundCommand command,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertCaseParty(dispute, actor);
        Instant now = clock.instant();
        HearingRoundEntity round =
                activeOrNextRound(
                        caseId,
                        command.dossierVersion(),
                        now,
                        actor.actorId());
        if (!round.getRoundDeadlineAt().isAfter(now)) {
            return completeRoundAfterTimeout(
                    dispute, round, now, "hearing-round-timeout");
        }
        boolean createdSubmission =
                submissionRepository
                        .findByCaseIdAndRoundNoAndParticipantRole(
                                caseId, round.getRoundNo(), actor.role())
                        .isEmpty();
        if (createdSubmission) {
            submissionRepository.save(
                    HearingRoundPartySubmissionEntity.submit(
                            "HEARING_ROUND_SUBMISSION_" + compactUuid(),
                            caseId,
                            round.getId(),
                            round.getRoundNo(),
                            actor.role(),
                            actor.actorId(),
                            HearingRoundSubmissionSource.PARTY_ACTION,
                            command.statementJson(),
                            now));
            recordRoundEvent(
                    caseId,
                    "HEARING_ROUND_PARTY_SUBMITTED",
                    Map.of(
                            "round_no", round.getRoundNo(),
                            "participant_role", actor.role().name()),
                    "hearing-round-party-submitted:"
                            + round.getRoundNo()
                            + ":"
                            + actor.role().name(),
                    actor.actorId());
        }
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                caseId, round.getRoundNo());
        if (submissions.size() < 2) {
            round.waitForCounterparty(now, actor.actorId());
            return view(caseId, roundRepository.save(round), actor);
        }
        if (round.getClosedAt() == null) {
            HearingStopReason stopReason =
                    round.getRoundNo() == disputeProperties.maxHearingRounds()
                            ? HearingStopReason.MAX_ROUNDS
                            : null;
            round.complete(
                    submittedRoundSummaryV2(submissions),
                    stopReason,
                    now,
                    "hearing-controller");
            roundRepository.save(round);
            recordRoundEvent(
                    caseId,
                    "HEARING_ROUND_COMPLETED",
                    Map.of(
                            "round_no", round.getRoundNo(),
                            "stop_reason",
                            stopReason == null
                                    ? "BOTH_PARTIES_SUBMITTED"
                            : stopReason.name()),
                    "hearing-round-completed:" + round.getRoundNo(),
                    "hearing-controller");
            courtOrchestrator.afterRoundClosedAfterCommit(
                    caseId,
                    round.getRoundNo(),
                    stopReason != null,
                    traceId(round.getRoundNo()));
            HearingRoundEntity responseRound = round;
            if (stopReason == null) {
                responseRound =
                        openNextRound(dispute, round, now, "hearing-controller");
            }
            workflowCoordinator.roundCompletedAfterCommit(
                    caseId, round.getRoundNo(), false);
            return view(caseId, responseRound, actor);
        }
        return view(caseId, round, actor);
    }

    @Transactional
    public int expireDueRounds() {
        Instant now = clock.instant();
        List<HearingRoundEntity> dueRounds =
                roundRepository
                        .findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(
                                List.of(
                                        HearingRoundStatus.OPEN,
                                        HearingRoundStatus.WAITING),
                                now);
        int expired = 0;
        for (HearingRoundEntity candidate : dueRounds) {
            FulfillmentCaseEntity dispute = lockedHearing(candidate.getCaseId());
            HearingRoundEntity round =
                    roundRepository
                            .findById(candidate.getId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "due hearing round disappeared"));
            if (round.getClosedAt() != null
                    || round.getRoundDeadlineAt().isAfter(now)) {
                continue;
            }
            completeRoundAfterTimeout(
                    dispute, round, now, "hearing-round-timeout-scheduler");
            expired++;
        }
        return expired;
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
                                            Math.min(
                                                    next,
                                                    disputeProperties
                                                            .maxHearingRounds()),
                                            dossierVersion,
                                            clock.instant()
                                                    .plus(disputeProperties.hearingRoundWindow()),
                                            clock.instant(),
                                            actorId);
                                });
        round.complete(
                "{\"deadline_expired\":true}",
                HearingStopReason.DEADLINE_EXPIRED,
                clock.instant(),
                actorId);
        return view(caseId, roundRepository.save(round), null);
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
                .map(round -> view(caseId, round, actor))
                .toList();
    }

    private HearingRoundEntity activeOrNextRound(
            String caseId, int dossierVersion, Instant now, String actorId) {
        return roundRepository
                .findTopByCaseIdOrderByRoundNoDesc(caseId)
                .filter(round -> round.getClosedAt() == null)
                .orElseGet(
                        () -> {
                            int nextRound =
                                    roundRepository
                                                    .findTopByCaseIdOrderByRoundNoDesc(caseId)
                                                    .map(HearingRoundEntity::getRoundNo)
                                                    .orElse(0)
                                            + 1;
                            if (nextRound > disputeProperties.maxHearingRounds()) {
                                throw new IllegalStateException(
                                        "hearing round limit reached");
                            }
                            return roundRepository.save(
                                    HearingRoundEntity.open(
                                            "HEARING_ROUND_" + compactUuid(),
                                            caseId,
                                            hearingStateRepository
                                                    .findByCaseId(caseId)
                                                    .map(state -> state.getId())
                                                    .orElse(null),
                                            nextRound,
                                            dossierVersion,
                                            now.plus(disputeProperties.hearingRoundWindow()),
                                            now,
                                            actorId));
                        });
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

    private HearingRoundView completeRoundAfterTimeout(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity round,
            Instant now,
            String actorId) {
        if (round.getClosedAt() != null) {
            return view(dispute.getId(), round, null);
        }
        List<HearingRoundPartySubmissionEntity> existing =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                dispute.getId(), round.getRoundNo());
        List<String> missingRoles =
                List.of(ActorRole.USER, ActorRole.MERCHANT).stream()
                        .filter(
                                role ->
                                        existing.stream()
                                                .noneMatch(
                                                        submission ->
                                                                submission
                                                                                .getParticipantRole()
                                                                        == role))
                        .map(Enum::name)
                        .toList();
        if (missingRoles.contains(ActorRole.USER.name())) {
            submissionRepository.save(
                    autoTimeoutSubmission(
                            dispute,
                            round,
                            ActorRole.USER,
                            dispute.getUserId(),
                            now));
        }
        if (missingRoles.contains(ActorRole.MERCHANT.name())) {
            submissionRepository.save(
                    autoTimeoutSubmission(
                            dispute,
                            round,
                            ActorRole.MERCHANT,
                            dispute.getMerchantId(),
                            now));
        }
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                dispute.getId(), round.getRoundNo());
        HearingStopReason stopReason =
                round.getRoundNo() == disputeProperties.maxHearingRounds()
                        ? HearingStopReason.MAX_ROUNDS
                        : null;
        round.complete(
                timeoutRoundSummaryV2(submissions),
                stopReason,
                now,
                actorId);
        HearingRoundEntity saved = roundRepository.save(round);
        HearingRoundEntity responseRound = saved;
        recordRoundEvent(
                dispute.getId(),
                "HEARING_ROUND_TIMEOUT_AUTO_SUBMITTED",
                Map.of(
                        "round_no", round.getRoundNo(),
                        "missing_roles", missingRoles),
                "hearing-round-timeout-auto-submitted:" + round.getRoundNo(),
                actorId);
        recordRoundEvent(
                dispute.getId(),
                "HEARING_ROUND_COMPLETED",
                Map.of(
                        "round_no", round.getRoundNo(),
                        "stop_reason",
                        stopReason == null
                                ? "ROUND_DEADLINE_EXPIRED"
                                : stopReason.name()),
                "hearing-round-completed:" + round.getRoundNo(),
                actorId);
        courtOrchestrator.afterRoundClosedAfterCommit(
                dispute.getId(),
                round.getRoundNo(),
                stopReason != null,
                traceId(round.getRoundNo()));
        if (stopReason == null) {
            responseRound = openNextRound(dispute, saved, now, actorId);
        }
        workflowCoordinator.roundCompletedAfterCommit(
                dispute.getId(), round.getRoundNo(), false);
        return view(dispute.getId(), responseRound, null);
    }

    private HearingRoundEntity openNextRound(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity completedRound,
            Instant now,
            String actorId) {
        int nextRoundNo = completedRound.getRoundNo() + 1;
        if (nextRoundNo > disputeProperties.maxHearingRounds()) {
            return completedRound;
        }
        return roundRepository
                .findByCaseIdAndRoundNo(dispute.getId(), nextRoundNo)
                .orElseGet(
                        () -> {
                            HearingRoundEntity nextRound =
                                    roundRepository.save(
                                            HearingRoundEntity.open(
                                                    "HEARING_ROUND_" + compactUuid(),
                                                    dispute.getId(),
                                                    hearingStateRepository
                                                            .findByCaseId(dispute.getId())
                                                            .map(state -> state.getId())
                                                            .orElse(null),
                                                    nextRoundNo,
                                                    completedRound.getDossierVersion(),
                                                    now.plus(
                                                            disputeProperties
                                                                    .hearingRoundWindow()),
                                                    now,
                                                    actorId));
                            recordRoundEvent(
                                    dispute.getId(),
                                    "HEARING_ROUND_OPENED",
                                    Map.of(
                                            "round_no",
                                            nextRoundNo,
                                            "previous_round_no",
                                            completedRound.getRoundNo(),
                                            "round_deadline_at",
                                            nextRound.getRoundDeadlineAt().toString()),
                                    "hearing-round-opened:" + nextRoundNo,
                                    actorId);
                            return nextRound;
                        });
    }

    private static HearingRoundPartySubmissionEntity autoTimeoutSubmission(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity round,
            ActorRole role,
            String participantId,
            Instant now) {
        return HearingRoundPartySubmissionEntity.submit(
                "HEARING_ROUND_SUBMISSION_" + compactUuid(),
                dispute.getId(),
                round.getId(),
                round.getRoundNo(),
                role,
                participantId,
                HearingRoundSubmissionSource.AUTO_TIMEOUT,
                autoTimeoutJsonV2(round, role),
                now);
    }

    private static void assertController(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.SYSTEM
                && actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN) {
            throw new ForbiddenException("hearing round completion requires trusted actor");
        }
    }

    private static void assertCaseParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    default -> false;
                };
        if (!allowed) {
            throw new ForbiddenException("only case parties may submit hearing round");
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

    private HearingRoundView view(
            String caseId, HearingRoundEntity round, AuthenticatedActor actor) {
        List<ActorRole> submittedRoles =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                caseId, round.getRoundNo())
                        .stream()
                        .map(HearingRoundPartySubmissionEntity::getParticipantRole)
                        .distinct()
                        .toList();
        return new HearingRoundView(
                round.getId(),
                caseId,
                round.getRoundNo(),
                round.getRoundStatus(),
                round.getDossierVersion(),
                round.getStopReason(),
                round.getSummaryJson(),
                round.getOpenedAt(),
                round.getRoundDeadlineAt(),
                submittedRoles,
                actor != null && submittedRoles.contains(actor.role()),
                round.getClosedAt());
    }

    private void recordRoundEvent(
            String caseId,
            String eventType,
            Map<String, Object> payload,
            String eventKey,
            String actorId) {
        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        eventService.recordLifecycleEvent(
                caseId,
                hearingRoom.getId(),
                eventType,
                payload,
                eventKey,
                actorId);
    }

    private static String submittedRoundSummaryV2(
            List<HearingRoundPartySubmissionEntity> submissions) {
        String userJson = submissionJson(submissions, ActorRole.USER);
        String merchantJson = submissionJson(submissions, ActorRole.MERCHANT);
        return """
                {"trigger":"BOTH_PARTIES_SUBMITTED","clerk":"双方本轮陈述已提交并封存。","judge":"本轮材料已入卷；如未到第三轮，系统会开放下一轮陈述，第三轮结束后由 AI 法官生成非最终裁决方案草案。","user_submission":%s,"merchant_submission":%s}
                """
                .formatted(userJson, merchantJson)
                .strip();
    }

    private static String timeoutRoundSummaryV2(
            List<HearingRoundPartySubmissionEntity> submissions) {
        String userJson = submissionJson(submissions, ActorRole.USER);
        String merchantJson = submissionJson(submissions, ActorRole.MERCHANT);
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","clerk":"本轮提交时效已届满，系统已自动封存双方当前材料。","judge":"本轮按时效自动入卷；如未到第三轮，系统会开放下一轮陈述，第三轮结束后由 AI 法官生成非最终裁决方案草案。","user_submission":%s,"merchant_submission":%s}
                """
                .formatted(userJson, merchantJson)
                .strip();
    }

    private static String autoTimeoutJsonV2(HearingRoundEntity round, ActorRole role) {
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","source":"AUTO_TIMEOUT","participant_role":"%s","round_no":%d,"statement":"本轮时效届满前未主动提交，系统按当前已留存内容自动提交。"}
                """
                .formatted(role.name(), round.getRoundNo())
                .strip();
    }

    private static String submittedRoundSummary(
            List<HearingRoundPartySubmissionEntity> submissions) {
        String userJson = submissionJson(submissions, ActorRole.USER);
        String merchantJson = submissionJson(submissions, ActorRole.MERCHANT);
        return """
                {"trigger":"BOTH_PARTIES_SUBMITTED","clerk":"双方本轮陈述已提交并封存。","judge":"本轮材料已入卷；若未到第三轮，系统会开放下一轮陈述，第三轮结束后再由 AI 法官生成最终裁决方案草案。","user_submission":%s,"merchant_submission":%s}
                """
                .formatted(userJson, merchantJson)
                .strip();
    }

    private static String timeoutRoundSummary(
            List<HearingRoundPartySubmissionEntity> submissions) {
        String userJson = submissionJson(submissions, ActorRole.USER);
        String merchantJson = submissionJson(submissions, ActorRole.MERCHANT);
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","clerk":"本轮提交时效已届满，系统已自动封存双方当前材料。","judge":"本轮按时效自动入卷；若未到第三轮，系统会开放下一轮陈述，第三轮结束后再由 AI 法官生成最终裁决方案草案。","user_submission":%s,"merchant_submission":%s}
                """
                .formatted(userJson, merchantJson)
                .strip();
    }

    private static String autoTimeoutJson(HearingRoundEntity round, ActorRole role) {
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","source":"AUTO_TIMEOUT","participant_role":"%s","round_no":%d,"statement":"本轮时效届满前未主动提交，系统按当前已留存内容自动提交。"}
                """
                .formatted(role.name(), round.getRoundNo())
                .strip();
    }

    private static String submissionJson(
            List<HearingRoundPartySubmissionEntity> submissions, ActorRole role) {
        return submissions.stream()
                .filter(item -> item.getParticipantRole() == role)
                .findFirst()
                .map(HearingRoundPartySubmissionEntity::getSubmissionJson)
                .orElse("{}");
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String traceId(int roundNo) {
        return "TRACE_HEARING_ROUND_" + roundNo;
    }
}
