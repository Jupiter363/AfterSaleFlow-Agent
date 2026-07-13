/*
 * 所属模块：共享小法庭。
 * 文件职责：编排庭审轮次规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「ensureInitialRoundOpen」、「completeNext」、「submitParty」、「currentOpenRoundNoForPartyMessage」、「recordPartyMessageSubmission」、「expireDueRounds」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
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

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingRoundService」。
// 类型职责：编排庭审轮次规则、权限校验与事实读写；本类型显式提供 「HearingRoundService」、「ensureInitialRoundOpen」、「completeNext」、「submitParty」、「currentOpenRoundNoForPartyMessage」、「recordPartyMessageSubmission」。
// 协作关系：主要由 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」、「FinalWorkflowActivitiesAdapter.complete」、「HearingCollaborationController.complete」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.HearingRoundService(FulfillmentCaseRepository,HearingStateRepository,AdjudicationDraftRepository,ReviewTaskRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,CaseRoomRepository,CaseEventService,HearingWorkflowCoordinator,HearingCourtOrchestrator,HearingFinalDraftService,HearingOutcomeOrchestrationService,EvidenceDossierRevisionService,DisputeProperties,Clock)」。
    // 具体功能：「HearingRoundService.HearingRoundService(FulfillmentCaseRepository,HearingStateRepository,AdjudicationDraftRepository,ReviewTaskRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,CaseRoomRepository,CaseEventService,HearingWorkflowCoordinator,HearingCourtOrchestrator,HearingFinalDraftService,HearingOutcomeOrchestrationService,EvidenceDossierRevisionService,DisputeProperties,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「hearingStateRepository」(HearingStateRepository)、「draftRepository」(AdjudicationDraftRepository)、「reviewTaskRepository」(ReviewTaskRepository)、「roundRepository」(HearingRoundRepository)、「submissionRepository」(HearingRoundPartySubmissionRepository)、「roomRepository」(CaseRoomRepository)、「eventService」(CaseEventService)、「workflowCoordinator」(HearingWorkflowCoordinator)、「courtOrchestrator」(HearingCourtOrchestrator)、「finalDraftService」(HearingFinalDraftService)、「outcomeOrchestrationService」(HearingOutcomeOrchestrationService)、「evidenceDossierRevisionService」(EvidenceDossierRevisionService)、「disputeProperties」(DisputeProperties)、「clock」(Clock) 并保存为「HearingRoundService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingRoundService.HearingRoundService(FulfillmentCaseRepository,HearingStateRepository,AdjudicationDraftRepository,ReviewTaskRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,CaseRoomRepository,CaseEventService,HearingWorkflowCoordinator,HearingCourtOrchestrator,HearingFinalDraftService,HearingOutcomeOrchestrationService,EvidenceDossierRevisionService,DisputeProperties,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「HearingRoundService.HearingRoundService(FulfillmentCaseRepository,HearingStateRepository,AdjudicationDraftRepository,ReviewTaskRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,CaseRoomRepository,CaseEventService,HearingWorkflowCoordinator,HearingCourtOrchestrator,HearingFinalDraftService,HearingOutcomeOrchestrationService,EvidenceDossierRevisionService,DisputeProperties,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundService.HearingRoundService(FulfillmentCaseRepository,HearingStateRepository,AdjudicationDraftRepository,ReviewTaskRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,CaseRoomRepository,CaseEventService,HearingWorkflowCoordinator,HearingCourtOrchestrator,HearingFinalDraftService,HearingOutcomeOrchestrationService,EvidenceDossierRevisionService,DisputeProperties,Clock)」负责主链路中的“庭审轮次服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.ensureInitialRoundOpen(String,int,String)」。
    // 具体功能：「HearingRoundService.ensureInitialRoundOpen(String,int,String)」：在案件已进入庭审且没有活动轮次时创建第 1 轮、绑定冻结卷宗版本和服务端截止时间；已有轮次则幂等返回，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.ensureInitialRoundOpen(String,int,String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」、「HearingCourtBootstrapService.bootstrap」、「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」。
    // 下游影响：「HearingRoundService.ensureInitialRoundOpen(String,int,String)」向下依次触达 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「roundRepository.save」、「hearingStateRepository.findByCaseId」、「HearingRoundEntity.open」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.ensureInitialRoundOpen(String,int,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.completeNext(String,CompleteHearingRoundCommand,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.completeNext(String,CompleteHearingRoundCommand,AuthenticatedActor)」：由庭审控制器封存当前轮：校验轮次可写、补齐双方提交状态、更新 HearingState，根据 finalRound/stopReason 决定开放下一轮还是触发终局法官回合，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.completeNext(String,CompleteHearingRoundCommand,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.completeRound」、「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow」、「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」、「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」。
    // 下游影响：「HearingRoundService.completeNext(String,CompleteHearingRoundCommand,AuthenticatedActor)」向下依次触达 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「hearingStateRepository.findByCaseId」、「roundRepository.save」、「roomRepository.findByCaseIdAndRoomType」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.completeNext(String,CompleteHearingRoundCommand,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.submitParty(String,SubmitHearingRoundCommand,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.submitParty(String,SubmitHearingRoundCommand,AuthenticatedActor)」：提交当事方：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「submissionRepository.findByCaseIdAndRoundNoAndParticipantRole」、「submissionRepository.save」、「HearingRoundPartySubmissionEntity.submit」、「clock.instant」；处理的关键状态/协议值包括 「hearing-round-timeout」、「HEARING_ROUND_SUBMISSION_」、「HEARING_ROUND_PARTY_SUBMITTED」、「round_no」，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.submitParty(String,SubmitHearingRoundCommand,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.submitCurrentRound」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」、「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge」、「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound」。
    // 下游影响：「HearingRoundService.submitParty(String,SubmitHearingRoundCommand,AuthenticatedActor)」向下依次触达 「submissionRepository.findByCaseIdAndRoundNoAndParticipantRole」、「submissionRepository.save」、「HearingRoundPartySubmissionEntity.submit」、「clock.instant」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.submitParty(String,SubmitHearingRoundCommand,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.currentOpenRoundNoForPartyMessage(String,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.currentOpenRoundNoForPartyMessage(String,AuthenticatedActor)」：构建currentOpen轮次编号面向当事方消息：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「lockedHearing」、「assertCaseParty」、「currentWritableRound」、「currentWritableRound(caseId).getRoundNo」，最终返回「int」。
    // 上游调用：「HearingRoundService.currentOpenRoundNoForPartyMessage(String,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.hearingRoundForPartyMessage」、「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」、「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」。
    // 下游影响：「HearingRoundService.currentOpenRoundNoForPartyMessage(String,AuthenticatedActor)」向下依次触达 「lockedHearing」、「assertCaseParty」、「currentWritableRound」、「currentWritableRound(caseId).getRoundNo」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.currentOpenRoundNoForPartyMessage(String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public int currentOpenRoundNoForPartyMessage(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedHearing(caseId);
        assertCaseParty(dispute, actor);
        return currentWritableRound(caseId).getRoundNo();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.recordPartyMessageSubmission(String,int,String,String,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.recordPartyMessageSubmission(String,int,String,String,AuthenticatedActor)」：记录当事方消息提交：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「roundRepository.findByCaseIdAndRoundNo」、「submissionRepository.findByCaseIdAndRoundNoAndParticipantRole」、「submissionRepository.save」、「HearingRoundPartySubmissionEntity.submit」；处理的关键状态/协议值包括 「HEARING_ROUND_SUBMISSION_」、「HEARING_ROUND_PARTY_MESSAGE_RECORDED」、「round_no」、「participant_role」，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.recordPartyMessageSubmission(String,int,String,String,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.create」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」、「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound」。
    // 下游影响：「HearingRoundService.recordPartyMessageSubmission(String,int,String,String,AuthenticatedActor)」向下依次触达 「roundRepository.findByCaseIdAndRoundNo」、「submissionRepository.findByCaseIdAndRoundNoAndParticipantRole」、「submissionRepository.save」、「HearingRoundPartySubmissionEntity.submit」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.recordPartyMessageSubmission(String,int,String,String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.expireDueRounds()」。
    // 具体功能：「HearingRoundService.expireDueRounds()」：定时扫描截止时间已到的 OPEN/WAITING_PARTIES 轮次，逐案重新加锁并调用超时收敛；单个案件失败只记录日志，不阻断其他案件恢复，最终返回「int」。
    // 上游调用：「HearingRoundService.expireDueRounds()」的上游调用点包括 「HearingRoundDeadlineScheduler.expireDueRounds」、「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」、「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase」。
    // 下游影响：「HearingRoundService.expireDueRounds()」向下依次触达 「findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc」、「roundRepository.findById」、「clock.instant」、「candidate.getCaseId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.expireDueRounds()」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.expire(String,int,String)」。
    // 具体功能：「HearingRoundService.expire(String,int,String)」：以 Temporal 或恢复任务提供的 roundNo 幂等处理截止：为缺席角色生成 AUTO_TIMEOUT 提交，封存当前轮并在未达上限时开放下一轮，否则进入最终草案，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.expire(String,int,String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「HearingRoundService.expire(String,int,String)」向下依次触达 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「hearingStateRepository.findByCaseId」、「roundRepository.save」、「HearingRoundEntity.open」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.expire(String,int,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.list(String,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.list(String,AuthenticatedActor)」：列出列表：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「assertCanAccess」、「view」，最终返回「List<HearingRoundView>」。
    // 上游调用：「HearingRoundService.list(String,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCollaborationController.rounds」、「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」、「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」。
    // 下游影响：「HearingRoundService.list(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「assertCanAccess」、「view」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.list(String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.status(String,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.status(String,AuthenticatedActor)」：聚合最新轮次、HearingState、最终草案和 ReviewTask，计算 BOOTSTRAPPING、ROUND_ACTIVE、DRAFT_READY 或 REVIEW_GATE_READY 页面状态，最终返回「HearingStatusView」。
    // 上游调用：「HearingRoundService.status(String,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingRoundService.completeHearing」、「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「HearingRoundService.status(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.status(String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.completeHearing(String,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.completeHearing(String,AuthenticatedActor)」：确认最终轮已封存，必要时采用已有草案，再调用终局编排创建补救方案与审核任务；返回状态只反映已落库事实，最终返回「HearingStatusView」。
    // 上游调用：「HearingRoundService.completeHearing(String,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.complete」、「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus」、「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」、「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce」。
    // 下游影响：「HearingRoundService.completeHearing(String,AuthenticatedActor)」向下依次触达 「finalDraftService.adoptExistingDraftForFinalSealedRound」、「outcomeOrchestrationService.orchestrate」、「current.hearingPhase」、「current.finalRoundSealed」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingRoundService.completeHearing(String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.recordHearingPhaseChanged(String,HearingStatusView,String)」。
    // 具体功能：「HearingRoundService.recordHearingPhaseChanged(String,HearingStatusView,String)」：记录庭审阶段Changed；实际协作者为 「status.hearingPhase」、「status.latestDraftId」、「status.phaseLabel」、「status.nextStepHint」；处理的关键状态/协议值包括 「DRAFT_READY」、「REVIEW_GATE_READY」、「hearing_phase」、「phase_label」，最终返回「void」。
    // 上游调用：「HearingRoundService.recordHearingPhaseChanged(String,HearingStatusView,String)」的上游调用点包括 「HearingRoundService.completeHearing」。
    // 下游影响：「HearingRoundService.recordHearingPhaseChanged(String,HearingStatusView,String)」向下依次触达 「status.hearingPhase」、「status.latestDraftId」、「status.phaseLabel」、「status.nextStepHint」。
    // 系统意义：「HearingRoundService.recordHearingPhaseChanged(String,HearingStatusView,String)」负责主链路中的“庭审阶段Changed”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.activeOrNextRound(String,int,Instant,String)」。
    // 具体功能：「HearingRoundService.activeOrNextRound(String,int,Instant,String)」：查询活动状态或者下一轮次：先把新状态写入 PostgreSQL 事实表；实际协作者为 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「roundRepository.save」、「hearingStateRepository.findByCaseId」、「HearingRoundEntity.open」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「HEARING_ROUND_」，最终返回「HearingRoundEntity」。
    // 上游调用：「HearingRoundService.activeOrNextRound(String,int,Instant,String)」的上游调用点包括 「HearingRoundService.submitParty」。
    // 下游影响：「HearingRoundService.activeOrNextRound(String,int,Instant,String)」向下依次触达 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「roundRepository.save」、「hearingStateRepository.findByCaseId」、「HearingRoundEntity.open」；计算结果以「HearingRoundEntity」交给调用方。
    // 系统意义：「HearingRoundService.activeOrNextRound(String,int,Instant,String)」负责主链路中的“或者下一轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.statusView(String,HearingRoundEntity,String,String,String,boolean,boolean,String,String,boolean)」。
    // 具体功能：「HearingRoundService.statusView(String,HearingRoundEntity,String,String,String,boolean,boolean,String,String,boolean)」：构建状态视图；实际协作者为 「round.getRoundNo」、「round.getRoundStatus」、「round.getRoundDeadlineAt」、「roundStageFor」，最终返回「HearingStatusView」。
    // 上游调用：「HearingRoundService.statusView(String,HearingRoundEntity,String,String,String,boolean,boolean,String,String,boolean)」的上游调用点包括 「HearingRoundService.status」。
    // 下游影响：「HearingRoundService.statusView(String,HearingRoundEntity,String,String,String,boolean,boolean,String,String,boolean)」向下依次触达 「round.getRoundNo」、「round.getRoundStatus」、「round.getRoundDeadlineAt」、「roundStageFor」；计算结果以「HearingStatusView」交给调用方。
    // 系统意义：「HearingRoundService.statusView(String,HearingRoundEntity,String,String,String,boolean,boolean,String,String,boolean)」负责主链路中的“状态视图”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.roundStageFor(int)」。
    // 具体功能：「HearingRoundService.roundStageFor(int)」：构建轮次Stage面向；处理的关键状态/协议值包括 「FACT_STATEMENT」、「EVIDENCE_EXPLANATION」、「REMEDY_CONFIRMATION」、「ROUND_」，最终返回「String」。
    // 上游调用：「HearingRoundService.roundStageFor(int)」的上游调用点包括 「HearingRoundService.statusView」。
    // 下游影响：「HearingRoundService.roundStageFor(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.roundStageFor(int)」负责主链路中的“轮次Stage面向”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String roundStageFor(int roundNo) {
        return switch (roundNo) {
            case 1 -> "FACT_STATEMENT";
            case 2 -> "EVIDENCE_EXPLANATION";
            case 3 -> "REMEDY_CONFIRMATION";
            default -> "ROUND_" + roundNo;
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.currentWritableRound(String)」。
    // 具体功能：「HearingRoundService.currentWritableRound(String)」：构建currentWritable轮次：先把 Optional 空值转换为明确业务异常；实际协作者为 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「assertWritableRound」，最终返回「HearingRoundEntity」。
    // 上游调用：「HearingRoundService.currentWritableRound(String)」的上游调用点包括 「HearingRoundService.currentOpenRoundNoForPartyMessage」。
    // 下游影响：「HearingRoundService.currentWritableRound(String)」向下依次触达 「roundRepository.findTopByCaseIdOrderByRoundNoDesc」、「assertWritableRound」；计算结果以「HearingRoundEntity」交给调用方。
    // 系统意义：「HearingRoundService.currentWritableRound(String)」负责主链路中的“currentWritable轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private HearingRoundEntity currentWritableRound(String caseId) {
        HearingRoundEntity round =
                roundRepository
                        .findTopByCaseIdOrderByRoundNoDesc(caseId)
                        .orElseThrow(() -> new IllegalStateException("hearing round not open"));
        assertWritableRound(round);
        return round;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.assertWritableRound(HearingRoundEntity)」。
    // 具体功能：「HearingRoundService.assertWritableRound(HearingRoundEntity)」：断言Writable轮次；实际协作者为 「round.getClosedAt」、「round.getRoundStatus」、「round.getRoundNo」、「round.getRoundDeadlineAt」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「reason」、「HEARING_ROUND_SEALED」、「round_no」、「HEARING_ROUND_DEADLINE_EXPIRED」，最终返回「void」。
    // 上游调用：「HearingRoundService.assertWritableRound(HearingRoundEntity)」的上游调用点包括 「HearingRoundService.recordPartyMessageSubmission」、「HearingRoundService.currentWritableRound」。
    // 下游影响：「HearingRoundService.assertWritableRound(HearingRoundEntity)」向下依次触达 「round.getClosedAt」、「round.getRoundStatus」、「round.getRoundNo」、「round.getRoundDeadlineAt」。
    // 系统意义：「HearingRoundService.assertWritableRound(HearingRoundEntity)」在“Writable轮次”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void assertWritableRound(HearingRoundEntity round) {
        if (round.getClosedAt() != null
                || round.getRoundStatus() == HearingRoundStatus.COMPLETED
                || round.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "hearing round is already sealed",
                    Map.of(
                            "reason", "HEARING_ROUND_SEALED",
                            "round_no", round.getRoundNo()));
        }
        if (!round.getRoundDeadlineAt().isAfter(clock.instant())) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "hearing round deadline has expired",
                    Map.of(
                            "reason", "HEARING_ROUND_DEADLINE_EXPIRED",
                            "round_no", round.getRoundNo(),
                            "deadline_at", round.getRoundDeadlineAt().toString()));
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.lockedHearing(String)」。
    // 具体功能：「HearingRoundService.lockedHearing(String)」：加锁持锁事件庭审：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「dispute.getCaseStatus」、「hearingRoundAvailable」；不满足前置条件时抛出 「IllegalStateException」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「HearingRoundService.lockedHearing(String)」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.completeNext」、「HearingRoundService.submitParty」、「HearingRoundService.currentOpenRoundNoForPartyMessage」。
    // 下游影响：「HearingRoundService.lockedHearing(String)」向下依次触达 「caseRepository.findByIdForUpdate」、「dispute.getCaseStatus」、「hearingRoundAvailable」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「HearingRoundService.lockedHearing(String)」负责主链路中的“持锁事件庭审”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.lockedHearingForExpiry(String)」。
    // 具体功能：「HearingRoundService.lockedHearingForExpiry(String)」：加锁持锁事件庭审面向Expiry：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「hearingRoundAvailable」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「HearingRoundService.lockedHearingForExpiry(String)」的上游调用点包括 「HearingRoundService.expireDueRounds」。
    // 下游影响：「HearingRoundService.lockedHearingForExpiry(String)」向下依次触达 「caseRepository.findByIdForUpdate」、「hearingRoundAvailable」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「HearingRoundService.lockedHearingForExpiry(String)」负责主链路中的“持锁事件庭审面向Expiry”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private FulfillmentCaseEntity lockedHearingForExpiry(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return hearingRoundAvailable(dispute) ? dispute : null;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.hearingRoundAvailable(FulfillmentCaseEntity)」。
    // 具体功能：「HearingRoundService.hearingRoundAvailable(FulfillmentCaseEntity)」：判断庭审轮次Available；实际协作者为 「dispute.getCaseStatus」，最终返回「boolean」。
    // 上游调用：「HearingRoundService.hearingRoundAvailable(FulfillmentCaseEntity)」的上游调用点包括 「HearingRoundService.lockedHearing」、「HearingRoundService.lockedHearingForExpiry」。
    // 下游影响：「HearingRoundService.hearingRoundAvailable(FulfillmentCaseEntity)」向下依次触达 「dispute.getCaseStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingRoundService.hearingRoundAvailable(FulfillmentCaseEntity)」负责主链路中的“庭审轮次Available”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static boolean hearingRoundAvailable(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.HEARING_OPEN
                || dispute.getCaseStatus() == CaseStatus.HEARING;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.completeRoundAfterTimeout(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」。
    // 具体功能：「HearingRoundService.completeRoundAfterTimeout(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」：为截止时缺席的 USER/MERCHANT 生成可审计 AUTO_TIMEOUT 提交，保留已到达陈述，封存轮次后按三轮上限决定下一轮或终局，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.completeRoundAfterTimeout(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」的上游调用点包括 「HearingRoundService.submitParty」、「HearingRoundService.expireDueRounds」。
    // 下游影响：「HearingRoundService.completeRoundAfterTimeout(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」向下依次触达 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「submissionRepository.save」、「roundRepository.save」、「round.getClosedAt」；计算结果以「HearingRoundView」交给调用方。
    // 系统意义：「HearingRoundService.completeRoundAfterTimeout(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」负责主链路中的“轮次之后超时”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded(String,int,String)」。
    // 具体功能：「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded(String,int,String)」：执行revise证据卷宗之后轮次IfNeeded；实际协作者为 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「evidenceDossierRevisionService.reviseAfterRoundIfNeeded」，最终返回「void」。
    // 上游调用：「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded(String,int,String)」的上游调用点包括 「HearingRoundService.completeNext」、「HearingRoundService.completeRoundAfterTimeout」、「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded(String,int,String)」向下依次触达 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「evidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 系统意义：「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded(String,int,String)」负责主链路中的“revise证据卷宗之后轮次IfNeeded”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.advanceAfterPartySubmissionIfReady(FulfillmentCaseEntity,HearingRoundEntity,Instant,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.advanceAfterPartySubmissionIfReady(FulfillmentCaseEntity,HearingRoundEntity,Instant,AuthenticatedActor)」：双方提交齐备后立即封存本轮并记录 stop_reason；提交不齐时保持当前轮开放，封存事务提交后才通知 Workflow 和法官编排器，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.advanceAfterPartySubmissionIfReady(FulfillmentCaseEntity,HearingRoundEntity,Instant,AuthenticatedActor)」的上游调用点包括 「HearingRoundService.submitParty」、「HearingRoundService.recordPartyMessageSubmission」。
    // 下游影响：「HearingRoundService.advanceAfterPartySubmissionIfReady(FulfillmentCaseEntity,HearingRoundEntity,Instant,AuthenticatedActor)」向下依次触达 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「roundRepository.save」、「dispute.getId」、「round.getRoundNo」；计算结果以「HearingRoundView」交给调用方。
    // 系统意义：「HearingRoundService.advanceAfterPartySubmissionIfReady(FulfillmentCaseEntity,HearingRoundEntity,Instant,AuthenticatedActor)」负责主链路中的“之后当事方提交IfReady”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.dispatchRoundClosedAfterCommit(String,int,boolean)」。
    // 具体功能：「HearingRoundService.dispatchRoundClosedAfterCommit(String,int,boolean)」：分派轮次Closed之后提交；实际协作者为 「workflowCoordinator.roundCompletedAfterCommit」、「courtOrchestrator.afterRoundClosedAfterCommit」、「traceId」，最终返回「void」。
    // 上游调用：「HearingRoundService.dispatchRoundClosedAfterCommit(String,int,boolean)」的上游调用点包括 「HearingRoundService.completeNext」、「HearingRoundService.completeRoundAfterTimeout」、「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundService.dispatchRoundClosedAfterCommit(String,int,boolean)」向下依次触达 「workflowCoordinator.roundCompletedAfterCommit」、「courtOrchestrator.afterRoundClosedAfterCommit」、「traceId」。
    // 系统意义：「HearingRoundService.dispatchRoundClosedAfterCommit(String,int,boolean)」负责主链路中的“轮次Closed之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void dispatchRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound) {
        courtOrchestrator.afterRoundClosedAfterCommit(
                caseId,
                roundNo,
                finalRound,
                traceId(roundNo),
                () -> workflowCoordinator.roundCompletedAfterCommit(caseId, roundNo, false));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.openNextRound(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」。
    // 具体功能：「HearingRoundService.openNextRound(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」：按 current.roundNo+1 创建或复用下一轮，继承服务端庭审总截止时间而不重置三小时时钟，并记录 previous_round_no，最终返回「HearingRoundEntity」。
    // 上游调用：「HearingRoundService.openNextRound(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」的上游调用点包括 「HearingRoundService.completeRoundAfterTimeout」、「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundService.openNextRound(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」向下依次触达 「roundRepository.findByCaseIdAndRoundNo」、「roundRepository.save」、「hearingStateRepository.findByCaseId」、「HearingRoundEntity.open」；计算结果以「HearingRoundEntity」交给调用方。
    // 系统意义：「HearingRoundService.openNextRound(FulfillmentCaseEntity,HearingRoundEntity,Instant,String)」负责主链路中的“下一轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.autoTimeoutSubmission(FulfillmentCaseEntity,HearingRoundEntity,ActorRole,String,Instant)」。
    // 具体功能：「HearingRoundService.autoTimeoutSubmission(FulfillmentCaseEntity,HearingRoundEntity,ActorRole,String,Instant)」：构建auto超时提交；实际协作者为 「HearingRoundPartySubmissionEntity.submit」、「dispute.getId」、「round.getId」、「round.getRoundNo」；处理的关键状态/协议值包括 「HEARING_ROUND_SUBMISSION_」，最终返回「HearingRoundPartySubmissionEntity」。
    // 上游调用：「HearingRoundService.autoTimeoutSubmission(FulfillmentCaseEntity,HearingRoundEntity,ActorRole,String,Instant)」的上游调用点包括 「HearingRoundService.completeRoundAfterTimeout」。
    // 下游影响：「HearingRoundService.autoTimeoutSubmission(FulfillmentCaseEntity,HearingRoundEntity,ActorRole,String,Instant)」向下依次触达 「HearingRoundPartySubmissionEntity.submit」、「dispute.getId」、「round.getId」、「round.getRoundNo」；计算结果以「HearingRoundPartySubmissionEntity」交给调用方。
    // 系统意义：「HearingRoundService.autoTimeoutSubmission(FulfillmentCaseEntity,HearingRoundEntity,ActorRole,String,Instant)」负责主链路中的“auto超时提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.assertController(AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.assertController(AuthenticatedActor)」：断言控制器；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「HearingRoundService.assertController(AuthenticatedActor)」的上游调用点包括 「HearingRoundService.completeNext」。
    // 下游影响：「HearingRoundService.assertController(AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「HearingRoundService.assertController(AuthenticatedActor)」在“控制器”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static void assertController(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.SYSTEM
                && actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN) {
            throw new ForbiddenException("hearing round completion requires trusted actor");
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.assertCaseParty(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.assertCaseParty(FulfillmentCaseEntity,AuthenticatedActor)」：断言案件当事方；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「HearingRoundService.assertCaseParty(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「HearingRoundService.submitParty」、「HearingRoundService.currentOpenRoundNoForPartyMessage」、「HearingRoundService.recordPartyMessageSubmission」。
    // 下游影响：「HearingRoundService.assertCaseParty(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「HearingRoundService.assertCaseParty(FulfillmentCaseEntity,AuthenticatedActor)」在“案件当事方”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can访问；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「HearingRoundService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「HearingRoundService.list」、「HearingRoundService.status」。
    // 下游影响：「HearingRoundService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「HearingRoundService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.view(String,HearingRoundEntity,AuthenticatedActor)」。
    // 具体功能：「HearingRoundService.view(String,HearingRoundEntity,AuthenticatedActor)」：构建视图；实际协作者为 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「round.getRoundNo」、「round.getId」、「round.getRoundStatus」，最终返回「HearingRoundView」。
    // 上游调用：「HearingRoundService.view(String,HearingRoundEntity,AuthenticatedActor)」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.completeNext」、「HearingRoundService.expire」、「HearingRoundService.list」。
    // 下游影响：「HearingRoundService.view(String,HearingRoundEntity,AuthenticatedActor)」向下依次触达 「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「round.getRoundNo」、「round.getId」、「round.getRoundStatus」；计算结果以「HearingRoundView」交给调用方。
    // 系统意义：「HearingRoundService.view(String,HearingRoundEntity,AuthenticatedActor)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.recordRoundEvent(String,String,Map,String,String)」。
    // 具体功能：「HearingRoundService.recordRoundEvent(String,String,Map,String,String)」：记录轮次事件：先把 Optional 空值转换为明确业务异常；实际协作者为 「roomRepository.findByCaseIdAndRoomType」、「eventService.recordLifecycleEvent」、「hearingRoom.getId」，最终返回「void」。
    // 上游调用：「HearingRoundService.recordRoundEvent(String,String,Map,String,String)」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.submitParty」、「HearingRoundService.recordPartyMessageSubmission」、「HearingRoundService.recordHearingPhaseChanged」。
    // 下游影响：「HearingRoundService.recordRoundEvent(String,String,Map,String,String)」向下依次触达 「roomRepository.findByCaseIdAndRoomType」、「eventService.recordLifecycleEvent」、「hearingRoom.getId」。
    // 系统意义：「HearingRoundService.recordRoundEvent(String,String,Map,String,String)」负责主链路中的“轮次事件”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.submittedRoundSummaryV2(List)」。
    // 具体功能：「HearingRoundService.submittedRoundSummaryV2(List)」：提交submitted轮次SummaryV2；实际协作者为 「submissionJson」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.submittedRoundSummaryV2(List)」的上游调用点包括 「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundService.submittedRoundSummaryV2(List)」向下依次触达 「submissionJson」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.submittedRoundSummaryV2(List)」负责主链路中的“submitted轮次SummaryV2”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.timeoutRoundSummaryV2(List)」。
    // 具体功能：「HearingRoundService.timeoutRoundSummaryV2(List)」：构建超时轮次SummaryV2；实际协作者为 「submissionJson」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.timeoutRoundSummaryV2(List)」的上游调用点包括 「HearingRoundService.completeRoundAfterTimeout」。
    // 下游影响：「HearingRoundService.timeoutRoundSummaryV2(List)」向下依次触达 「submissionJson」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.timeoutRoundSummaryV2(List)」负责主链路中的“超时轮次SummaryV2”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.autoTimeoutJsonV2(HearingRoundEntity,ActorRole)」。
    // 具体功能：「HearingRoundService.autoTimeoutJsonV2(HearingRoundEntity,ActorRole)」：构建auto超时JSONV2；实际协作者为 「round.getRoundNo」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.autoTimeoutJsonV2(HearingRoundEntity,ActorRole)」的上游调用点包括 「HearingRoundService.autoTimeoutSubmission」。
    // 下游影响：「HearingRoundService.autoTimeoutJsonV2(HearingRoundEntity,ActorRole)」向下依次触达 「round.getRoundNo」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.autoTimeoutJsonV2(HearingRoundEntity,ActorRole)」负责主链路中的“auto超时JSONV2”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String autoTimeoutJsonV2(HearingRoundEntity round, ActorRole role) {
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","source":"AUTO_TIMEOUT","participant_role":"%s","round_no":%d,"statement":"本轮时效届满前未主动提交，系统按当前已留存内容自动提交。"}
                """
                .formatted(role.name(), round.getRoundNo())
                .strip();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.roomMessageSubmissionJson(HearingRoundEntity,ActorRole,String,String)」。
    // 具体功能：「HearingRoundService.roomMessageSubmissionJson(HearingRoundEntity,ActorRole,String,String)」：构建房间消息提交JSON；实际协作者为 「round.getRoundNo」、「jsonEscape」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.roomMessageSubmissionJson(HearingRoundEntity,ActorRole,String,String)」的上游调用点包括 「HearingRoundService.recordPartyMessageSubmission」。
    // 下游影响：「HearingRoundService.roomMessageSubmissionJson(HearingRoundEntity,ActorRole,String,String)」向下依次触达 「round.getRoundNo」、「jsonEscape」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.roomMessageSubmissionJson(HearingRoundEntity,ActorRole,String,String)」负责主链路中的“房间消息提交JSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.submittedRoundSummary(List)」。
    // 具体功能：「HearingRoundService.submittedRoundSummary(List)」：提交submitted轮次Summary；实际协作者为 「submissionJson」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.submittedRoundSummary(List)」只由「HearingRoundService」内部流程使用，负责封装“submitted轮次Summary”这一步校验、映射或状态转换。
    // 下游影响：「HearingRoundService.submittedRoundSummary(List)」向下依次触达 「submissionJson」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.submittedRoundSummary(List)」负责主链路中的“submitted轮次Summary”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.timeoutRoundSummary(List)」。
    // 具体功能：「HearingRoundService.timeoutRoundSummary(List)」：构建超时轮次Summary；实际协作者为 「submissionJson」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.timeoutRoundSummary(List)」只由「HearingRoundService」内部流程使用，负责封装“超时轮次Summary”这一步校验、映射或状态转换。
    // 下游影响：「HearingRoundService.timeoutRoundSummary(List)」向下依次触达 「submissionJson」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.timeoutRoundSummary(List)」负责主链路中的“超时轮次Summary”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.autoTimeoutJson(HearingRoundEntity,ActorRole)」。
    // 具体功能：「HearingRoundService.autoTimeoutJson(HearingRoundEntity,ActorRole)」：构建auto超时JSON；实际协作者为 「round.getRoundNo」、「strip」、「formatted」，最终返回「String」。
    // 上游调用：「HearingRoundService.autoTimeoutJson(HearingRoundEntity,ActorRole)」只由「HearingRoundService」内部流程使用，负责封装“auto超时JSON”这一步校验、映射或状态转换。
    // 下游影响：「HearingRoundService.autoTimeoutJson(HearingRoundEntity,ActorRole)」向下依次触达 「round.getRoundNo」、「strip」、「formatted」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.autoTimeoutJson(HearingRoundEntity,ActorRole)」负责主链路中的“auto超时JSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String autoTimeoutJson(HearingRoundEntity round, ActorRole role) {
        return """
                {"trigger":"ROUND_DEADLINE_EXPIRED","source":"AUTO_TIMEOUT","participant_role":"%s","round_no":%d,"statement":"本轮时效届满前未主动提交，系统按当前已留存内容自动提交。"}
                """
                .formatted(role.name(), round.getRoundNo())
                .strip();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.submissionJson(List,ActorRole)」。
    // 具体功能：「HearingRoundService.submissionJson(List,ActorRole)」：构建提交JSON；实际协作者为 「item.getParticipantRole」、「findFirst」；处理的关键状态/协议值包括 「{}」，最终返回「String」。
    // 上游调用：「HearingRoundService.submissionJson(List,ActorRole)」的上游调用点包括 「HearingRoundService.submittedRoundSummaryV2」、「HearingRoundService.timeoutRoundSummaryV2」、「HearingRoundService.submittedRoundSummary」、「HearingRoundService.timeoutRoundSummary」。
    // 下游影响：「HearingRoundService.submissionJson(List,ActorRole)」向下依次触达 「item.getParticipantRole」、「findFirst」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.submissionJson(List,ActorRole)」负责主链路中的“提交JSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static String submissionJson(
            List<HearingRoundPartySubmissionEntity> submissions, ActorRole role) {
        return submissions.stream()
                .filter(item -> item.getParticipantRole() == role)
                .findFirst()
                .map(HearingRoundPartySubmissionEntity::getSubmissionJson)
                .orElse("{}");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.jsonEscape(String)」。
    // 具体功能：「HearingRoundService.jsonEscape(String)」：构建JSON转义；实际协作者为 「value.charAt」、「escaped.append」；处理的关键状态/协议值包括 「\\\\」、「\\\」、「\\n」、「\\r」，最终返回「String」。
    // 上游调用：「HearingRoundService.jsonEscape(String)」的上游调用点包括 「HearingRoundService.roomMessageSubmissionJson」。
    // 下游影响：「HearingRoundService.jsonEscape(String)」向下依次触达 「value.charAt」、「escaped.append」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.jsonEscape(String)」统一“JSON转义”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.compactUuid()」。
    // 具体功能：「HearingRoundService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「HearingRoundService.compactUuid()」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.completeNext」、「HearingRoundService.submitParty」、「HearingRoundService.recordPartyMessageSubmission」。
    // 下游影响：「HearingRoundService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.compactUuid()」负责主链路中的“UUID”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundService.traceId(int)」。
    // 具体功能：「HearingRoundService.traceId(int)」：构建链路标识标识；处理的关键状态/协议值包括 「TRACE_HEARING_ROUND_」，最终返回「String」。
    // 上游调用：「HearingRoundService.traceId(int)」的上游调用点包括 「HearingRoundService.dispatchRoundClosedAfterCommit」。
    // 下游影响：「HearingRoundService.traceId(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundService.traceId(int)」负责主链路中的“链路标识标识”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String traceId(int roundNo) {
        return "TRACE_HEARING_ROUND_" + roundNo;
    }
}
