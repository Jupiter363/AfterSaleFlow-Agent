/*
 * 所属模块：共享小法庭。
 * 文件职责：编排和解规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「propose」、「confirm」、「get」、「list」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
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

// 所属模块：【共享小法庭 / 应用编排层】类型「SettlementService」。
// 类型职责：编排和解规则、权限校验与事实读写；本类型显式提供 「SettlementService」、「propose」、「confirm」、「get」、「list」、「view」。
// 协作关系：主要由 「HearingCollaborationController.confirmSettlement」、「HearingCollaborationController.hearing」、「HearingCollaborationController.proposeSettlement」、「HearingCollaborationController.settlements」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class SettlementService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final SettlementProposalRepository proposalRepository;
    private final SettlementConfirmationRepository confirmationRepository;
    private final CaseEventService eventService;
    private final NotificationService notificationService;
    private final Clock clock;

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.SettlementService(FulfillmentCaseRepository,CaseRoomRepository,SettlementProposalRepository,SettlementConfirmationRepository,CaseEventService,NotificationService,HearingWorkflowCoordinator,Clock)」。
    // 具体功能：「SettlementService.SettlementService(FulfillmentCaseRepository,CaseRoomRepository,SettlementProposalRepository,SettlementConfirmationRepository,CaseEventService,NotificationService,HearingWorkflowCoordinator,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「proposalRepository」(SettlementProposalRepository)、「confirmationRepository」(SettlementConfirmationRepository)、「eventService」(CaseEventService)、「notificationService」(NotificationService)、「hearingWorkflowCoordinator」(HearingWorkflowCoordinator)、「clock」(Clock) 并保存为「SettlementService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「SettlementService.SettlementService(FulfillmentCaseRepository,CaseRoomRepository,SettlementProposalRepository,SettlementConfirmationRepository,CaseEventService,NotificationService,HearingWorkflowCoordinator,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「SettlementService.SettlementService(FulfillmentCaseRepository,CaseRoomRepository,SettlementProposalRepository,SettlementConfirmationRepository,CaseEventService,NotificationService,HearingWorkflowCoordinator,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementService.SettlementService(FulfillmentCaseRepository,CaseRoomRepository,SettlementProposalRepository,SettlementConfirmationRepository,CaseEventService,NotificationService,HearingWorkflowCoordinator,Clock)」负责主链路中的“和解服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public SettlementService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            SettlementProposalRepository proposalRepository,
            SettlementConfirmationRepository confirmationRepository,
            CaseEventService eventService,
            NotificationService notificationService,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.proposalRepository = proposalRepository;
        this.confirmationRepository = confirmationRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.propose(String,SettlementProposalCommand,AuthenticatedActor,String)」。
    // 具体功能：「SettlementService.propose(String,SettlementProposalCommand,AuthenticatedActor,String)」：在案件锁内创建下一和解版本；新版本会使旧版双方确认失效，保存提案后记录生命周期事件但不会直接结束庭审，最终返回「SettlementView」。
    // 上游调用：「SettlementService.propose(String,SettlementProposalCommand,AuthenticatedActor,String)」的上游调用点包括 「HearingCollaborationController.proposeSettlement」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」。
    // 下游影响：「SettlementService.propose(String,SettlementProposalCommand,AuthenticatedActor,String)」向下依次触达 「proposalRepository.findTopByCaseIdOrderByProposalVersionDesc」、「proposalRepository.save」、「eventService.recordLifecycleEvent」、「SettlementProposalEntity.propose」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「SettlementService.propose(String,SettlementProposalCommand,AuthenticatedActor,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.confirm(String,int,AuthenticatedActor,String)」。
    // 具体功能：「SettlementService.confirm(String,int,AuthenticatedActor,String)」：要求 USER/MERCHANT 确认当前最新版本并按幂等键去重；只有双方确认同一 proposalId/version 后才标记 CONFIRMED 并 Signal Workflow，最终返回「SettlementView」。
    // 上游调用：「SettlementService.confirm(String,int,AuthenticatedActor,String)」的上游调用点包括 「HearingCollaborationController.confirmSettlement」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」。
    // 下游影响：「SettlementService.confirm(String,int,AuthenticatedActor,String)」向下依次触达 「proposalRepository.findTopByCaseIdOrderByProposalVersionDesc」、「confirmationRepository.findByCaseIdAndIdempotencyKey」、「confirmationRepository.findByProposalIdAndParticipantRole」、「confirmationRepository.save」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「SettlementService.confirm(String,int,AuthenticatedActor,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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
        Optional<SettlementConfirmationEntity> byKey =
                confirmationRepository.findByCaseIdAndIdempotencyKey(
                        caseId, idempotencyKey);
        if (byKey.isPresent()) {
            SettlementConfirmationEntity existing = byKey.orElseThrow();
            if (existing.getProposalId().equals(current.getId())
                    && existing.getProposalVersion() == version
                    && existing.getParticipantRole() == actor.role()
                    && existing.getParticipantId().equals(actor.actorId())) {
                return view(current);
            }
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different settlement confirmation");
        }
        if (current.getProposalVersion() != version
                || current.getProposalStatus()
                        != SettlementStatus.PENDING_CONFIRMATION) {
            throw new SettlementVersionConflictException(
                    caseId, version, current.getProposalVersion());
        }
        Optional<SettlementConfirmationEntity> byRole =
                confirmationRepository.findByProposalIdAndParticipantRole(
                        current.getId(), actor.role());
        if (byRole.isEmpty()) {
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
        }
        return view(current);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.get(String,int,AuthenticatedActor)」。
    // 具体功能：「SettlementService.get(String,int,AuthenticatedActor)」：读取和解：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「proposalRepository.findByCaseIdAndProposalVersion」、「assertCanAccess」、「view」，最终返回「SettlementView」。
    // 上游调用：「SettlementService.get(String,int,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」。
    // 下游影响：「SettlementService.get(String,int,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「proposalRepository.findByCaseIdAndProposalVersion」、「assertCanAccess」、「view」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「SettlementService.get(String,int,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.list(String,AuthenticatedActor)」。
    // 具体功能：「SettlementService.list(String,AuthenticatedActor)」：列出列表：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「proposalRepository.findAllByCaseIdOrderByProposalVersionDesc」、「assertCanAccess」，最终返回「List<SettlementView>」。
    // 上游调用：「SettlementService.list(String,AuthenticatedActor)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCollaborationController.settlements」、「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」。
    // 下游影响：「SettlementService.list(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「proposalRepository.findAllByCaseIdOrderByProposalVersionDesc」、「assertCanAccess」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「SettlementService.list(String,AuthenticatedActor)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.view(SettlementProposalEntity)」。
    // 具体功能：「SettlementService.view(SettlementProposalEntity)」：构建视图；实际协作者为 「confirmationRepository.findAllByProposalIdAndConfirmationStatus」、「proposal.getId」、「proposal.getCaseId」、「proposal.getProposalVersion」；处理的关键状态/协议值包括 「CONFIRMED」，最终返回「SettlementView」。
    // 上游调用：「SettlementService.view(SettlementProposalEntity)」的上游调用点包括 「SettlementService.propose」、「SettlementService.confirm」、「SettlementService.get」。
    // 下游影响：「SettlementService.view(SettlementProposalEntity)」向下依次触达 「confirmationRepository.findAllByProposalIdAndConfirmationStatus」、「proposal.getId」、「proposal.getCaseId」、「proposal.getProposalVersion」；计算结果以「SettlementView」交给调用方。
    // 系统意义：「SettlementService.view(SettlementProposalEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.lockedHearing(String)」。
    // 具体功能：「SettlementService.lockedHearing(String)」：加锁持锁事件庭审：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「dispute.getCaseStatus」；不满足前置条件时抛出 「IllegalStateException」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「SettlementService.lockedHearing(String)」的上游调用点包括 「SettlementService.propose」、「SettlementService.confirm」。
    // 下游影响：「SettlementService.lockedHearing(String)」向下依次触达 「caseRepository.findByIdForUpdate」、「dispute.getCaseStatus」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「SettlementService.lockedHearing(String)」负责主链路中的“持锁事件庭审”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.hearingRoom(String)」。
    // 具体功能：「SettlementService.hearingRoom(String)」：构建庭审房间：先把 Optional 空值转换为明确业务异常；实际协作者为 「roomRepository.findByCaseIdAndRoomType」，最终返回「CaseRoomEntity」。
    // 上游调用：「SettlementService.hearingRoom(String)」的上游调用点包括 「SettlementService.propose」、「SettlementService.confirm」。
    // 下游影响：「SettlementService.hearingRoom(String)」向下依次触达 「roomRepository.findByCaseIdAndRoomType」；计算结果以「CaseRoomEntity」交给调用方。
    // 系统意义：「SettlementService.hearingRoom(String)」负责主链路中的“庭审房间”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private CaseRoomEntity hearingRoom(String caseId) {
        return roomRepository
                .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.notifyParties(FulfillmentCaseEntity,int)」。
    // 具体功能：「SettlementService.notifyParties(FulfillmentCaseEntity,int)」：通知参与方；实际协作者为 「dispute.getUserId」、「dispute.getMerchantId」、「sendConfirmationNotice」，最终返回「void」。
    // 上游调用：「SettlementService.notifyParties(FulfillmentCaseEntity,int)」的上游调用点包括 「SettlementService.propose」。
    // 下游影响：「SettlementService.notifyParties(FulfillmentCaseEntity,int)」向下依次触达 「dispute.getUserId」、「dispute.getMerchantId」、「sendConfirmationNotice」。
    // 系统意义：「SettlementService.notifyParties(FulfillmentCaseEntity,int)」负责主链路中的“参与方”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void notifyParties(FulfillmentCaseEntity dispute, int version) {
        sendConfirmationNotice(dispute, dispute.getUserId(), ActorRole.USER, version);
        sendConfirmationNotice(
                dispute, dispute.getMerchantId(), ActorRole.MERCHANT, version);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.sendConfirmationNotice(FulfillmentCaseEntity,String,ActorRole,int)」。
    // 具体功能：「SettlementService.sendConfirmationNotice(FulfillmentCaseEntity,String,ActorRole,int)」：发送ConfirmationNotice；实际协作者为 「notificationService.send」、「dispute.getId」；处理的关键状态/协议值包括 「settlement-proposed:」、「新的和解方案待确认」、「请进入争议审判庭核对当前版本，双方确认同一版本后才视为达成一致。」、「{\"settlement_version\":」，最终返回「void」。
    // 上游调用：「SettlementService.sendConfirmationNotice(FulfillmentCaseEntity,String,ActorRole,int)」的上游调用点包括 「SettlementService.notifyParties」。
    // 下游影响：「SettlementService.sendConfirmationNotice(FulfillmentCaseEntity,String,ActorRole,int)」向下依次触达 「notificationService.send」、「dispute.getId」。
    // 系统意义：「SettlementService.sendConfirmationNotice(FulfillmentCaseEntity,String,ActorRole,int)」负责主链路中的“ConfirmationNotice”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.assertCanPropose(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「SettlementService.assertCanPropose(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can和解提案；实际协作者为 「actor.role」、「assertParty」，最终返回「void」。
    // 上游调用：「SettlementService.assertCanPropose(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「SettlementService.propose」。
    // 下游影响：「SettlementService.assertCanPropose(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「assertParty」。
    // 系统意义：「SettlementService.assertCanPropose(FulfillmentCaseEntity,AuthenticatedActor)」在“Can和解提案”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static void assertCanPropose(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (actor.role() == ActorRole.SYSTEM) return;
        assertParty(dispute, actor);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「SettlementService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」：断言当事方；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「SettlementService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「SettlementService.confirm」、「SettlementService.assertCanPropose」。
    // 下游影响：「SettlementService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「SettlementService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」在“当事方”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「SettlementService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can访问；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「SettlementService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「SettlementService.get」、「SettlementService.list」。
    // 下游影响：「SettlementService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「SettlementService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
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

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementService.compactUuid()」。
    // 具体功能：「SettlementService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「SettlementService.compactUuid()」的上游调用点包括 「SettlementService.propose」、「SettlementService.confirm」。
    // 下游影响：「SettlementService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「SettlementService.compactUuid()」负责主链路中的“UUID”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
