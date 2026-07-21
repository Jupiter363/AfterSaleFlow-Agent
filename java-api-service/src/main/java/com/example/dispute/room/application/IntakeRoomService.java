/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排接待室受理确认和下一阶段开放规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「confirm」、「cancel」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.casecore.domain.CasePartyPosition;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeRoomService」。
// 类型职责：编排接待室受理确认和下一阶段开放规则、权限校验与事实读写；本类型显式提供 「IntakeRoomService」、「confirm」、「acceptedIntakeResultJson」、「cancel」、「sendCounterpartySummons」、「sendSummonsTo」。
// 协作关系：主要由 「IntakeRoomController.cancel」、「IntakeRoomController.confirm」、「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission」、「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class IntakeRoomService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository phaseClockRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final IntakeProgressService intakeProgressService;
    private final ParticipantService participantService;
    private final NotificationService notificationService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final EvidenceWindowCoordinator evidenceWindowCoordinator;
    private final CaseEventService caseEventService;
    private final RoomEpochAllocator roomEpochAllocator;
    private final DisputeProperties disputeProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IntakeBranchDomainService branchDomainService;

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.IntakeRoomService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,CaseIntakeDossierRepository,ParticipantService,NotificationService,CaseLifecycleNotificationService,EvidenceWindowCoordinator,CaseEventService,DisputeProperties,Clock)」。
    // 具体功能：「IntakeRoomService.IntakeRoomService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,CaseIntakeDossierRepository,ParticipantService,NotificationService,CaseLifecycleNotificationService,EvidenceWindowCoordinator,CaseEventService,DisputeProperties,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「phaseClockRepository」(CasePhaseClockRepository)、「intakeDossierRepository」(CaseIntakeDossierRepository)、「participantService」(ParticipantService)、「notificationService」(NotificationService)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「evidenceWindowCoordinator」(EvidenceWindowCoordinator)、「caseEventService」(CaseEventService)、「disputeProperties」(DisputeProperties)、「clock」(Clock) 并保存为「IntakeRoomService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「IntakeRoomService.IntakeRoomService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,CaseIntakeDossierRepository,ParticipantService,NotificationService,CaseLifecycleNotificationService,EvidenceWindowCoordinator,CaseEventService,DisputeProperties,Clock)」的上游创建点包括 「IntakeRoomServiceTest.setUp」。
    // 下游影响：「IntakeRoomService.IntakeRoomService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,CaseIntakeDossierRepository,ParticipantService,NotificationService,CaseLifecycleNotificationService,EvidenceWindowCoordinator,CaseEventService,DisputeProperties,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeRoomService.IntakeRoomService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,CaseIntakeDossierRepository,ParticipantService,NotificationService,CaseLifecycleNotificationService,EvidenceWindowCoordinator,CaseEventService,DisputeProperties,Clock)」负责主链路中的“接待房间服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public IntakeRoomService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository phaseClockRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            IntakeProgressService intakeProgressService,
            ParticipantService participantService,
            NotificationService notificationService,
            CaseLifecycleNotificationService lifecycleNotifications,
            EvidenceWindowCoordinator evidenceWindowCoordinator,
            CaseEventService caseEventService,
            RoomEpochAllocator roomEpochAllocator,
            DisputeProperties disputeProperties,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.phaseClockRepository = phaseClockRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.intakeProgressService = intakeProgressService;
        this.participantService = participantService;
        this.notificationService = notificationService;
        this.lifecycleNotifications = lifecycleNotifications;
        this.evidenceWindowCoordinator = evidenceWindowCoordinator;
        this.caseEventService = caseEventService;
        this.roomEpochAllocator = roomEpochAllocator;
        this.disputeProperties = disputeProperties;
        this.clock = clock;
        this.branchDomainService =
                new IntakeBranchDomainService(
                        caseRepository,
                        roomRepository,
                        phaseClockRepository,
                        intakeDossierRepository,
                        intakeProgressService,
                        participantService,
                        notificationService,
                        lifecycleNotifications,
                        evidenceWindowCoordinator,
                        caseEventService,
                        disputeProperties,
                        objectMapper);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.confirm(String,AuthenticatedActor,IntakeConfirmationCommand)」。
    // 具体功能：「IntakeRoomService.confirm(String,AuthenticatedActor,IntakeConfirmationCommand)」：确认接待Confirmation：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「participantService.addInitiator」；处理的关键状态/协议值包括 「case_id」、「INTAKE_REJECTED」、「case_status」、「intake-confirmed:」，最终返回「IntakeConfirmationView」。
    // 上游调用：「IntakeRoomService.confirm(String,AuthenticatedActor,IntakeConfirmationCommand)」的上游调用点包括 「IntakeRoomController.confirm」、「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi」、「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」。
    // 下游影响：「IntakeRoomService.confirm(String,AuthenticatedActor,IntakeConfirmationCommand)」向下依次触达 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「participantService.addInitiator」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「IntakeRoomService.confirm(String,AuthenticatedActor,IntakeConfirmationCommand)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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
        ActorRole confirmationRole = confirmationRole(dispute, actor);
        if (confirmationRole != dispute.getInitiatorRole() && !command.admissible()) {
            throw new BadRequestException(
                    "respondent cannot change the intake admissibility decision",
                    Map.of("case_id", dispute.getId()));
        }
        if (intakeProgressService.isCompleted(dispute, confirmationRole)) {
            assertCompletedReplayState(dispute, confirmationRole);
            return completedReplay(dispute);
        }
        assertIntakeActionAllowed(dispute);
        CaseRoomEntity intakeRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                CaseRoomEntity.open(
                                                        roomId(),
                                                        caseId,
                                                        RoomType.INTAKE,
                                                        now,
                                                        actor.actorId())));
        assertOpenIntakeRoom(dispute, intakeRoom);
        ensureIntakeEpoch(dispute, intakeRoom, now);
        if (confirmationRole != dispute.getInitiatorRole()) {
            IntakeBranchDomainService.BranchResult result = branchDomainService
                    .confirmRespondent(dispute, intakeRoom, actor, command, now);
            roomEpochAllocator.transition(
                    new TransitionRoomEpoch(
                            dispute.getId(),
                            ContractTypes.RoomType.INTAKE,
                            result.evidenceRoomId(),
                            ContractTypes.RoomType.EVIDENCE,
                            dispute.getCaseStatus().name(),
                            RoomStatus.OPEN.name(),
                            result.view().deadlineAt(),
                            now));
            return result.view();
        }

        if (!command.admissible()) {
            IntakeConfirmationView result = branchDomainService
                    .rejectInitiator(dispute, intakeRoom, actor, command, now)
                    .view();
            terminateIntakeEpoch(dispute, now);
            return result;
        }

        return branchDomainService.acceptInitiator(dispute, intakeRoom, actor, command, now).view();
    }

    private static ActorRole confirmationRole(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            return dispute.getInitiatorRole();
        }
        return dispute.partyAssignment()
                .resolve(actor.actorId(), actor.role())
                .map(
                        position ->
                                position == CasePartyPosition.INITIATOR
                                        ? dispute.getInitiatorRole()
                                        : dispute.getRespondentRole())
                .orElseThrow(() -> new SecurityException("actor is not a case party"));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.cancel(String,AuthenticatedActor,String)」。
    // 具体功能：「IntakeRoomService.cancel(String,AuthenticatedActor,String)」：判断能否cancel：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「caseRepository.save」；处理的关键状态/协议值包括 「case_id」、「INTAKE_CANCELLED」、「case_status」、「reason」，最终返回「IntakeConfirmationView」。
    // 上游调用：「IntakeRoomService.cancel(String,AuthenticatedActor,String)」的上游调用点包括 「IntakeRoomController.cancel」、「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission」、「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence」。
    // 下游影响：「IntakeRoomService.cancel(String,AuthenticatedActor,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「caseRepository.save」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「IntakeRoomService.cancel(String,AuthenticatedActor,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public IntakeConfirmationView cancel(
            String caseId,
            AuthenticatedActor actor,
            String reason) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        if (confirmationRole(dispute, actor) != dispute.getInitiatorRole()) {
            throw new ForbiddenException("only the intake initiator can cancel the dispute");
        }
        assertIntakeActionAllowed(dispute);
        OffsetDateTime now = OffsetDateTime.now(clock);
        CaseRoomEntity intakeRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                CaseRoomEntity.open(
                                                        roomId(),
                                                        caseId,
                                                        RoomType.INTAKE,
                                                        now,
                                                        actor.actorId())));
        assertOpenIntakeRoom(dispute, intakeRoom);
        ensureIntakeEpoch(dispute, intakeRoom, now);
        IntakeConfirmationView result =
                branchDomainService.cancel(dispute, intakeRoom, actor, reason, now).view();
        terminateIntakeEpoch(dispute, now);
        return result;
    }

    private void assertCompletedReplayState(
            FulfillmentCaseEntity dispute, ActorRole confirmationRole) {
        if (confirmationRole != dispute.getInitiatorRole()
                && RoomType.INTAKE.name().equals(dispute.getCurrentRoom())) {
            throw invalidIntakeState(dispute, "respondent completion has no committed room transition");
        }
    }

    private static IntakeConfirmationView completedReplay(FulfillmentCaseEntity dispute) {
        return new IntakeConfirmationView(
                dispute.getId(),
                dispute.getCaseStatus(),
                roomTypeOrNull(dispute.getCurrentRoom()),
                dispute.getCurrentDeadlineAt());
    }

    private static RoomType roomTypeOrNull(String currentRoom) {
        if (currentRoom == null || currentRoom.isBlank()) {
            return null;
        }
        try {
            return RoomType.valueOf(currentRoom);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void assertIntakeActionAllowed(FulfillmentCaseEntity dispute) {
        boolean intakeStatus =
                dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_PENDING
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_IN_PROGRESS
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.WAITING_SLOT_COMPLETION
                        || dispute.getCaseStatus()
                                == com.example.dispute.domain.model.CaseStatus.INTAKE_COMPLETED;
        if (!intakeStatus
                || !RoomType.INTAKE.name().equals(dispute.getCurrentRoom())
                || dispute.getCurrentDeadlineAt() != null) {
            throw invalidIntakeState(dispute, "intake action is not allowed from the current case state");
        }
    }

    private static void assertOpenIntakeRoom(
            FulfillmentCaseEntity dispute, CaseRoomEntity intakeRoom) {
        if (intakeRoom.getRoomStatus() != RoomStatus.OPEN) {
            throw invalidIntakeState(dispute, "intake action requires an open intake room");
        }
    }

    private static BusinessException invalidIntakeState(
            FulfillmentCaseEntity dispute, String message) {
        return new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                message,
                Map.of(
                        "case_id", dispute.getId(),
                        "case_status", dispute.getCaseStatus().name(),
                        "current_room", String.valueOf(dispute.getCurrentRoom()),
                        "current_deadline_at", String.valueOf(dispute.getCurrentDeadlineAt())));
    }

    private void ensureIntakeEpoch(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            OffsetDateTime occurredAt) {
        roomEpochAllocator.activate(
                new ActivateRoomEpoch(
                        dispute.getId(),
                        intakeRoom.getId(),
                        ContractTypes.RoomType.INTAKE,
                        dispute.getCaseStatus().name(),
                        intakeRoom.getRoomStatus().name(),
                        dispute.getCurrentDeadlineAt(),
                        occurredAt));
    }

    private void terminateIntakeEpoch(
            FulfillmentCaseEntity dispute, OffsetDateTime occurredAt) {
        roomEpochAllocator.terminate(
                new TerminateRoomEpoch(
                        dispute.getId(),
                        ContractTypes.RoomType.INTAKE,
                        dispute.getCaseStatus().name(),
                        "CLOSED",
                        occurredAt));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.roomId()」。
    // 具体功能：「IntakeRoomService.roomId()」：构建房间标识；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「ROOM_」、「-」，最终返回「String」。
    // 上游调用：「IntakeRoomService.roomId()」的上游调用点包括 「IntakeRoomService.confirm」、「IntakeRoomService.cancel」。
    // 下游影响：「IntakeRoomService.roomId()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeRoomService.roomId()」负责主链路中的“房间标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String roomId() {
        return "ROOM_" + UUID.randomUUID().toString().replace("-", "");
    }

}
