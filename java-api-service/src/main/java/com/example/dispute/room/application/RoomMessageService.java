/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排房间不可变消息与受众投递规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「post」、「list」、「ensureOpening」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「RoomMessageService」。
// 类型职责：编排房间不可变消息与受众投递规则、权限校验与事实读写；本类型显式提供 「RoomMessageService」、「post」、「list」、「ensureOpening」、「create」、「hearingRoundForPartyMessage」。
// 协作关系：主要由 「EvidenceSubmissionService.createSubmission」、「RoomController.list」、「RoomController.opening」、「RoomController.post」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RoomMessageService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseParticipantRepository participantRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final IntakeAgentTurnService intakeAgentTurnService;
    private final EvidenceAgentTurnService evidenceAgentTurnService;
    private final HearingRoundService hearingRoundService;
    private final AccessSessionResolver accessSessionResolver;
    private final SessionPermissionService permissionService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.RoomMessageService(FulfillmentCaseRepository,CaseRoomRepository,CaseParticipantRepository,RoomMessageRepository,CaseEventService,IntakeAgentTurnService,EvidenceAgentTurnService,HearingRoundService,AccessSessionResolver,SessionPermissionService,Clock)」。
    // 具体功能：「RoomMessageService.RoomMessageService(FulfillmentCaseRepository,CaseRoomRepository,CaseParticipantRepository,RoomMessageRepository,CaseEventService,IntakeAgentTurnService,EvidenceAgentTurnService,HearingRoundService,AccessSessionResolver,SessionPermissionService,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「participantRepository」(CaseParticipantRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「intakeAgentTurnService」(IntakeAgentTurnService)、「evidenceAgentTurnService」(EvidenceAgentTurnService)、「hearingRoundService」(HearingRoundService)、「accessSessionResolver」(AccessSessionResolver)、「permissionService」(SessionPermissionService)、「clock」(Clock) 并保存为「RoomMessageService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RoomMessageService.RoomMessageService(FulfillmentCaseRepository,CaseRoomRepository,CaseParticipantRepository,RoomMessageRepository,CaseEventService,IntakeAgentTurnService,EvidenceAgentTurnService,HearingRoundService,AccessSessionResolver,SessionPermissionService,Clock)」的上游创建点包括 「RoomMessageAndEventServiceTest.setUp」。
    // 下游影响：「RoomMessageService.RoomMessageService(FulfillmentCaseRepository,CaseRoomRepository,CaseParticipantRepository,RoomMessageRepository,CaseEventService,IntakeAgentTurnService,EvidenceAgentTurnService,HearingRoundService,AccessSessionResolver,SessionPermissionService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomMessageService.RoomMessageService(FulfillmentCaseRepository,CaseRoomRepository,CaseParticipantRepository,RoomMessageRepository,CaseEventService,IntakeAgentTurnService,EvidenceAgentTurnService,HearingRoundService,AccessSessionResolver,SessionPermissionService,Clock)」负责主链路中的“房间消息服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RoomMessageService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CaseParticipantRepository participantRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            IntakeAgentTurnService intakeAgentTurnService,
            EvidenceAgentTurnService evidenceAgentTurnService,
            HearingRoundService hearingRoundService,
            AccessSessionResolver accessSessionResolver,
            SessionPermissionService permissionService,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.intakeAgentTurnService = intakeAgentTurnService;
        this.evidenceAgentTurnService = evidenceAgentTurnService;
        this.hearingRoundService = hearingRoundService;
        this.accessSessionResolver = accessSessionResolver;
        this.permissionService = permissionService;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.post(String,RoomType,RoomMessageCommand,AuthenticatedActor,String,String)」。
    // 具体功能：「RoomMessageService.post(String,RoomType,RoomMessageCommand,AuthenticatedActor,String,String)」：构建事务后：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常，再在读取或写入房间前校验参与关系和角色权限；实际协作者为 「caseRepository.findByIdForUpdate」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「permissionService.require」，最终返回「RoomMessageView」。
    // 上游调用：「RoomMessageService.post(String,RoomType,RoomMessageCommand,AuthenticatedActor,String,String)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」、「RoomController.post」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」。
    // 下游影响：「RoomMessageService.post(String,RoomType,RoomMessageCommand,AuthenticatedActor,String,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「permissionService.require」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RoomMessageService.post(String,RoomType,RoomMessageCommand,AuthenticatedActor,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public RoomMessageView post(
            String caseId,
            RoomType roomType,
            RoomMessageCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.require(accessSession, PermissionScope.ROOM_MESSAGE_WRITE);
        assertCanPost(dispute, roomType, actor, command.messageType());
        CaseRoomEntity requestedRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        return messageRepository
                .findByCaseIdAndIdempotencyKey(caseId, idempotencyKey)
                .map(
                        existing -> {
                            assertSameImmutableRequest(
                                    existing, requestedRoom, command, actor);
                            return view(existing);
                        })
                .orElseGet(
                        () ->
                                create(
                                        dispute,
                                        requestedRoom,
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.list(String,RoomType,AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.list(String,RoomType,AuthenticatedActor)」：列出列表：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常，再在读取或写入房间前校验参与关系和角色权限；实际协作者为 「caseRepository.findById」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「permissionService.require」，最终返回「List<RoomMessageView>」。
    // 上游调用：「RoomMessageService.list(String,RoomType,AuthenticatedActor)」的上游调用点包括 「RoomController.list」、「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping」、「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole」、「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty」。
    // 下游影响：「RoomMessageService.list(String,RoomType,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「permissionService.require」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RoomMessageService.list(String,RoomType,AuthenticatedActor)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<RoomMessageView> list(
            String caseId, RoomType roomType, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.require(accessSession, PermissionScope.ROOM_MESSAGE_READ);
        assertCanRead(dispute, actor);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        if (roomType == RoomType.INTAKE
                && isParty(actor.role())
                && !isIntakeInitiator(dispute, actor)) {
            return List.of();
        }
        return messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId())
                .stream()
                .filter(message -> visibleTo(message, accessSession))
                .map(this::view)
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」。
    // 具体功能：「RoomMessageService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」：确保开场消息：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「evidenceAgentTurnService.ensureOpeningOrStart」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「Object」。
    // 上游调用：「RoomMessageService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」的上游调用点包括 「RoomController.opening」、「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage」。
    // 下游影响：「RoomMessageService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」向下依次触达 「evidenceAgentTurnService.ensureOpeningOrStart」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RoomMessageService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public Object ensureOpening(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        if (roomType == RoomType.EVIDENCE) {
            return evidenceAgentTurnService.ensureOpeningOrStart(
                    caseId, roomType, actor, traceId, requestId);
        }
        throw new IllegalArgumentException("room opening is not supported for " + roomType);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.create(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor,String,String)」。
    // 具体功能：「RoomMessageService.create(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor,String,String)」：创建房间消息：先把新状态写入 PostgreSQL 事实表；实际协作者为 「messageRepository.findMaxSequenceByRoomId」、「hearingRoundService.recordPartyMessageSubmission」、「intakeAgentTurnService.continueFromParticipantMessage」、「evidenceAgentTurnService.continueFromParticipantMessage」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「MESSAGE_」，最终返回「RoomMessageView」。
    // 上游调用：「RoomMessageService.create(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor,String,String)」的上游调用点包括 「RoomMessageService.post」。
    // 下游影响：「RoomMessageService.create(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor,String,String)」向下依次触达 「messageRepository.findMaxSequenceByRoomId」、「hearingRoundService.recordPartyMessageSubmission」、「intakeAgentTurnService.continueFromParticipantMessage」、「evidenceAgentTurnService.continueFromParticipantMessage」；计算结果以「RoomMessageView」交给调用方。
    // 系统意义：「RoomMessageService.create(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor,String,String)」负责主链路中的“房间消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private RoomMessageView create(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            RoomMessageCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        if (room.getRoomStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException("room is not open");
        }
        String audienceJson = audience(room.getRoomType(), actor.role(), command.messageType());
        String audienceActorIdsJson =
                audienceActorIds(room.getRoomType(), actor, command.messageType());
        Integer hearingRound = hearingRoundForPartyMessage(dispute, room, command, actor);
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity message =
                RoomMessageEntity.create(
                        "MESSAGE_" + compactUuid(),
                        dispute.getId(),
                        room.getId(),
                        sequence,
                        senderType(actor),
                        actor.role().name(),
                        actor.actorId(),
                        audienceJson,
                        audienceActorIdsJson,
                        command.messageType(),
                        command.text(),
                        json(command.attachmentRefs()),
                        idempotencyKey,
                        hearingRound,
                        clock.instant(),
                        traceId);
        if (hearingRound != null && command.messageType() == MessageType.PARTY_TEXT) {
            hearingRoundService.recordPartyMessageSubmission(
                    dispute.getId(),
                    hearingRound,
                    message.getId(),
                    message.getMessageText(),
                    actor);
        }
        AgentRunAcceptedView accepted = intakeAgentTurnService.continueFromParticipantMessage(
                dispute.getId(),
                room.getRoomType(),
                actor,
                message,
                traceId,
                traceId);
        AgentRunAcceptedView evidenceRun = evidenceAgentTurnService.continueFromParticipantMessage(
                dispute.getId(),
                room.getRoomType(),
                actor,
                command,
                message.getId(),
                message.getCreatedAt(),
                traceId,
                traceId);
        if (accepted == null) {
            accepted = evidenceRun;
        } else if (evidenceRun != null) {
            throw new IllegalStateException("one room message cannot start two public agent runs");
        }
        if (accepted != null) {
            message.attachAgentRun(accepted.runId());
        }
        RoomMessageEntity saved = messageRepository.save(message);
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                audienceJson,
                audienceActorIdsJson,
                actor.actorId());
        return view(saved);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.hearingRoundForPartyMessage(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.hearingRoundForPartyMessage(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」：构建庭审轮次面向当事方消息；实际协作者为 「hearingRoundService.currentOpenRoundNoForPartyMessage」、「room.getRoomType」、「command.messageType」、「actor.role」，最终返回「Integer」。
    // 上游调用：「RoomMessageService.hearingRoundForPartyMessage(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.create」。
    // 下游影响：「RoomMessageService.hearingRoundForPartyMessage(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」向下依次触达 「hearingRoundService.currentOpenRoundNoForPartyMessage」、「room.getRoomType」、「command.messageType」、「actor.role」；计算结果以「Integer」交给调用方。
    // 系统意义：「RoomMessageService.hearingRoundForPartyMessage(FulfillmentCaseEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」负责主链路中的“庭审轮次面向当事方消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private Integer hearingRoundForPartyMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            RoomMessageCommand command,
            AuthenticatedActor actor) {
        if (room.getRoomType() != RoomType.HEARING
                || !isHearingPartyRoundMessage(command.messageType())
                || !isParty(actor.role())) {
            return null;
        }
        return hearingRoundService.currentOpenRoundNoForPartyMessage(dispute.getId(), actor);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.assertSameImmutableRequest(RoomMessageEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.assertSameImmutableRequest(RoomMessageEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」：断言相同Immutable请求；实际协作者为 「existing.getRoomId」、「requestedRoom.getId」、「existing.getSenderRole」、「actor.role」；不满足前置条件时抛出 「IdempotencyConflictException」，最终返回「void」。
    // 上游调用：「RoomMessageService.assertSameImmutableRequest(RoomMessageEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.post」。
    // 下游影响：「RoomMessageService.assertSameImmutableRequest(RoomMessageEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」向下依次触达 「existing.getRoomId」、「requestedRoom.getId」、「existing.getSenderRole」、「actor.role」。
    // 系统意义：「RoomMessageService.assertSameImmutableRequest(RoomMessageEntity,CaseRoomEntity,RoomMessageCommand,AuthenticatedActor)」在“相同Immutable请求”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void assertSameImmutableRequest(
            RoomMessageEntity existing,
            CaseRoomEntity requestedRoom,
            RoomMessageCommand command,
            AuthenticatedActor actor) {
        RoomMessageView existingView = view(existing);
        boolean sameRequest =
                existing.getRoomId().equals(requestedRoom.getId())
                        && existing.getSenderRole().equals(actor.role().name())
                        && existing.getSenderId().equals(actor.actorId())
                        && existing.getMessageType() == command.messageType()
                        && Objects.equals(existing.getMessageText(), command.text())
                        && existingView.attachmentRefs().equals(command.attachmentRefs());
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different room message");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.assertCanPost(FulfillmentCaseEntity,RoomType,AuthenticatedActor,MessageType)」。
    // 具体功能：「RoomMessageService.assertCanPost(FulfillmentCaseEntity,RoomType,AuthenticatedActor,MessageType)」：断言Can事务后；实际协作者为 「actor.role」、「assertCanRead」、「isParty」、「isIntakeInitiator」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「RoomMessageService.assertCanPost(FulfillmentCaseEntity,RoomType,AuthenticatedActor,MessageType)」的上游调用点包括 「RoomMessageService.post」。
    // 下游影响：「RoomMessageService.assertCanPost(FulfillmentCaseEntity,RoomType,AuthenticatedActor,MessageType)」向下依次触达 「actor.role」、「assertCanRead」、「isParty」、「isIntakeInitiator」。
    // 系统意义：「RoomMessageService.assertCanPost(FulfillmentCaseEntity,RoomType,AuthenticatedActor,MessageType)」在“Can事务后”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void assertCanPost(
            FulfillmentCaseEntity dispute,
            RoomType roomType,
            AuthenticatedActor actor,
            MessageType messageType) {
        assertCanRead(dispute, actor);
        if (roomType == RoomType.INTAKE
                && isParty(actor.role())
                && !isIntakeInitiator(dispute, actor)) {
            throw new ForbiddenException(
                    "only the intake initiator can post in the intake room");
        }
        boolean allowed =
                switch (actor.role()) {
                    case USER, MERCHANT ->
                            messageType == MessageType.PARTY_TEXT
                                    || messageType == MessageType.PARTY_EVIDENCE_REFERENCE
                                    || messageType == MessageType.PARTY_CONFIRMATION;
                    case PLATFORM_REVIEWER -> messageType == MessageType.REVIEWER_NOTE;
                    case CUSTOMER_SERVICE, ADMIN, SYSTEM ->
                            messageType == MessageType.SYSTEM_EVENT
                                    || messageType == MessageType.AGENT_MESSAGE;
                };
        if (!allowed) {
            throw new ForbiddenException("message type is not allowed for actor");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」、「dispute.getUserId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「RoomMessageService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.list」、「RoomMessageService.assertCanPost」。
    // 下游影响：「RoomMessageService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」、「dispute.getUserId」。
    // 系统意义：「RoomMessageService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void assertCanRead(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot access room");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.view(RoomMessageEntity)」。
    // 具体功能：「RoomMessageService.view(RoomMessageEntity)」：解析视图；实际协作者为 「objectMapper.readValue」、「entity.getAttachmentRefsJson」、「entity.getId」、「entity.getCaseId」；不满足前置条件时抛出 「IllegalStateException」，最终返回「RoomMessageView」。
    // 上游调用：「RoomMessageService.view(RoomMessageEntity)」的上游调用点包括 「RoomMessageService.post」、「RoomMessageService.create」、「RoomMessageService.assertSameImmutableRequest」。
    // 下游影响：「RoomMessageService.view(RoomMessageEntity)」向下依次触达 「objectMapper.readValue」、「entity.getAttachmentRefsJson」、「entity.getId」、「entity.getCaseId」；计算结果以「RoomMessageView」交给调用方。
    // 系统意义：「RoomMessageService.view(RoomMessageEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private RoomMessageView view(RoomMessageEntity entity) {
        try {
            List<String> attachments =
                    objectMapper.readValue(
                            entity.getAttachmentRefsJson(), new TypeReference<>() {});
            return new RoomMessageView(
                    entity.getId(),
                    entity.getCaseId(),
                    entity.getRoomId(),
                    entity.getSequenceNo(),
                    entity.getSenderRole(),
                    entity.getSenderId(),
                    entity.getMessageType(),
                    entity.getMessageText(),
                    attachments,
                    entity.getAgentRunId(),
                    entity.getHearingRound(),
                    entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message attachments", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.visibleTo(RoomMessageEntity,CaseAccessSessionEntity)」。
    // 具体功能：「RoomMessageService.visibleTo(RoomMessageEntity,CaseAccessSessionEntity)」：判断可见性房间不可变消息与受众投递；实际协作者为 「permissionService.canReadActorAudience」、「accessSession.getActorRole」、「objectMapper.readValue」、「message.getAudienceJson」；不满足前置条件时抛出 「IllegalStateException」，最终返回「boolean」。
    // 上游调用：「RoomMessageService.visibleTo(RoomMessageEntity,CaseAccessSessionEntity)」的上游调用点包括 「RoomMessageService.list」。
    // 下游影响：「RoomMessageService.visibleTo(RoomMessageEntity,CaseAccessSessionEntity)」向下依次触达 「permissionService.canReadActorAudience」、「accessSession.getActorRole」、「objectMapper.readValue」、「message.getAudienceJson」；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomMessageService.visibleTo(RoomMessageEntity,CaseAccessSessionEntity)」负责主链路中的“房间不可变消息与受众投递”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private boolean visibleTo(RoomMessageEntity message, CaseAccessSessionEntity accessSession) {
        if (accessSession.getActorRole() == ActorRole.ADMIN
                || accessSession.getActorRole() == ActorRole.SYSTEM) {
            return true;
        }
        try {
            List<String> audiences =
                    objectMapper.readValue(
                            message.getAudienceJson(), new TypeReference<>() {});
            if (!audiences.isEmpty() && !audiences.contains(accessSession.getActorRole().name())) {
                return false;
            }
            List<String> audienceActorIds =
                    objectMapper.readValue(
                            message.getAudienceActorIdsJson(), new TypeReference<>() {});
            return permissionService.canReadActorAudience(accessSession, audienceActorIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message audience", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.audience(RoomType,ActorRole,MessageType)」。
    // 具体功能：「RoomMessageService.audience(RoomType,ActorRole,MessageType)」：构建受众 JSON；实际协作者为 「json」、「isParty」、「isEvidencePrivatePartyMessage」，最终返回「String」。
    // 上游调用：「RoomMessageService.audience(RoomType,ActorRole,MessageType)」的上游调用点包括 「RoomMessageService.create」。
    // 下游影响：「RoomMessageService.audience(RoomType,ActorRole,MessageType)」向下依次触达 「json」、「isParty」、「isEvidencePrivatePartyMessage」；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageService.audience(RoomType,ActorRole,MessageType)」负责主链路中的“受众 JSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String audience(RoomType roomType, ActorRole senderRole, MessageType messageType) {
        if (messageType == MessageType.REVIEWER_NOTE) {
            return json(
                    List.of(
                            ActorRole.PLATFORM_REVIEWER.name(),
                            ActorRole.ADMIN.name()));
        }
        if (roomType == RoomType.EVIDENCE
                && isParty(senderRole)
                && isEvidencePrivatePartyMessage(messageType)) {
            return json(
                    List.of(
                            senderRole.name(),
                            ActorRole.CUSTOMER_SERVICE.name(),
                            ActorRole.PLATFORM_REVIEWER.name(),
                            ActorRole.ADMIN.name(),
                            ActorRole.SYSTEM.name()));
        }
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.audienceActorIds(RoomType,AuthenticatedActor,MessageType)」。
    // 具体功能：「RoomMessageService.audienceActorIds(RoomType,AuthenticatedActor,MessageType)」：构建受众 JSON操作者Ids；实际协作者为 「sender.role」、「sender.actorId」、「isParty」、「json」；处理的关键状态/协议值包括 「[]」，最终返回「String」。
    // 上游调用：「RoomMessageService.audienceActorIds(RoomType,AuthenticatedActor,MessageType)」的上游调用点包括 「RoomMessageService.create」。
    // 下游影响：「RoomMessageService.audienceActorIds(RoomType,AuthenticatedActor,MessageType)」向下依次触达 「sender.role」、「sender.actorId」、「isParty」、「json」；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageService.audienceActorIds(RoomType,AuthenticatedActor,MessageType)」负责主链路中的“受众 JSON操作者Ids”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String audienceActorIds(
            RoomType roomType, AuthenticatedActor sender, MessageType messageType) {
        if (roomType == RoomType.INTAKE && isParty(sender.role())) {
            return json(List.of(sender.actorId()));
        }
        if (roomType == RoomType.EVIDENCE
                && isParty(sender.role())
                && isEvidencePrivatePartyMessage(messageType)) {
            return json(List.of(sender.actorId()));
        }
        return "[]";
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.json(Object)」。
    // 具体功能：「RoomMessageService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「RoomMessageService.json(Object)」的上游调用点包括 「RoomMessageService.create」、「RoomMessageService.audience」、「RoomMessageService.audienceActorIds」。
    // 下游影响：「RoomMessageService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize room message", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.senderType(AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.senderType(AuthenticatedActor)」：发送sender类型；实际协作者为 「actor.role」，最终返回「MessageSenderType」。
    // 上游调用：「RoomMessageService.senderType(AuthenticatedActor)」的上游调用点包括 「RoomMessageService.create」。
    // 下游影响：「RoomMessageService.senderType(AuthenticatedActor)」向下依次触达 「actor.role」；计算结果以「MessageSenderType」交给调用方。
    // 系统意义：「RoomMessageService.senderType(AuthenticatedActor)」负责主链路中的“sender类型”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static MessageSenderType senderType(AuthenticatedActor actor) {
        return switch (actor.role()) {
            case USER, MERCHANT -> MessageSenderType.PARTY;
            case PLATFORM_REVIEWER -> MessageSenderType.REVIEWER;
            case SYSTEM -> MessageSenderType.SYSTEM;
            case CUSTOMER_SERVICE, ADMIN -> MessageSenderType.AGENT;
        };
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.isParty(ActorRole)」。
    // 具体功能：「RoomMessageService.isParty(ActorRole)」：判断是否当事方，最终返回「boolean」。
    // 上游调用：「RoomMessageService.isParty(ActorRole)」的上游调用点包括 「RoomMessageService.list」、「RoomMessageService.hearingRoundForPartyMessage」、「RoomMessageService.assertCanPost」、「RoomMessageService.audience」。
    // 下游影响：「RoomMessageService.isParty(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomMessageService.isParty(ActorRole)」负责主链路中的“当事方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.isEvidencePrivatePartyMessage(MessageType)」。
    // 具体功能：「RoomMessageService.isEvidencePrivatePartyMessage(MessageType)」：判断是否证据私有当事方消息，最终返回「boolean」。
    // 上游调用：「RoomMessageService.isEvidencePrivatePartyMessage(MessageType)」的上游调用点包括 「RoomMessageService.audience」、「RoomMessageService.audienceActorIds」。
    // 下游影响：「RoomMessageService.isEvidencePrivatePartyMessage(MessageType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomMessageService.isEvidencePrivatePartyMessage(MessageType)」负责主链路中的“证据私有当事方消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isEvidencePrivatePartyMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.isHearingPartyRoundMessage(MessageType)」。
    // 具体功能：「RoomMessageService.isHearingPartyRoundMessage(MessageType)」：判断是否庭审当事方轮次消息，最终返回「boolean」。
    // 上游调用：「RoomMessageService.isHearingPartyRoundMessage(MessageType)」的上游调用点包括 「RoomMessageService.hearingRoundForPartyMessage」。
    // 下游影响：「RoomMessageService.isHearingPartyRoundMessage(MessageType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomMessageService.isHearingPartyRoundMessage(MessageType)」负责主链路中的“庭审当事方轮次消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isHearingPartyRoundMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.isIntakeInitiator(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「RoomMessageService.isIntakeInitiator(FulfillmentCaseEntity,AuthenticatedActor)」：判断是否接待发起方；实际协作者为 「actor.role」、「dispute.getInitiatorRole」、「actor.actorId」、「dispute.getUserId」，最终返回「boolean」。
    // 上游调用：「RoomMessageService.isIntakeInitiator(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「RoomMessageService.list」、「RoomMessageService.assertCanPost」。
    // 下游影响：「RoomMessageService.isIntakeInitiator(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「dispute.getInitiatorRole」、「actor.actorId」、「dispute.getUserId」；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomMessageService.isIntakeInitiator(FulfillmentCaseEntity,AuthenticatedActor)」负责主链路中的“接待发起方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isIntakeInitiator(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (actor.role() != dispute.getInitiatorRole()) {
            return false;
        }
        return switch (actor.role()) {
            case USER -> actor.actorId().equals(dispute.getUserId());
            case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
            default -> false;
        };
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageService.compactUuid()」。
    // 具体功能：「RoomMessageService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「RoomMessageService.compactUuid()」的上游调用点包括 「RoomMessageService.create」。
    // 下游影响：「RoomMessageService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageService.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
