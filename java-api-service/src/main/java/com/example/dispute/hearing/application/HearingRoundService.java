package com.example.dispute.hearing.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.evidence.application.EvidenceDossierRevisionService;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HearingRoundService {

    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository hearingStateRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseEventService eventService;
    private final HearingWorkflowCoordinator workflowCoordinator;
    private final HearingCourtOrchestrator courtOrchestrator;
    private final HearingFinalDraftService finalDraftService;
    private final HearingOutcomeOrchestrationService outcomeOrchestrationService;
    private final EvidenceDossierRevisionService evidenceDossierRevisionService;
    private final DisputeProperties disputeProperties;
    private final Clock clock;

    public HearingRoundService(
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository hearingStateRepository,
            AdjudicationDraftRepository draftRepository,
            ReviewTaskRepository reviewTaskRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            CaseRoomRepository roomRepository,
            CaseEventService eventService,
            HearingWorkflowCoordinator workflowCoordinator,
            HearingCourtOrchestrator courtOrchestrator,
            HearingFinalDraftService finalDraftService,
            HearingOutcomeOrchestrationService outcomeOrchestrationService,
            EvidenceDossierRevisionService evidenceDossierRevisionService,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.draftRepository = draftRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.roomRepository = roomRepository;
        this.eventService = eventService;
        this.workflowCoordinator = workflowCoordinator;
        this.courtOrchestrator = courtOrchestrator;
        this.finalDraftService = finalDraftService;
        this.outcomeOrchestrationService = outcomeOrchestrationService;
        this.evidenceDossierRevisionService = evidenceDossierRevisionService;
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
        reviseEvidenceDossierAfterRoundIfNeeded(caseId, roundNo, actor.actorId());
        dispatchRoundClosedAfterCommit(caseId, roundNo, stopReason != null);
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
        return advanceAfterPartySubmissionIfReady(dispute, round, now, actor);
    }

    @Transactional
    public int currentOpenRoundNoForPartyMessage(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertCaseParty(dispute, actor);
        return currentWritableRound(caseId).getRoundNo();
    }

    @Transactional
    public HearingRoundView recordPartyMessageSubmission(
            String caseId,
            int roundNo,
            String messageId,
            String statement,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertCaseParty(dispute, actor);
        HearingRoundEntity round =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, roundNo)
                        .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));
        assertWritableRound(round);
        if (submissionRepository
                .findByCaseIdAndRoundNoAndParticipantRole(caseId, roundNo, actor.role())
                .isEmpty()) {
            submissionRepository.save(
                    HearingRoundPartySubmissionEntity.submit(
                            "HEARING_ROUND_SUBMISSION_" + compactUuid(),
                            caseId,
                            round.getId(),
                            round.getRoundNo(),
                            actor.role(),
                            actor.actorId(),
                            HearingRoundSubmissionSource.PARTY_ACTION,
                            roomMessageSubmissionJson(round, actor.role(), messageId, statement),
                            clock.instant()));
            recordRoundEvent(
                    caseId,
                    "HEARING_ROUND_PARTY_MESSAGE_RECORDED",
                    Map.of(
                            "round_no", round.getRoundNo(),
                            "participant_role", actor.role().name(),
                            "message_id", messageId),
                    "hearing-round-party-message-recorded:"
                            + round.getRoundNo()
                            + ":"
                            + actor.role().name(),
                    actor.actorId());
        }
        return advanceAfterPartySubmissionIfReady(dispute, round, clock.instant(), actor);
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
            FulfillmentCaseEntity dispute = lockedHearingForExpiry(candidate.getCaseId());
            if (dispute == null) {
                continue;
            }
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

    @Transactional(readOnly = true)
    public HearingStatusView status(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        var latestRound = roundRepository.findTopByCaseIdOrderByRoundNoDesc(caseId);
        var latestDraft = draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId);
        var latestReviewTask = reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId);
        String latestDraftId = latestDraft.map(item -> item.getId()).orElse(null);
        String reviewTaskId = latestReviewTask.map(item -> item.getId()).orElse(null);
        boolean reviewGateReady = reviewTaskId != null;

        if (latestRound.isEmpty()) {
            return new HearingStatusView(
                    caseId,
                    "BOOTSTRAPPING",
                    "等待开庭装卷",
                    "系统正在装订接待室案情卷宗和证据室证据卷宗，稍后会开启第 1 轮庭审。",
                    false,
                    reviewGateReady,
                    latestDraftId,
                    reviewTaskId,
                    null,
                    null,
                    null,
                    null,
                    false);
        }

        HearingRoundEntity round = latestRound.get();
        boolean closed =
                round.getClosedAt() != null
                        || round.getRoundStatus() == HearingRoundStatus.COMPLETED
                        || round.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED;
        boolean finalRoundSealed = closed && round.getRoundNo() >= disputeProperties.maxHearingRounds();
        String finalDraftId =
                finalRoundSealed
                        ? draftRepository
                                .findByCaseIdAndDraftVersion(caseId, round.getRoundNo() + 1)
                                .map(item -> item.getId())
                                .orElse(null)
                        : latestDraftId;
        List<ActorRole> submittedRoles =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(caseId, round.getRoundNo())
                        .stream()
                        .map(HearingRoundPartySubmissionEntity::getParticipantRole)
                        .distinct()
                        .toList();
        boolean bothPartiesSubmitted =
                submittedRoles.contains(ActorRole.USER) && submittedRoles.contains(ActorRole.MERCHANT);

        if (reviewGateReady) {
            return statusView(
                    caseId,
                    round,
                    "REVIEW_GATE_READY",
                    "裁决草案已生成",
                    "裁决草案已生成，可进入结果页查看草案说明。",
                    true,
                    true,
                    finalDraftId,
                    reviewTaskId,
                    finalRoundSealed);
        }
        if (finalRoundSealed && finalDraftId != null) {
            return statusView(
                    caseId,
                    round,
                    "DRAFT_READY",
                    "裁决草案已生成",
                    "AI 法官已生成裁决草案，点击庭审完成后进入结果页查看草案说明。",
                    true,
                    false,
                    finalDraftId,
                    null,
                    true);
        }
        if (finalRoundSealed) {
            return statusView(
                    caseId,
                    round,
                    "JUDGE_DRAFTING",
                    "等待裁决草案",
                    "第 3 轮方案确认已封存，双方对法官拟处理方向的确认或说明异议已入卷，等待 AI 法官生成裁决草案。",
                    false,
                    false,
                    null,
                    null,
                    true);
        }
        if (closed) {
            return statusView(
                    caseId,
                    round,
                    "JUDGE_PROCESSING",
                    "法官处理中",
                    "本轮陈述已封存，等待 AI 法官生成下一轮问题。",
                    false,
                    false,
                    latestDraftId,
                    null,
                    false);
        }
        if (bothPartiesSubmitted) {
            return statusView(
                    caseId,
                    round,
                    "WAITING_JUDGE",
                    "等待法官处理",
                    "双方已完成本轮陈述，等待 AI 法官收束本轮并推进庭审。",
                    false,
                    false,
                    latestDraftId,
                    null,
                    false);
        }
        return statusView(
                caseId,
                round,
                "ROUND_OPEN",
                "本轮陈述中",
                "请双方围绕法官问题完成本轮陈述；双方提交或倒计时结束后，本轮会自动封存。",
                false,
                false,
                latestDraftId,
                null,
                false);
    }

    @Transactional
    public HearingStatusView completeHearing(String caseId, AuthenticatedActor actor) {
        HearingStatusView current = status(caseId, actor);
        if ("DRAFT_READY".equals(current.hearingPhase())
                && current.finalRoundSealed()
                && current.currentRoundNo() != null) {
            finalDraftService.adoptExistingDraftForFinalSealedRound(
                    caseId,
                    current.currentRoundNo(),
                    disputeProperties.maxHearingRounds(),
                    actor.actorId());
            outcomeOrchestrationService.orchestrate(caseId, actor.actorId());
            HearingStatusView updated = status(caseId, actor);
            recordHearingPhaseChanged(caseId, updated, actor.actorId());
            return updated;
        }
        recordHearingPhaseChanged(caseId, current, actor.actorId());
        return current;
    }

    private void recordHearingPhaseChanged(
            String caseId, HearingStatusView status, String actorId) {
        if (!"DRAFT_READY".equals(status.hearingPhase())
                && !"REVIEW_GATE_READY".equals(status.hearingPhase())) {
            return;
        }
        if (status.latestDraftId() == null || status.latestDraftId().isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hearing_phase", status.hearingPhase());
        payload.put("phase_label", status.phaseLabel());
        payload.put("next_step_hint", status.nextStepHint());
        payload.put("can_complete_hearing", status.canCompleteHearing());
        payload.put("review_gate_ready", status.reviewGateReady());
        payload.put("latest_draft_id", status.latestDraftId());
        payload.put("current_round_no", status.currentRoundNo());
        payload.put("final_round_sealed", status.finalRoundSealed());
        if (status.reviewTaskId() != null && !status.reviewTaskId().isBlank()) {
            payload.put("review_task_id", status.reviewTaskId());
        }
        recordRoundEvent(
                caseId,
                "HEARING_PHASE_CHANGED",
                payload,
                "hearing-phase-changed:" + status.hearingPhase() + ":" + status.latestDraftId(),
                actorId);
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

    private static HearingStatusView statusView(
            String caseId,
            HearingRoundEntity round,
            String hearingPhase,
            String phaseLabel,
            String nextStepHint,
            boolean canCompleteHearing,
            boolean reviewGateReady,
            String latestDraftId,
            String reviewTaskId,
            boolean finalRoundSealed) {
        return new HearingStatusView(
                caseId,
                hearingPhase,
                phaseLabel,
                nextStepHint,
                canCompleteHearing,
                reviewGateReady,
                latestDraftId,
                reviewTaskId,
                round.getRoundNo(),
                roundStageFor(round.getRoundNo()),
                round.getRoundStatus().name(),
                round.getRoundDeadlineAt(),
                finalRoundSealed);
    }

    private static String roundStageFor(int roundNo) {
        return switch (roundNo) {
            case 1 -> "FACT_STATEMENT";
            case 2 -> "EVIDENCE_EXPLANATION";
            case 3 -> "REMEDY_CONFIRMATION";
            default -> "ROUND_" + roundNo;
        };
    }

    private HearingRoundEntity currentWritableRound(String caseId) {
        HearingRoundEntity round =
                roundRepository
                        .findTopByCaseIdOrderByRoundNoDesc(caseId)
                        .orElseThrow(() -> new IllegalStateException("hearing round not open"));
        assertWritableRound(round);
        return round;
    }

    private void assertWritableRound(HearingRoundEntity round) {
        if (round.getClosedAt() != null
                || round.getRoundStatus() == HearingRoundStatus.COMPLETED
                || round.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED) {
            throw new IllegalStateException("hearing round is already sealed");
        }
        if (!round.getRoundDeadlineAt().isAfter(clock.instant())) {
            throw new IllegalStateException("hearing round deadline has expired");
        }
    }

    private FulfillmentCaseEntity lockedHearing(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        if (!hearingRoundAvailable(dispute)) {
            throw new IllegalStateException(
                    "hearing round is unavailable from " + dispute.getCaseStatus());
        }
        return dispute;
    }

    private FulfillmentCaseEntity lockedHearingForExpiry(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return hearingRoundAvailable(dispute) ? dispute : null;
    }

    private static boolean hearingRoundAvailable(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.HEARING_OPEN
                || dispute.getCaseStatus() == CaseStatus.HEARING;
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
        reviseEvidenceDossierAfterRoundIfNeeded(dispute.getId(), round.getRoundNo(), "evidence-clerk");
        dispatchRoundClosedAfterCommit(
                dispute.getId(), round.getRoundNo(), stopReason != null);
        if (stopReason == null) {
            responseRound = openNextRound(dispute, saved, now, actorId);
        }
        return view(dispute.getId(), responseRound, null);
    }

    private void reviseEvidenceDossierAfterRoundIfNeeded(
            String caseId, int roundNo, String actorId) {
        if (roundNo != EvidenceDossierRevisionService.EVIDENCE_EXPLANATION_ROUND) {
            return;
        }
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        caseId, roundNo);
        evidenceDossierRevisionService.reviseAfterRoundIfNeeded(
                caseId,
                roundNo,
                submissions,
                actorId);
    }

    private HearingRoundView advanceAfterPartySubmissionIfReady(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity round,
            Instant now,
            AuthenticatedActor actor) {
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository
                        .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                dispute.getId(), round.getRoundNo());
        if (submissions.size() < 2) {
            round.waitForCounterparty(now, actor.actorId());
            return view(dispute.getId(), roundRepository.save(round), actor);
        }
        if (round.getClosedAt() != null) {
            return view(dispute.getId(), round, actor);
        }
        HearingStopReason stopReason =
                round.getRoundNo() == disputeProperties.maxHearingRounds()
                        ? HearingStopReason.MAX_ROUNDS
                        : null;
        round.complete(
                submittedRoundSummaryV2(submissions),
                stopReason,
                now,
                "hearing-controller");
        HearingRoundEntity saved = roundRepository.save(round);
        recordRoundEvent(
                dispute.getId(),
                "HEARING_ROUND_COMPLETED",
                Map.of(
                        "round_no", round.getRoundNo(),
                        "stop_reason",
                        stopReason == null ? "BOTH_PARTIES_SUBMITTED" : stopReason.name()),
                "hearing-round-completed:" + round.getRoundNo(),
                "hearing-controller");
        reviseEvidenceDossierAfterRoundIfNeeded(
                dispute.getId(), round.getRoundNo(), "evidence-clerk");
        dispatchRoundClosedAfterCommit(
                dispute.getId(), round.getRoundNo(), stopReason != null);
        HearingRoundEntity responseRound = saved;
        if (stopReason == null) {
            responseRound = openNextRound(dispute, saved, now, "hearing-controller");
        }
        return view(dispute.getId(), responseRound, actor);
    }

    private void dispatchRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound) {
        courtOrchestrator.afterRoundClosedAfterCommit(
                caseId,
                roundNo,
                finalRound,
                traceId(roundNo),
                () -> workflowCoordinator.roundCompletedAfterCommit(caseId, roundNo, false));
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

    private static String roomMessageSubmissionJson(
            HearingRoundEntity round, ActorRole role, String messageId, String statement) {
        return """
                {"source":"ROOM_MESSAGE","participant_role":"%s","round_no":%d,"message_id":"%s","statement":"%s"}
                """
                .formatted(
                        role.name(),
                        round.getRoundNo(),
                        jsonEscape(messageId),
                        jsonEscape(statement))
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

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String traceId(int roundNo) {
        return "TRACE_HEARING_ROUND_" + roundNo;
    }
}
