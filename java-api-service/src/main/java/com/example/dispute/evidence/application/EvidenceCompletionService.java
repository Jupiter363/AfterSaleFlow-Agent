/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据完成确认规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「warnDeadline」、「complete」、「status」、「expire」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
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
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceCompletionService」。
// 类型职责：编排证据完成确认规则、权限校验与事实读写；本类型显式提供 「EvidenceCompletionService」、「warnDeadline」、「complete」、「status」、「expire」、「completionVersion」。
// 协作关系：主要由 「EvidenceController.complete」、「EvidenceController.completion」、「EvidenceWindowActivitiesAdapter.expire」、「EvidenceWindowActivitiesAdapter.warn」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceCompletionService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidencePartyCompletionRepository completionRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final EvidenceDossierFreezer dossierFreezer;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final CaseEventService caseEventService;
    private final NotificationService notificationService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final HearingRoundService hearingRoundService;
    private final HearingWorkflowCoordinator hearingWorkflowCoordinator;
    private final DisputeProperties disputeProperties;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.EvidenceCompletionService(FulfillmentCaseRepository,EvidencePartyCompletionRepository,EvidenceItemRepository,CaseRoomRepository,CasePhaseClockRepository,EvidenceDossierFreezer,EvidenceWindowCoordinator,CaseEventService,NotificationService,CaseLifecycleNotificationService,HearingRoundService,HearingWorkflowCoordinator,DisputeProperties,Clock)」。
    // 具体功能：「EvidenceCompletionService.EvidenceCompletionService(FulfillmentCaseRepository,EvidencePartyCompletionRepository,EvidenceItemRepository,CaseRoomRepository,CasePhaseClockRepository,EvidenceDossierFreezer,EvidenceWindowCoordinator,CaseEventService,NotificationService,CaseLifecycleNotificationService,HearingRoundService,HearingWorkflowCoordinator,DisputeProperties,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「completionRepository」(EvidencePartyCompletionRepository)、「evidenceRepository」(EvidenceItemRepository)、「roomRepository」(CaseRoomRepository)、「clockRepository」(CasePhaseClockRepository)、「dossierFreezer」(EvidenceDossierFreezer)、「evidenceWindowCoordinator」(EvidenceWindowCoordinator)、「caseEventService」(CaseEventService)、「notificationService」(NotificationService)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「hearingRoundService」(HearingRoundService)、「hearingWorkflowCoordinator」(HearingWorkflowCoordinator)、「disputeProperties」(DisputeProperties)、「clock」(Clock) 并保存为「EvidenceCompletionService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceCompletionService.EvidenceCompletionService(FulfillmentCaseRepository,EvidencePartyCompletionRepository,EvidenceItemRepository,CaseRoomRepository,CasePhaseClockRepository,EvidenceDossierFreezer,EvidenceWindowCoordinator,CaseEventService,NotificationService,CaseLifecycleNotificationService,HearingRoundService,HearingWorkflowCoordinator,DisputeProperties,Clock)」的上游创建点包括 「EvidenceCompletionServiceTest.setUp」。
    // 下游影响：「EvidenceCompletionService.EvidenceCompletionService(FulfillmentCaseRepository,EvidencePartyCompletionRepository,EvidenceItemRepository,CaseRoomRepository,CasePhaseClockRepository,EvidenceDossierFreezer,EvidenceWindowCoordinator,CaseEventService,NotificationService,CaseLifecycleNotificationService,HearingRoundService,HearingWorkflowCoordinator,DisputeProperties,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceCompletionService.EvidenceCompletionService(FulfillmentCaseRepository,EvidencePartyCompletionRepository,EvidenceItemRepository,CaseRoomRepository,CasePhaseClockRepository,EvidenceDossierFreezer,EvidenceWindowCoordinator,CaseEventService,NotificationService,CaseLifecycleNotificationService,HearingRoundService,HearingWorkflowCoordinator,DisputeProperties,Clock)」负责主链路中的“证据完成确认服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceCompletionService(
            FulfillmentCaseRepository caseRepository,
            EvidencePartyCompletionRepository completionRepository,
            EvidenceItemRepository evidenceRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            EvidenceDossierFreezer dossierFreezer,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            CaseEventService caseEventService,
            NotificationService notificationService,
            CaseLifecycleNotificationService lifecycleNotifications,
            HearingRoundService hearingRoundService,
            HearingWorkflowCoordinator hearingWorkflowCoordinator,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.completionRepository = completionRepository;
        this.evidenceRepository = evidenceRepository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.dossierFreezer = dossierFreezer;
        this.evidenceWindowCoordinator = evidenceWindowCoordinator;
        this.caseEventService = caseEventService;
        this.notificationService = notificationService;
        this.lifecycleNotifications = lifecycleNotifications;
        this.hearingRoundService = hearingRoundService;
        this.hearingWorkflowCoordinator = hearingWorkflowCoordinator;
        this.disputeProperties = disputeProperties;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.warnDeadline(String)」。
    // 具体功能：「EvidenceCompletionService.warnDeadline(String)」：只在案件仍处于举证阶段且服务端截止时间存在时发送一次临期通知；Temporal 计时是触发源，浏览器倒计时不驱动状态，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.warnDeadline(String)」的上游调用点包括 「EvidenceWindowActivitiesAdapter.warn」、「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen」。
    // 下游影响：「EvidenceCompletionService.warnDeadline(String)」向下依次触达 「caseRepository.findByIdForUpdate」、「dispute.getCaseStatus」、「dispute.getCurrentDeadlineAt」、「lifecycleNotifications.evidenceDeadlineWarning」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceCompletionService.warnDeadline(String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public void warnDeadline(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        if (dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                && dispute.getCurrentDeadlineAt() != null) {
            lifecycleNotifications.evidenceDeadlineWarning(
                    dispute, dispute.getCurrentDeadlineAt());
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.complete(String,AuthenticatedActor,String)」。
    // 具体功能：「EvidenceCompletionService.complete(String,AuthenticatedActor,String)」：校验 USER/MERCHANT 参与身份、发起方至少提交一份有效证据，并按 case+version+role 幂等记录完成确认；双方均完成时立即封卷、关闭证据室、开放庭审并 Signal 举证 Workflow，最终返回「EvidenceCompletionView」。
    // 上游调用：「EvidenceCompletionService.complete(String,AuthenticatedActor,String)」的上游调用点包括 「EvidenceController.complete」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」、「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence」。
    // 下游影响：「EvidenceCompletionService.complete(String,AuthenticatedActor,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「completionRepository.findByCaseIdAndIdempotencyKey」、「completionRepository.findByCaseIdAndDossierVersionAndParticipantRole」、「completionRepository.save」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceCompletionService.complete(String,AuthenticatedActor,String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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
        assertInitiatorHasSubmittedEvidence(dispute);
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
            hearingRoundService.ensureInitialRoundOpen(
                    caseId, dossierVersion, actor.actorId());
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.status(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceCompletionService.status(String,AuthenticatedActor)」：读取最新卷宗版本下双方完成记录，返回 USER/MERCHANT 完成标志、截止时间和是否已满足提前封卷条件，最终返回「EvidenceCompletionStatusView」。
    // 上游调用：「EvidenceCompletionService.status(String,AuthenticatedActor)」的上游调用点包括 「EvidenceController.completion」、「EvidenceCompletionService.expire」、「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection」。
    // 下游影响：「EvidenceCompletionService.status(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus」、「dossierFreezer.latestVersion」、「item.getParticipantRole」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceCompletionService.status(String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.expire(String)」。
    // 具体功能：「EvidenceCompletionService.expire(String)」：响应 Temporal 举证截止：即使单方缺席也冻结当前有效证据，关闭证据室、开放首轮庭审并标记 DEADLINE_EXPIRED，最终返回「EvidenceCompletionStatusView」。
    // 上游调用：「EvidenceCompletionService.expire(String)」的上游调用点包括 「EvidenceWindowActivitiesAdapter.expire」、「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing」、「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing」。
    // 下游影响：「EvidenceCompletionService.expire(String)」向下依次触达 「caseRepository.findByIdForUpdate」、「hearingRoundService.ensureInitialRoundOpen」、「hearingWorkflowCoordinator.startAfterCommit」、「dispute.getCaseStatus」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceCompletionService.expire(String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceCompletionStatusView expire(String caseId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        int dossierVersion = completionVersion(caseId);
        if (dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                || dispute.getCaseStatus() == CaseStatus.EVIDENCE_SEALED) {
            assertInitiatorHasSubmittedEvidence(dispute);
            dossierFreezer.freeze(caseId, dossierVersion, "evidence-deadline");
            CaseRoomEntity hearingRoom =
                    sealEvidenceAndOpenHearing(
                            dispute,
                            OffsetDateTime.now(clock),
                            "evidence-deadline",
                            false);
            announceHearingOpened(
                    dispute, hearingRoom, dossierVersion, "DEADLINE_EXPIRED");
            hearingRoundService.ensureInitialRoundOpen(
                    caseId, dossierVersion, "evidence-deadline");
            hearingWorkflowCoordinator.startAfterCommit(caseId, dossierVersion);
        }
        return status(caseId, new AuthenticatedActor("evidence-deadline", ActorRole.SYSTEM));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.completionVersion(String)」。
    // 具体功能：「EvidenceCompletionService.completionVersion(String)」：构建完成确认版本；实际协作者为 「completionRepository.findTopByCaseIdOrderByDossierVersionDesc」、「dossierFreezer.targetVersion」，最终返回「int」。
    // 上游调用：「EvidenceCompletionService.completionVersion(String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.status」、「EvidenceCompletionService.expire」。
    // 下游影响：「EvidenceCompletionService.completionVersion(String)」向下依次触达 「completionRepository.findTopByCaseIdOrderByDossierVersionDesc」、「dossierFreezer.targetVersion」；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceCompletionService.completionVersion(String)」负责主链路中的“完成确认版本”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private int completionVersion(String caseId) {
        return completionRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .map(EvidencePartyCompletionEntity::getDossierVersion)
                .orElseGet(() -> dossierFreezer.targetVersion(caseId));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.sealEvidenceAndOpenHearing(FulfillmentCaseEntity,OffsetDateTime,String,boolean)」。
    // 具体功能：「EvidenceCompletionService.sealEvidenceAndOpenHearing(FulfillmentCaseEntity,OffsetDateTime,String,boolean)」：在同一事务中冻结目标卷宗版本、关闭 EVIDENCE 房间与时钟、创建/开放 HEARING 房间和三小时时钟，再准备初始庭审轮次；事务提交后才启动庭审 Workflow 与通知，最终返回「CaseRoomEntity」。
    // 上游调用：「EvidenceCompletionService.sealEvidenceAndOpenHearing(FulfillmentCaseEntity,OffsetDateTime,String,boolean)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」。
    // 下游影响：「EvidenceCompletionService.sealEvidenceAndOpenHearing(FulfillmentCaseEntity,OffsetDateTime,String,boolean)」向下依次触达 「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「clockRepository.findByCaseIdAndClockType」、「clockRepository.save」；计算结果以「CaseRoomEntity」交给调用方。
    // 系统意义：「EvidenceCompletionService.sealEvidenceAndOpenHearing(FulfillmentCaseEntity,OffsetDateTime,String,boolean)」负责主链路中的“seal证据并且Open庭审”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCompletionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」：断言当事方；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCompletionService.complete」。
    // 下游影响：「EvidenceCompletionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「EvidenceCompletionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」在“当事方”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void assertParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER
                                && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) throw new SecurityException("only case parties can complete evidence");
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity)」：断言发起方HasSubmitted证据；实际协作者为 「countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」、「dispute.getInitiatorRole」、「dispute.getId」；不满足前置条件时抛出 「BadRequestException」；处理的关键状态/协议值包括 「case_id」、「initiator_role」、「required_submission_status」，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」。
    // 下游影响：「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity)」向下依次触达 「countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」、「dispute.getInitiatorRole」、「dispute.getId」。
    // 系统意义：「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity)」在“发起方HasSubmitted证据”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private void assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity dispute) {
        ActorRole initiatorRole = dispute.getInitiatorRole();
        long submitted =
                evidenceRepository
                        .countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull(
                                dispute.getId(),
                                initiatorRole.name(),
                                EvidenceSubmissionStatus.SUBMITTED);
        if (submitted > 0) {
            return;
        }
        throw new BadRequestException(
                "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。",
                Map.of(
                        "case_id", dispute.getId(),
                        "initiator_role", initiatorRole.name(),
                        "required_submission_status", EvidenceSubmissionStatus.SUBMITTED.name()));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceCompletionService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can访问；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceCompletionService.status」。
    // 下游影响：「EvidenceCompletionService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「EvidenceCompletionService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.announceHearingOpened(FulfillmentCaseEntity,CaseRoomEntity,int,String)」。
    // 具体功能：「EvidenceCompletionService.announceHearingOpened(FulfillmentCaseEntity,CaseRoomEntity,int,String)」：执行announce庭审Opened；实际协作者为 「caseEventService.recordLifecycleEvent」、「dispute.getId」、「hearingRoom.getId」、「dispute.getCurrentDeadlineAt」；处理的关键状态/协议值包括 「hearing-opened:」、「HEARING_OPENED」、「dossier_version」、「reason」，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.announceHearingOpened(FulfillmentCaseEntity,CaseRoomEntity,int,String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」。
    // 下游影响：「EvidenceCompletionService.announceHearingOpened(FulfillmentCaseEntity,CaseRoomEntity,int,String)」向下依次触达 「caseEventService.recordLifecycleEvent」、「dispute.getId」、「hearingRoom.getId」、「dispute.getCurrentDeadlineAt」。
    // 系统意义：「EvidenceCompletionService.announceHearingOpened(FulfillmentCaseEntity,CaseRoomEntity,int,String)」负责主链路中的“announce庭审Opened”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.sendHearingNotice(FulfillmentCaseEntity,String,ActorRole,String,String)」。
    // 具体功能：「EvidenceCompletionService.sendHearingNotice(FulfillmentCaseEntity,String,ActorRole,String,String)」：发送庭审Notice；实际协作者为 「notificationService.send」、「dispute.getId」、「dispute.getCurrentDeadlineAt」；处理的关键状态/协议值包括 「争议审判庭已开放」、「证据卷宗已封存，请在三小时内进入审判庭参与处理。」、「{\"reason\":\」、「\",\"deadline_at\":\」，最终返回「void」。
    // 上游调用：「EvidenceCompletionService.sendHearingNotice(FulfillmentCaseEntity,String,ActorRole,String,String)」的上游调用点包括 「EvidenceCompletionService.announceHearingOpened」。
    // 下游影响：「EvidenceCompletionService.sendHearingNotice(FulfillmentCaseEntity,String,ActorRole,String,String)」向下依次触达 「notificationService.send」、「dispute.getId」、「dispute.getCurrentDeadlineAt」。
    // 系统意义：「EvidenceCompletionService.sendHearingNotice(FulfillmentCaseEntity,String,ActorRole,String,String)」负责主链路中的“庭审Notice”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.isEvidenceOpen(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceCompletionService.isEvidenceOpen(FulfillmentCaseEntity)」：判断是否证据Open；实际协作者为 「dispute.getCaseStatus」，最终返回「boolean」。
    // 上游调用：「EvidenceCompletionService.isEvidenceOpen(FulfillmentCaseEntity)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.status」。
    // 下游影响：「EvidenceCompletionService.isEvidenceOpen(FulfillmentCaseEntity)」向下依次触达 「dispute.getCaseStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceCompletionService.isEvidenceOpen(FulfillmentCaseEntity)」负责主链路中的“证据Open”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean isEvidenceOpen(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                || dispute.getCaseStatus() == CaseStatus.EVIDENCE_SEALED;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceCompletionService.compactUuid()」。
    // 具体功能：「EvidenceCompletionService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceCompletionService.compactUuid()」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」。
    // 下游影响：「EvidenceCompletionService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceCompletionService.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
