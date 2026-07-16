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
import com.example.dispute.common.exception.ForbiddenException;
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
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final DisputeProperties disputeProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        this.disputeProperties = disputeProperties;
        this.clock = clock;
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
        if (confirmationRole == dispute.getInitiatorRole()
                && intakeProgressService.isCompleted(dispute, confirmationRole)) {
            return new IntakeConfirmationView(
                    caseId,
                    dispute.getCaseStatus(),
                    dispute.getCaseStatus() == com.example.dispute.domain.model.CaseStatus.EVIDENCE_OPEN
                            ? RoomType.EVIDENCE
                            : RoomType.INTAKE,
                    dispute.getCurrentDeadlineAt());
        }
        if (confirmationRole != dispute.getInitiatorRole()) {
            return confirmRespondent(dispute, intakeRoom, actor, command, now);
        }

        if (!command.admissible()) {
            intakeRoom.close(now, actor.actorId());
            roomRepository.save(intakeRoom);
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
        intakeProgressService.completeInitiator(dispute, actor, now);
        String acceptedIntakeResultJson = acceptedIntakeResultJson(dispute);
        dispute.completeIntake(
                command.disputeType(),
                com.example.dispute.domain.model.CaseStatus.INTAKE_COMPLETED,
                command.riskLevel(),
                acceptedIntakeResultJson,
                actor.actorId());
        caseRepository.save(dispute);
        caseEventService.recordLifecycleEvent(
                caseId,
                intakeRoom.getId(),
                "INITIATOR_INTAKE_COMPLETED",
                Map.of("case_status", dispute.getCaseStatus().name()),
                "intake-confirmed:" + caseId,
                actor.actorId());
        sendCounterpartySummons(dispute, actor, null);
        return new IntakeConfirmationView(
                caseId,
                dispute.getCaseStatus(),
                RoomType.INTAKE,
                null);
    }

    private IntakeConfirmationView confirmRespondent(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            IntakeConfirmationCommand command,
            OffsetDateTime now) {
        if (!command.admissible()) {
            throw new BadRequestException(
                    "respondent cannot change the intake admissibility decision",
                    Map.of("case_id", dispute.getId()));
        }
        if (intakeProgressService.isCompleted(dispute, actor.role())) {
            return new IntakeConfirmationView(
                    dispute.getId(),
                    dispute.getCaseStatus(),
                    RoomType.EVIDENCE,
                    dispute.getCurrentDeadlineAt());
        }
        String finalIntakeResultJson = acceptedIntakeResultJson(dispute);
        assertBilateralMatrixReady(dispute.getId(), finalIntakeResultJson);
        intakeProgressService.completeRespondent(dispute, actor, now);
        participantService.inviteBoth(dispute, actor, now);
        Duration evidenceWindow = disputeProperties.evidenceWindow();
        OffsetDateTime deadline = now.plus(evidenceWindow);
        CaseRoomEntity evidenceRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                CaseRoomEntity.open(
                                                        roomId(),
                                                        dispute.getId(),
                                                        RoomType.EVIDENCE,
                                                        now,
                                                        actor.actorId())));
        phaseClockRepository.save(
                CasePhaseClockEntity.running(
                        clockId(),
                        dispute.getId(),
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        now,
                        deadline,
                        "evidence-window-" + dispute.getId(),
                        actor.actorId()));
        intakeRoom.close(now, actor.actorId());
        roomRepository.save(intakeRoom);
        dispute.admitToEvidence(
                command.disputeType(),
                command.riskLevel(),
                finalIntakeResultJson,
                deadline,
                actor.actorId());
        caseRepository.save(dispute);
        caseEventService.recordLifecycleEvent(
                dispute.getId(),
                intakeRoom.getId(),
                "RESPONDENT_INTAKE_COMPLETED",
                Map.of(
                        "case_status", dispute.getCaseStatus().name(),
                        "deadline_at", deadline.toString(),
                        "respondent_role", actor.role().name()),
                "respondent-intake-completed:" + dispute.getId(),
                actor.actorId());
        caseEventService.recordLifecycleEvent(
                dispute.getId(),
                evidenceRoom.getId(),
                "EVIDENCE_OPENED",
                Map.of(
                        "case_status", dispute.getCaseStatus().name(),
                        "deadline_at", deadline.toString(),
                        "matrix_kind", "BILATERAL_FROZEN"),
                "evidence-opened-after-bilateral-intake:" + dispute.getId(),
                actor.actorId());
        lifecycleNotifications.evidenceRoomOpened(dispute, deadline);
        evidenceWindowCoordinator.startAfterCommit(dispute.getId(), evidenceWindow);
        return new IntakeConfirmationView(
                dispute.getId(),
                dispute.getCaseStatus(),
                RoomType.EVIDENCE,
                deadline);
    }

    private void assertBilateralMatrixReady(String caseId, String intakeResultJson) {
        try {
            JsonNode matrix =
                    objectMapper.readTree(intakeResultJson).path("case_fact_matrix");
            if ("case_fact_matrix.v2".equals(
                            matrix.path("schema_version").asText())
                    && "BILATERAL_FROZEN".equals(
                            matrix.path("matrix_kind").asText())) {
                return;
            }
        } catch (JsonProcessingException ignored) {
            // The business error below is stable for malformed and incomplete dossiers.
        }
        throw new BadRequestException(
                "respondent must complete the bilateral intake matrix before entering evidence",
                Map.of("case_id", caseId, "required_matrix_kind", "BILATERAL_FROZEN"));
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

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.acceptedIntakeResultJson(FulfillmentCaseEntity)」。
    // 具体功能：「IntakeRoomService.acceptedIntakeResultJson(FulfillmentCaseEntity)」：构建接待结果JSON；实际协作者为 「intakeDossierRepository.findByCaseIdAndRoomType」、「dispute.getId」、「dossier.getDossierJson」、「dispute.getIntakeResultJson」，最终返回「String」。
    // 上游调用：「IntakeRoomService.acceptedIntakeResultJson(FulfillmentCaseEntity)」的上游调用点包括 「IntakeRoomService.confirm」。
    // 下游影响：「IntakeRoomService.acceptedIntakeResultJson(FulfillmentCaseEntity)」向下依次触达 「intakeDossierRepository.findByCaseIdAndRoomType」、「dispute.getId」、「dossier.getDossierJson」、「dispute.getIntakeResultJson」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeRoomService.acceptedIntakeResultJson(FulfillmentCaseEntity)」负责主链路中的“接待结果JSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String acceptedIntakeResultJson(FulfillmentCaseEntity dispute) {
        return intakeDossierRepository
                .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                .map(dossier -> dossier.getDossierJson())
                .filter(json -> json != null && !json.isBlank())
                .orElse(dispute.getIntakeResultJson());
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
        dispute.cancelIntake(actor.actorId(), now);
        caseRepository.save(dispute);
        caseEventService.recordLifecycleEvent(
                caseId,
                intakeRoom.getId(),
                "INTAKE_CANCELLED",
                Map.of(
                        "case_status",
                        dispute.getCaseStatus().name(),
                        "reason",
                        reason == null ? "" : reason),
                "intake-cancelled:" + caseId,
                actor.actorId());
        return new IntakeConfirmationView(caseId, dispute.getCaseStatus(), null, null);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.sendCounterpartySummons(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」。
    // 具体功能：「IntakeRoomService.sendCounterpartySummons(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」：发送CounterpartySummons；实际协作者为 「initiator.role」、「dispute.getMerchantId」、「dispute.getUserId」、「sendSummonsTo」，最终返回「void」。
    // 上游调用：「IntakeRoomService.sendCounterpartySummons(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」的上游调用点包括 「IntakeRoomService.confirm」。
    // 下游影响：「IntakeRoomService.sendCounterpartySummons(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」向下依次触达 「initiator.role」、「dispute.getMerchantId」、「dispute.getUserId」、「sendSummonsTo」。
    // 系统意义：「IntakeRoomService.sendCounterpartySummons(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」负责主链路中的“CounterpartySummons”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.sendSummonsTo(FulfillmentCaseEntity,String,ActorRole,OffsetDateTime)」。
    // 具体功能：「IntakeRoomService.sendSummonsTo(FulfillmentCaseEntity,String,ActorRole,OffsetDateTime)」：发送Summons；实际协作者为 「notificationService.send」、「dispute.getId」；处理的关键状态/协议值包括 「:intake-accepted」、「争议审理传票」、「订单争议已受理，请在两小时内进入证据书记官室。」、「{\"deadline_at\":\」，最终返回「void」。
    // 上游调用：「IntakeRoomService.sendSummonsTo(FulfillmentCaseEntity,String,ActorRole,OffsetDateTime)」的上游调用点包括 「IntakeRoomService.sendCounterpartySummons」。
    // 下游影响：「IntakeRoomService.sendSummonsTo(FulfillmentCaseEntity,String,ActorRole,OffsetDateTime)」向下依次触达 「notificationService.send」、「dispute.getId」。
    // 系统意义：「IntakeRoomService.sendSummonsTo(FulfillmentCaseEntity,String,ActorRole,OffsetDateTime)」负责主链路中的“Summons”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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
                        "案情接待通知",
                        "对方已完成案情接待，请先进入接待室独立补充你的陈述。双方陈述完成后，系统才会统一开放证据室。",
                        "/disputes/" + dispute.getId() + "/intake",
                        deadline == null
                                ? "{}"
                                : "{\"deadline_at\":\"" + deadline + "\"}"));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.roomId()」。
    // 具体功能：「IntakeRoomService.roomId()」：构建房间标识；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「ROOM_」、「-」，最终返回「String」。
    // 上游调用：「IntakeRoomService.roomId()」的上游调用点包括 「IntakeRoomService.confirm」、「IntakeRoomService.cancel」。
    // 下游影响：「IntakeRoomService.roomId()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeRoomService.roomId()」负责主链路中的“房间标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String roomId() {
        return "ROOM_" + UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeRoomService.clockId()」。
    // 具体功能：「IntakeRoomService.clockId()」：构建时钟标识；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「CLOCK_」、「-」，最终返回「String」。
    // 上游调用：「IntakeRoomService.clockId()」的上游调用点包括 「IntakeRoomService.confirm」。
    // 下游影响：「IntakeRoomService.clockId()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeRoomService.clockId()」负责主链路中的“时钟标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String clockId() {
        return "CLOCK_" + UUID.randomUUID().toString().replace("-", "");
    }
}
