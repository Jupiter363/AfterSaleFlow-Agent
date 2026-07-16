/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排证据Agent轮次规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「continueFromParticipantMessage」、「supports」、「finalizeResult」、「ensureOpening」、「ensureOpeningOrStart」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunFinalizer;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceAgentTurnService」。
// 类型职责：编排证据Agent轮次规则、权限校验与事实读写；本类型显式提供 「EvidenceAgentTurnService」、「EvidenceAgentTurnService」、「continueFromParticipantMessage」、「startStreamingRun」、「supports」、「finalizeResult」。
// 协作关系：主要由 「RoomMessageService.create」、「RoomMessageService.ensureOpening」、「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceAgentTurnService implements AgentRunFinalizer {

    public static final String AGENT_ROLE = "EVIDENCE_CLERK";
    private static final String AGENT_SENDER_ROLE = "CUSTOMER_SERVICE";
    private static final String AGENT_SENDER_ID = "evidence-clerk";
    private static final String OPENING_IDEMPOTENCY_VERSION = "dossier-v3";
    private static final List<String> SUPERSEDED_GENERIC_OPENING_MARKERS =
            List.of(
                    "您好！我是您的证据书记官",
                    "请上传与本案相关的证据材料",
                    "争议焦点待确认",
                    "原始证据文件、证据形成时间、证据来源路径");
    private static final Logger log = LoggerFactory.getLogger(EvidenceAgentTurnService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final EvidenceDossierFreezer dossierFreezer;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final EvidenceContextEnvelopeFactory contextEnvelopeFactory;
    private final EvidenceAgentTurnClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private AgentRunCoordinator agentRunCoordinator;

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock)」。
    // 具体功能：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「evidenceItemRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository)、「dossierFreezer」(EvidenceDossierFreezer)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「accessSessionResolver」(AccessSessionResolver)、「agentSessionResolver」(AgentSessionResolver)、「permissionService」(SessionPermissionService)、「contextEnvelopeFactory」(EvidenceContextEnvelopeFactory)、「client」(EvidenceAgentTurnClient)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「EvidenceAgentTurnService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock)」的上游创建点包括 「EvidenceAgentTurnServiceTest.setUp」。
    // 下游影响：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock)」负责主链路中的“证据Agent轮次服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            EvidenceItemRepository evidenceItemRepository,
            EvidenceVerificationRepository verificationRepository,
            EvidenceDossierFreezer dossierFreezer,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            EvidenceContextEnvelopeFactory contextEnvelopeFactory,
            EvidenceAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.memoryRepository = memoryRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.verificationRepository = verificationRepository;
        this.dossierFreezer = dossierFreezer;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.accessSessionResolver = accessSessionResolver;
        this.agentSessionResolver = agentSessionResolver;
        this.permissionService = permissionService;
        this.contextEnvelopeFactory = contextEnvelopeFactory;
        this.client = client;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」。
    // 具体功能：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「evidenceItemRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository)、「dossierFreezer」(EvidenceDossierFreezer)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「accessSessionResolver」(AccessSessionResolver)、「agentSessionResolver」(AgentSessionResolver)、「permissionService」(SessionPermissionService)、「contextEnvelopeFactory」(EvidenceContextEnvelopeFactory)、「client」(EvidenceAgentTurnClient)、「objectMapper」(ObjectMapper)、「clock」(Clock)、「agentRunCoordinator」(AgentRunCoordinator) 并保存为「EvidenceAgentTurnService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」的上游创建点包括 「EvidenceAgentTurnServiceTest.setUp」。
    // 下游影响：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceAgentTurnService.EvidenceAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,EvidenceItemRepository,EvidenceVerificationRepository,EvidenceDossierFreezer,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,EvidenceContextEnvelopeFactory,EvidenceAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」负责主链路中的“证据Agent轮次服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public EvidenceAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            EvidenceItemRepository evidenceItemRepository,
            EvidenceVerificationRepository verificationRepository,
            EvidenceDossierFreezer dossierFreezer,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            EvidenceContextEnvelopeFactory contextEnvelopeFactory,
            EvidenceAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock,
            AgentRunCoordinator agentRunCoordinator) {
        this(
                caseRepository,
                roomRepository,
                memoryRepository,
                evidenceItemRepository,
                verificationRepository,
                dossierFreezer,
                messageRepository,
                eventService,
                accessSessionResolver,
                agentSessionResolver,
                permissionService,
                contextEnvelopeFactory,
                client,
                objectMapper,
                clock);
        this.agentRunCoordinator = agentRunCoordinator;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageCommand,String,Instant,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageCommand,String,Instant,String,String)」：先把参与方文本与附件引用写入私有会话记忆，组装角色可见证据、最新核验和卷宗版本上下文，再启动 EVIDENCE_TURN AgentRun；模型看不到无权访问的对方私密原件，最终返回「AgentRunAcceptedView」。
    // 上游调用：「EvidenceAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageCommand,String,Instant,String,String)」的上游调用点包括 「RoomMessageService.create」、「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」。
    // 下游影响：「EvidenceAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageCommand,String,Instant,String,String)」向下依次触达 「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.save」、「contextEnvelopeFactory.create」、「RoomTurnMemoryEntity.participantTurn」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageCommand,String,Instant,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentRunAcceptedView continueFromParticipantMessage(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            RoomMessageCommand message,
            String sourceMessageId,
            Instant sourceMessageCreatedAt,
            String traceId,
            String requestId) {
        if ((roomType != RoomType.EVIDENCE && roomType != RoomType.HEARING)
                || !isEvidenceTurnMessage(message.messageType())
                || (roomType == RoomType.HEARING
                        && message.messageType() != MessageType.PARTY_EVIDENCE_REFERENCE)
                || !isParty(actor.role())) {
            return null;
        }

        TurnContext context = prepare(caseId, roomType);
        SessionContext session = resolveSession(caseId, actor, RoomType.EVIDENCE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        // 文本和附件引用先写入该当事方的私有记忆；Agent 失败不能撤销已经接收的举证陈述。
        memoryRepository.save(
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_" + compactUuid(),
                        caseId,
                        RoomType.EVIDENCE,
                        turnNo,
                        actor.actorId(),
                        actor.role().name(),
                        participantMemoryContent(message),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));

        // EnvelopeFactory 根据 CaseAccessSession 裁剪证据原件、核验和历史消息。
        // Python 只能看到 envelope 中的内容，不能凭 evidenceId 自行读取 Java/MinIO。
        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        contextEnvelopeFactory.create(
                                context.dispute(),
                                context.room(),
                                actor,
                                session.accessSession(),
                                session.agentSession(),
                                "PARTY_MESSAGE",
                                sourceMessageId,
                                message.messageType(),
                                message.text(),
                                message.attachmentRefs(),
                                turnNo,
                                sourceMessageCreatedAt),
                        invocationContext(session, roomType));
        if (agentRunCoordinator != null) {
            return startStreamingRun(
                    context,
                    session,
                    turnNo,
                    actor,
                    command,
                    traceId,
                    requestId,
                    turnIdempotencyKey(
                            context.dispute(), session.agentSession(), actor.role(), turnNo));
        }
        EvidenceAgentTurnResult result = safeRun(command, traceId, requestId);
        persistAgentTurn(
                context,
                session,
                turnNo,
                actor.role(),
                message.attachmentRefs(),
                result,
                allowedFactIds(command.contextEnvelope()),
                traceId);
        return null;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,EvidenceAgentTurnCommand,String,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,EvidenceAgentTurnCommand,String,String,String)」：把 EvidenceContextEnvelope、当事方受众和 prompt profile 固化到 AgentRunStartCommand，使用案件/会话/轮次组成的幂等键交给统一流式协调器，最终返回「AgentRunAcceptedView」。
    // 上游调用：「EvidenceAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,EvidenceAgentTurnCommand,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,EvidenceAgentTurnCommand,String,String,String)」向下依次触达 「agentRunCoordinator.start」、「context.room」、「actor.role」、「context.dispute」；计算结果以「AgentRunAcceptedView」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,EvidenceAgentTurnCommand,String,String,String)」负责主链路中的“Streaming运行”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private AgentRunAcceptedView startStreamingRun(
            TurnContext context,
            SessionContext session,
            int turnNo,
            AuthenticatedActor actor,
            EvidenceAgentTurnCommand command,
            String traceId,
            String requestId,
            String idempotencyKey) {
        boolean hearingSupplement = context.room().getRoomType() == RoomType.HEARING;
        // 证据室对话只面向当前当事方；庭审补证属于共享法庭事件，受众才扩展到双方和平台角色。
        List<String> roles =
                hearingSupplement
                        ? List.of(
                                ActorRole.USER.name(),
                                ActorRole.MERCHANT.name(),
                                ActorRole.CUSTOMER_SERVICE.name(),
                                ActorRole.PLATFORM_REVIEWER.name(),
                                ActorRole.ADMIN.name(),
                                ActorRole.SYSTEM.name())
                        : List.of(
                                actor.role().name(),
                                ActorRole.CUSTOMER_SERVICE.name(),
                                ActorRole.PLATFORM_REVIEWER.name(),
                                ActorRole.ADMIN.name(),
                                ActorRole.SYSTEM.name());
        return agentRunCoordinator.start(
                new AgentRunStartCommand(
                        context.dispute().getId(),
                        context.room().getId(),
                        "EVIDENCE_TURN",
                        objectMapper.valueToTree(command),
                        roles,
                        hearingSupplement ? List.of() : List.of(actor.actorId()),
                        idempotencyKey,
                        traceId,
                        requestId,
                        actor.actorId()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.supports(String)」。
    // 具体功能：「EvidenceAgentTurnService.supports(String)」：判断是否支持；处理的关键状态/协议值包括 「EVIDENCE_TURN」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.supports(String)」由使用「EvidenceAgentTurnService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceAgentTurnService.supports(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.supports(String)」负责主链路中的“”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @Override
    public boolean supports(String operation) {
        return "EVIDENCE_TURN".equals(operation);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「EvidenceAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」：作为 EVIDENCE_TURN Finalizer 校验持久化 envelope 的案件、房间、会话和受众，核对 Agent assessment 覆盖范围后写核验记录、记忆、房间回复；庭审补证还会冻结新卷宗版本，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」由使用「EvidenceAgentTurnService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」向下依次触达 「objectMapper.convertValue」、「finalization.request」、「command.contextEnvelope」、「envelope.actorSnapshot」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Override
    @Transactional
    public void finalizeResult(AgentRunFinalizationContext finalization, JsonNode rawResult) {
        EvidenceAgentTurnCommand command =
                objectMapper.convertValue(finalization.request(), EvidenceAgentTurnCommand.class);
        EvidenceAgentTurnResult result =
                objectMapper.convertValue(rawResult, EvidenceAgentTurnResult.class);
        EvidenceContextEnvelopeV1 envelope = command.contextEnvelope();
        ActorRole audienceParty = ActorRole.valueOf(envelope.actorSnapshot().actorRole());
        AuthenticatedActor actor =
                new AuthenticatedActor(envelope.actorSnapshot().actorId(), audienceParty);
        SessionContext session = resolveSession(finalization.caseId(), actor, RoomType.EVIDENCE);
        // 使用当前数据库会话复核 envelope 快照，拒绝把迟到结果写入已变化或被撤销的授权范围。
        if (!session.agentSession().getId().equals(envelope.actorSnapshot().agentSessionId())
                || !session.accessSession().getId().equals(
                        envelope.actorSnapshot().accessSessionId())) {
            throw new IllegalStateException("evidence agent session changed before finalization");
        }
        RoomType actualRoom = envelope.roomPolicy().roomType();
        TurnContext context = prepare(finalization.caseId(), actualRoom);
        int turnNo = envelope.currentEvent().turnNo();
        if ("ROOM_OPENING".equals(envelope.currentEvent().eventType())) {
            persistOpeningAgentTurn(
                    context,
                    session,
                    audienceParty,
                    turnNo,
                    result,
                    finalization.runId(),
                    finalization.traceId(),
                    finalization.idempotencyKey());
            return;
        }
        // 普通回合会继续校验 assessment 只能覆盖当前可见和本轮附件，
        // 随后在同一事务中保存核验版本、Agent 记忆和房间消息。
        persistAgentTurn(
                context,
                session,
                turnNo,
                audienceParty,
                envelope.currentEvent().attachmentRefs(),
                result,
                finalization.runId(),
                allowedFactIds(envelope),
                finalization.traceId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」：为证据书记官生成一次开场回合并用 openingIdempotencyKey 去重，保存 Agent 记忆和房间消息；已有开场直接返回，最终返回「RoomMessageView」。
    // 上游调用：「EvidenceAgentTurnService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」的上游调用点包括 「EvidenceAgentTurnService.ensureOpeningOrStart」、「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」、「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening」、「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening」。
    // 下游影响：「EvidenceAgentTurnService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「contextEnvelopeFactory.create」、「actor.role」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceAgentTurnService.ensureOpening(String,RoomType,AuthenticatedActor,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public RoomMessageView ensureOpening(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        if (roomType != RoomType.EVIDENCE) {
            throw new IllegalArgumentException("opening is only supported for evidence room");
        }
        if (!isParty(actor.role())) {
            throw new IllegalArgumentException("evidence opening is only created for party actors");
        }

        TurnContext context = prepare(caseId, RoomType.EVIDENCE);
        SessionContext session = resolveSession(caseId, actor, RoomType.EVIDENCE);
        String idempotencyKey = openingIdempotencyKey(caseId, session.agentSession());
        var existing =
                messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        List<RoomMessageEntity> visibleConversation =
                visibleActorScopedConversationMessages(context.room(), session.accessSession());
        if (!visibleConversation.isEmpty()
                && !isOnlySupersededOpeningMessages(visibleConversation)) {
            return view(visibleConversation.getFirst());
        }

        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        contextEnvelopeFactory.create(
                                context.dispute(),
                                context.room(),
                                actor,
                                session.accessSession(),
                                session.agentSession(),
                                "ROOM_OPENING",
                                "EVIDENCE_OPENING_" + turnNo,
                                MessageType.AGENT_MESSAGE,
                                null,
                                List.of(),
                                turnNo,
                                clock.instant()),
                        session.agentContext());
        EvidenceAgentTurnResult result = safeRun(command, traceId, requestId);
        RoomMessageEntity saved =
                persistOpeningAgentTurn(
                        context,
                        session,
                        actor.role(),
                        turnNo,
                        result,
                        "EVIDENCE_RUN_" + compactUuid(),
                        traceId,
                        idempotencyKey);
        return view(saved);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.ensureOpeningOrStart(String,RoomType,AuthenticatedActor,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.ensureOpeningOrStart(String,RoomType,AuthenticatedActor,String,String)」：若开场消息已存在则返回消息；否则在支持流式协调器时创建 AgentRun，兼容模式才同步生成，保证 API 不会同时启动两条书记官开场链路，最终返回「Object」。
    // 上游调用：「EvidenceAgentTurnService.ensureOpeningOrStart(String,RoomType,AuthenticatedActor,String,String)」的上游调用点包括 「RoomMessageService.ensureOpening」。
    // 下游影响：「EvidenceAgentTurnService.ensureOpeningOrStart(String,RoomType,AuthenticatedActor,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「contextEnvelopeFactory.create」、「actor.role」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceAgentTurnService.ensureOpeningOrStart(String,RoomType,AuthenticatedActor,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public Object ensureOpeningOrStart(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        if (agentRunCoordinator == null) {
            return ensureOpening(caseId, roomType, actor, traceId, requestId);
        }
        if (roomType != RoomType.EVIDENCE || !isParty(actor.role())) {
            throw new IllegalArgumentException("evidence opening requires a party actor");
        }
        TurnContext context = prepare(caseId, RoomType.EVIDENCE);
        SessionContext session = resolveSession(caseId, actor, RoomType.EVIDENCE);
        String idempotencyKey = openingIdempotencyKey(caseId, session.agentSession());
        var existing = messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existing.isPresent()) {
            return view(existing.orElseThrow());
        }
        var acceptedOpening =
                agentRunCoordinator.findAcceptedByIdempotencyKey(
                        caseId, idempotencyKey, "EVIDENCE_TURN");
        if (acceptedOpening.isPresent()) {
            return acceptedOpening.orElseThrow();
        }
        List<RoomMessageEntity> visibleConversation =
                visibleActorScopedConversationMessages(context.room(), session.accessSession());
        if (!visibleConversation.isEmpty()
                && !isOnlySupersededOpeningMessages(visibleConversation)) {
            return view(visibleConversation.getFirst());
        }
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        contextEnvelopeFactory.create(
                                context.dispute(),
                                context.room(),
                                actor,
                                session.accessSession(),
                                session.agentSession(),
                                "ROOM_OPENING",
                                "EVIDENCE_OPENING_" + turnNo,
                                MessageType.AGENT_MESSAGE,
                                null,
                                List.of(),
                                turnNo,
                                clock.instant()),
                        session.agentContext());
        return startStreamingRun(
                context,
                session,
                turnNo,
                actor,
                command,
                traceId,
                requestId,
                idempotencyKey);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.persistOpeningAgentTurn(TurnContext,SessionContext,ActorRole,int,EvidenceAgentTurnResult,String,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.persistOpeningAgentTurn(TurnContext,SessionContext,ActorRole,int,EvidenceAgentTurnResult,String,String,String)」：持久化开场消息Agent轮次：先把新状态写入 PostgreSQL 事实表；实际协作者为 「memoryRepository.save」、「RoomTurnMemoryEntity.agentTurn」、「context.dispute」、「result.roomUtterance」；处理的关键状态/协议值包括 「MEMORY_」、「{}」，最终返回「RoomMessageEntity」。
    // 上游调用：「EvidenceAgentTurnService.persistOpeningAgentTurn(TurnContext,SessionContext,ActorRole,int,EvidenceAgentTurnResult,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.finalizeResult」、「EvidenceAgentTurnService.ensureOpening」。
    // 下游影响：「EvidenceAgentTurnService.persistOpeningAgentTurn(TurnContext,SessionContext,ActorRole,int,EvidenceAgentTurnResult,String,String,String)」向下依次触达 「memoryRepository.save」、「RoomTurnMemoryEntity.agentTurn」、「context.dispute」、「result.roomUtterance」；计算结果以「RoomMessageEntity」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.persistOpeningAgentTurn(TurnContext,SessionContext,ActorRole,int,EvidenceAgentTurnResult,String,String,String)」负责主链路中的“开场消息Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private RoomMessageEntity persistOpeningAgentTurn(
            TurnContext context,
            SessionContext session,
            ActorRole audienceParty,
            int turnNo,
            EvidenceAgentTurnResult result,
            String runId,
            String traceId,
            String idempotencyKey) {
        String memorySnapshotJson = json(defaultObject(result.memoryPatch()));
        memoryRepository.save(
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_" + compactUuid(),
                        context.dispute().getId(),
                        RoomType.EVIDENCE,
                        turnNo,
                        AGENT_SENDER_ID,
                        AGENT_ROLE,
                        result.roomUtterance(),
                        memorySnapshotJson,
                        memorySnapshotJson,
                        json(defaultArray(result.canvasOperations())),
                        runId,
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        return appendAgentMessage(
                        context.dispute(),
                        context.room(),
                        session.agentSession(),
                        audienceParty,
                        result.roomUtterance(),
                        turnNo,
                        traceId,
                        idempotencyKey,
                        runId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」。
    // 具体功能：「EvidenceAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」：先校验案件房间读取权和当前角色的举证权限，再取得 EVIDENCE_PARTY_PRIVATE Agent 会话，使用户与商家的证据对话记忆、附件可见范围彼此隔离，最终返回「SessionContext」。
    // 上游调用：「EvidenceAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.finalizeResult」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」向下依次触达 「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「permissionService.requireEvidenceSubmit」、「agentSessionResolver.resolve」；计算结果以「SessionContext」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」负责主链路中的“会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private SessionContext resolveSession(
            String caseId, AuthenticatedActor actor, RoomType roomType) {
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.requireEvidenceSubmit(accessSession);
        AgentConversationSessionEntity agentSession =
                agentSessionResolver.resolve(
                        accessSession,
                        roomType,
                        AGENT_ROLE,
                        promptProfileId(actor.role()),
                        "MEMEO_DEFAULT");
        return new SessionContext(
                accessSession,
                agentSession,
                AgentInvocationContext.partyPrivate(
                        accessSession,
                        agentSession,
                        roomType,
                        "EVIDENCE_PARTY_PRIVATE"));
    }

    private static AgentInvocationContext invocationContext(
            SessionContext session, RoomType processingRoom) {
        // Keep the party's evidence-session memory while binding the request to its physical room.
        return AgentInvocationContext.partyPrivate(
                session.accessSession(),
                session.agentSession(),
                processingRoom,
                "EVIDENCE_PARTY_PRIVATE");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.prepare(String,RoomType)」。
    // 具体功能：「EvidenceAgentTurnService.prepare(String,RoomType)」：准备轮次上下文：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「room.getRoomStatus」；不满足前置条件时抛出 「IllegalStateException」，最终返回「TurnContext」。
    // 上游调用：「EvidenceAgentTurnService.prepare(String,RoomType)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.finalizeResult」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.prepare(String,RoomType)」向下依次触达 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「room.getRoomStatus」；计算结果以「TurnContext」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.prepare(String,RoomType)」负责主链路中的“轮次上下文”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private TurnContext prepare(String caseId, RoomType roomType) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        if (room.getRoomStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException("evidence room is not open");
        }
        return new TurnContext(dispute, room);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.safeRun(EvidenceAgentTurnCommand,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.safeRun(EvidenceAgentTurnCommand,String,String)」：生成安全值运行；实际协作者为 「client.run」、「result.roomUtterance」、「result.liabilityDetermined」、「result.remedyRecommended」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「trace_id」、「request_id」，最终返回「EvidenceAgentTurnResult」。
    // 上游调用：「EvidenceAgentTurnService.safeRun(EvidenceAgentTurnCommand,String,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」。
    // 下游影响：「EvidenceAgentTurnService.safeRun(EvidenceAgentTurnCommand,String,String)」向下依次触达 「client.run」、「result.roomUtterance」、「result.liabilityDetermined」、「result.remedyRecommended」；计算结果以「EvidenceAgentTurnResult」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.safeRun(EvidenceAgentTurnCommand,String,String)」负责主链路中的“运行”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private EvidenceAgentTurnResult safeRun(
            EvidenceAgentTurnCommand command, String traceId, String requestId) {
        try {
            EvidenceAgentTurnResult result = client.run(command, traceId, requestId);
            if (result == null
                    || blank(result.roomUtterance())
                    || result.liabilityDetermined()
                    || result.remedyRecommended()) {
                throw new AgentExecutionException(
                        ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                        "evidence agent returned empty or unsafe output",
                        Map.of("trace_id", traceId, "request_id", requestId));
            }
            return result;
        } catch (AgentExecutionException failure) {
            logAgentFailure(command, traceId, requestId, failure);
            throw failure;
        } catch (RuntimeException failure) {
            logAgentFailure(command, traceId, requestId, failure);
            throw new AgentExecutionException(
                    ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                    "evidence agent request failed",
                    Map.of("trace_id", traceId, "request_id", requestId));
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.logAgentFailure(EvidenceAgentTurnCommand,String,String,RuntimeException)」。
    // 具体功能：「EvidenceAgentTurnService.logAgentFailure(EvidenceAgentTurnCommand,String,String,RuntimeException)」：执行logAgent失败；实际协作者为 「log.warn」、「command.contextEnvelope」、「command.contextEnvelope().caseSnapshot().caseId」、「command.contextEnvelope().caseSnapshot」，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.logAgentFailure(EvidenceAgentTurnCommand,String,String,RuntimeException)」的上游调用点包括 「EvidenceAgentTurnService.safeRun」。
    // 下游影响：「EvidenceAgentTurnService.logAgentFailure(EvidenceAgentTurnCommand,String,String,RuntimeException)」向下依次触达 「log.warn」、「command.contextEnvelope」、「command.contextEnvelope().caseSnapshot().caseId」、「command.contextEnvelope().caseSnapshot」。
    // 系统意义：「EvidenceAgentTurnService.logAgentFailure(EvidenceAgentTurnCommand,String,String,RuntimeException)」负责主链路中的“logAgent失败”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void logAgentFailure(
            EvidenceAgentTurnCommand command,
            String traceId,
            String requestId,
            RuntimeException failure) {
        log.warn(
                "Evidence agent turn failed closed after agent call failure: case_id={}, room_type={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
                command.contextEnvelope().caseSnapshot().caseId(),
                command.contextEnvelope().roomPolicy().roomType(),
                traceId,
                requestId,
                failure.getClass().getName(),
                failure.getMessage(),
                failure);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String)」。
    // 具体功能：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String)」：提供「persistAgentTurn」的便捷重载：接收 「context」(TurnContext)、「session」(SessionContext)、「turnNo」(int)、「audienceParty」(ActorRole)、「currentAttachmentRefs」(List)、「result」(EvidenceAgentTurnResult)、「traceId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.finalizeResult」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String)」向下依次触达 「persistAgentTurn」、「compactUuid」。
    // 系统意义：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String)」负责主链路中的“Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            ActorRole audienceParty,
            List<String> currentAttachmentRefs,
            EvidenceAgentTurnResult result,
            Set<String> allowedFactIds,
            String traceId) {
        persistAgentTurn(
                context,
                session,
                turnNo,
                audienceParty,
                currentAttachmentRefs,
                result,
                "EVIDENCE_RUN_" + compactUuid(),
                allowedFactIds,
                traceId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String,String)」：持久化Agent轮次：先把新状态写入 PostgreSQL 事实表；实际协作者为 「memoryRepository.save」、「RoomTurnMemoryEntity.agentTurn」、「context.dispute」、「session.accessSession」；处理的关键状态/协议值包括 「MEMORY_」、「{}」，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.finalizeResult」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String,String)」向下依次触达 「memoryRepository.save」、「RoomTurnMemoryEntity.agentTurn」、「context.dispute」、「session.accessSession」。
    // 系统意义：「EvidenceAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,ActorRole,List,EvidenceAgentTurnResult,String,String)」负责主链路中的“Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            ActorRole audienceParty,
            List<String> currentAttachmentRefs,
            EvidenceAgentTurnResult result,
            String runId,
            Set<String> allowedFactIds,
            String traceId) {
        Set<String> allowedAttachmentIds =
                validateEvidenceAssessmentCoverage(
                        context.dispute().getId(),
                        session.accessSession(),
                        currentAttachmentRefs,
                        result.evidenceAssessments(),
                        traceId);
        if (!allowedAttachmentIds.isEmpty() && allowedFactIds.isEmpty()) {
            throw new IllegalStateException(
                    "evidence result requires frozen case_fact_matrix.v2");
        }
        validateEvidenceFactReferences(
                result, allowedFactIds, allowedAttachmentIds, traceId);
        String memorySnapshotJson = json(defaultObject(result.memoryPatch()));
        memoryRepository.save(
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_" + compactUuid(),
                        context.dispute().getId(),
                        RoomType.EVIDENCE,
                        turnNo,
                        AGENT_SENDER_ID,
                        AGENT_ROLE,
                        result.roomUtterance(),
                        memorySnapshotJson,
                        memorySnapshotJson,
                        json(defaultArray(result.canvasOperations())),
                        runId,
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        appendAgentMessage(
                context.dispute(),
                context.room(),
                session.agentSession(),
                audienceParty,
                result.roomUtterance(),
                turnNo,
                traceId,
                turnIdempotencyKey(context.dispute(), session.agentSession(), audienceParty, turnNo),
                runId);
        persistEvidenceVerifications(
                context.dispute().getId(),
                allowedAttachmentIds,
                result,
                runId,
                traceId);
        if (context.room().getRoomType() == RoomType.HEARING
                && !allowedAttachmentIds.isEmpty()) {
            freezeHearingSupplementDossier(
                    context,
                    allowedAttachmentIds,
                    session.agentSession().getActorId());
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.freezeHearingSupplementDossier(TurnContext,Set,String)」。
    // 具体功能：「EvidenceAgentTurnService.freezeHearingSupplementDossier(TurnContext,Set,String)」：庭审补证回合完成后冻结下一卷宗版本，记录 previous/active version、轮次和新增证据 ID，使后续法官只读取版本化快照而不是可变证据集合，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.freezeHearingSupplementDossier(TurnContext,Set,String)」的上游调用点包括 「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.freezeHearingSupplementDossier(TurnContext,Set,String)」向下依次触达 「eventService.recordLifecycleEvent」、「String.join」、「dossierFreezer.targetVersion」、「context.dispute」。
    // 系统意义：「EvidenceAgentTurnService.freezeHearingSupplementDossier(TurnContext,Set,String)」负责主链路中的“庭审补证卷宗”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void freezeHearingSupplementDossier(
            TurnContext context, Set<String> evidenceIds, String actorId) {
        int nextVersion = dossierFreezer.targetVersion(context.dispute().getId());
        int previousVersion = nextVersion - 1;
        EvidenceDossierEntity active =
                dossierFreezer.freeze(context.dispute().getId(), nextVersion, AGENT_SENDER_ID);
        eventService.recordLifecycleEvent(
                context.dispute().getId(),
                context.room().getId(),
                "EVIDENCE_DOSSIER_REVISED",
                Map.of(
                        "previous_version", previousVersion,
                        "active_version", active.getDossierVersion(),
                        "updated_after_round", "HEARING_SUPPLEMENT",
                        "revision_reason", "HEARING_SUPPLEMENT_EVIDENCE_REVIEW",
                        "evidence_ids", sorted(evidenceIds),
                        "summary", "证据书记官已完成庭审补证复核，并更新证据证明矩阵。"),
                "hearing-supplement-dossier-revised:"
                        + active.getDossierVersion()
                        + ":"
                        + String.join("-", sorted(evidenceIds)),
                actorId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage(String,CaseAccessSessionEntity,List,List,String)」。
    // 具体功能：「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage(String,CaseAccessSessionEntity,List,List,String)」：把当前会话允许的附件 ID 与 Agent 返回的 assessment ID 比较，拒绝未知证据、重复评估或漏评当前附件；这是阻止模型越权评价不可见证据的最后校验，最终返回「Set<String>」。
    // 上游调用：「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage(String,CaseAccessSessionEntity,List,List,String)」的上游调用点包括 「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage(String,CaseAccessSessionEntity,List,List,String)」向下依次触达 「allowedAttachmentIds.addAll」、「allowedAttachmentIds.retainAll」、「assessment.evidenceId」、「unknownIds.removeAll」；计算结果以「Set<String>」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage(String,CaseAccessSessionEntity,List,List,String)」在“证据AssessmentCoverage”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private Set<String> validateEvidenceAssessmentCoverage(
            String caseId,
            CaseAccessSessionEntity accessSession,
            List<String> currentAttachmentRefs,
            List<EvidenceAgentTurnResult.EvidenceAssessment> assessments,
            String traceId) {
        // 先取当前角色“有权看见”的证据，再与本轮附件求交集。
        // 这一步防止调用方伪造附件引用，把对方私密证据间接送给模型评价。
        Set<String> allowedAttachmentIds = new HashSet<>();
        if (currentAttachmentRefs != null && !currentAttachmentRefs.isEmpty()) {
            allowedAttachmentIds.addAll(visibleEvidenceIds(caseId, accessSession));
            allowedAttachmentIds.retainAll(Set.copyOf(currentAttachmentRefs));
        }

        Set<String> assessmentIds = new HashSet<>();
        Set<String> duplicateIds = new HashSet<>();
        boolean hasBlankId = false;
        for (EvidenceAgentTurnResult.EvidenceAssessment assessment : assessments) {
            String evidenceId = assessment.evidenceId();
            if (blank(evidenceId)) {
                hasBlankId = true;
                continue;
            }
            if (!assessmentIds.add(evidenceId)) {
                duplicateIds.add(evidenceId);
            }
        }

        Set<String> unknownIds = new HashSet<>(assessmentIds);
        unknownIds.removeAll(allowedAttachmentIds);
        Set<String> missingIds = new HashSet<>(allowedAttachmentIds);
        missingIds.removeAll(assessmentIds);
        // 一对一覆盖是强契约：多评、漏评、空 ID 或重复 ID 均整轮失败，
        // 不接受“部分可用”结果，以免错误核验进入后续冻结卷宗。
        if (hasBlankId
                || !duplicateIds.isEmpty()
                || !unknownIds.isEmpty()
                || !missingIds.isEmpty()
                || assessments.size() != allowedAttachmentIds.size()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evidence agent assessments must cover each current attachment exactly once",
                    Map.of(
                            "trace_id", traceId,
                            "expected_evidence_ids", sorted(allowedAttachmentIds),
                            "assessment_evidence_ids", sorted(assessmentIds),
                            "duplicate_evidence_ids", sorted(duplicateIds),
                            "unknown_evidence_ids", sorted(unknownIds),
                            "missing_evidence_ids", sorted(missingIds),
                            "blank_evidence_id", hasBlankId));
        }
        return Set.copyOf(allowedAttachmentIds);
    }

    /**
     * Reads the only fact namespace that the evidence clerk may reference. The namespace is bound
     * to the persisted request envelope so a late AgentRun cannot silently switch to a newer or
     * unrelated dossier while finalizing.
     */
    private Set<String> allowedFactIds(EvidenceContextEnvelopeV1 envelope) {
        EvidenceContextEnvelopeV1.IntakeDossierSnapshot snapshot =
                envelope.intakeDossierSnapshot();
        JsonNode payload =
                snapshot == null || snapshot.payload() == null
                        ? objectMapper.createObjectNode()
                        : snapshot.payload();
        JsonNode matrix = payload.path("case_fact_matrix");
        if (!"case_fact_matrix.v2".equals(matrix.path("schema_version").asText())) {
            matrix = payload.path("unilateral_case_matrix");
        }
        JsonNode rows = matrix.path("fact_rows");
        boolean supported =
                "case_fact_matrix.v2".equals(matrix.path("schema_version").asText())
                        || "unilateral_case_matrix.v1"
                                .equals(matrix.path("schema_version").asText());
        if (!matrix.isObject()
                || !supported
                || !rows.isArray()
                || rows.isEmpty()) {
            return Set.of();
        }
        Set<String> factIds = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String factId = row.path("fact_id").asText("").trim();
            if (factId.isBlank() || !factIds.add(factId)) {
                throw new IllegalStateException(
                        "frozen case fact matrix contains invalid fact ids");
            }
        }
        return Set.copyOf(factIds);
    }

    /**
     * Revalidates Python's semantic evidence mapping at the Java persistence boundary. Model output
     * is never authoritative merely because it passed JSON deserialization.
     */
    private void validateEvidenceFactReferences(
            EvidenceAgentTurnResult result,
            Set<String> allowedFactIds,
            Set<String> allowedAttachmentIds,
            String traceId) {
        Map<String, Set<String>> linkedFactsByEvidence = new java.util.LinkedHashMap<>();
        for (EvidenceAgentTurnResult.EvidenceAssessment assessment :
                result.evidenceAssessments()) {
            Set<String> linkedFacts = new LinkedHashSet<>();
            Set<String> supportedFacts = new LinkedHashSet<>();
            for (Map<String, Object> link : assessment.factLinks()) {
                String factId = mapText(link, "fact_id");
                String relation = mapText(link, "relation");
                if (!allowedFactIds.contains(factId)
                        || !Set.of("SUPPORTS", "OPPOSES", "INCONCLUSIVE")
                                .contains(relation)
                        || !linkedFacts.add(factId)) {
                    throw invalidFactReference(
                            "evidence assessment contains an invalid or duplicate fact link",
                            traceId,
                            assessment.evidenceId(),
                            factId);
                }
                if ("SUPPORTS".equals(relation)) {
                    supportedFacts.add(factId);
                }
            }
            if (assessment.relevanceScore() >= 0.50 && linkedFacts.isEmpty()) {
                throw invalidFactReference(
                        "relevant evidence must reference at least one formal fact",
                        traceId,
                        assessment.evidenceId(),
                        "");
            }
            Set<String> suppliedSupportedFacts =
                    assessment.supportedFactIds().stream()
                            .filter(value -> value != null && !value.isBlank())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!supportedFacts.equals(suppliedSupportedFacts)
                    || !allowedFactIds.containsAll(suppliedSupportedFacts)) {
                throw invalidFactReference(
                        "supported_fact_ids must equal SUPPORTS fact_links",
                        traceId,
                        assessment.evidenceId(),
                        String.join(",", suppliedSupportedFacts));
            }
            linkedFactsByEvidence.put(assessment.evidenceId(), Set.copyOf(linkedFacts));
        }

        for (Map<String, Object> patch : result.factMatrixPatch()) {
            String factId = mapText(patch, "fact_id");
            String evidenceId = mapText(patch, "evidence_id");
            String operation = mapText(patch, "operation");
            if (!allowedFactIds.contains(factId)
                    || !allowedAttachmentIds.contains(evidenceId)
                    || !Set.of("UPSERT_LINK", "REMOVE_LINK").contains(operation)) {
                throw invalidFactReference(
                        "fact_matrix_patch references data outside the current formal scope",
                        traceId,
                        evidenceId,
                        factId);
            }
            if ("UPSERT_LINK".equals(operation)
                    && !linkedFactsByEvidence
                            .getOrDefault(evidenceId, Set.of())
                            .contains(factId)) {
                throw invalidFactReference(
                        "UPSERT_LINK must be derived from an accepted assessment fact link",
                        traceId,
                        evidenceId,
                        factId);
            }
        }

        Object uncovered = result.internalHandoff().get("uncovered_fact_ids");
        if (uncovered instanceof List<?> values) {
            for (Object value : values) {
                String factId = value == null ? "" : value.toString().trim();
                if (!allowedFactIds.contains(factId)) {
                    throw invalidFactReference(
                            "internal_handoff references an unknown fact",
                            traceId,
                            "",
                            factId);
                }
            }
        }
    }

    private AgentExecutionException invalidFactReference(
            String message, String traceId, String evidenceId, String factId) {
        return new AgentExecutionException(
                ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                message,
                Map.of(
                        "trace_id", safeText(traceId),
                        "evidence_id", safeText(evidenceId),
                        "fact_id", safeText(factId)));
    }

    private static String mapText(Map<String, Object> value, String field) {
        if (value == null) {
            return "";
        }
        Object raw = value.get(field);
        return raw == null ? "" : raw.toString().trim();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.persistEvidenceVerifications(String,Set,EvidenceAgentTurnResult,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.persistEvidenceVerifications(String,Set,EvidenceAgentTurnResult,String,String)」：持久化证据Verifications；实际协作者为 「persistEvidenceAssessments」，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.persistEvidenceVerifications(String,Set,EvidenceAgentTurnResult,String,String)」的上游调用点包括 「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.persistEvidenceVerifications(String,Set,EvidenceAgentTurnResult,String,String)」向下依次触达 「persistEvidenceAssessments」。
    // 系统意义：「EvidenceAgentTurnService.persistEvidenceVerifications(String,Set,EvidenceAgentTurnResult,String,String)」负责主链路中的“证据Verifications”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistEvidenceVerifications(
            String caseId,
            Set<String> allowedAttachmentIds,
            EvidenceAgentTurnResult result,
            String runId,
            String traceId) {
        if (allowedAttachmentIds.isEmpty()) {
            return;
        }
        persistEvidenceAssessments(
                caseId,
                result,
                runId,
                traceId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.persistEvidenceAssessments(String,EvidenceAgentTurnResult,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.persistEvidenceAssessments(String,EvidenceAgentTurnResult,String,String)」：为每份 assessment 追加单调递增核验版本，保存推荐状态、分数、理由、检查方法和 AgentRun 引用；NEEDS_HUMAN_REVIEW 不会被自动提升为已验证，最终返回「void」。
    // 上游调用：「EvidenceAgentTurnService.persistEvidenceAssessments(String,EvidenceAgentTurnResult,String,String)」的上游调用点包括 「EvidenceAgentTurnService.persistEvidenceVerifications」。
    // 下游影响：「EvidenceAgentTurnService.persistEvidenceAssessments(String,EvidenceAgentTurnResult,String,String)」向下依次触达 「verificationRepository.save」、「EvidenceVerificationEntity.create」、「result.evidenceAssessments」、「assessment.evidenceId」。
    // 系统意义：「EvidenceAgentTurnService.persistEvidenceAssessments(String,EvidenceAgentTurnResult,String,String)」负责主链路中的“证据Assessments”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistEvidenceAssessments(
            String caseId,
            EvidenceAgentTurnResult result,
            String runId,
            String traceId) {
        for (EvidenceAgentTurnResult.EvidenceAssessment assessment : result.evidenceAssessments()) {
            int version = nextVerificationVersion(assessment.evidenceId());
            EvidenceAgentTurnResult.HumanReview humanReview = assessment.humanReview();
            EvidenceAssessmentReviewPolicy.Decision reviewDecision =
                    EvidenceAssessmentReviewPolicy.evaluate(
                            assessment.authenticityScore(),
                            assessment.relevanceScore(),
                            assessment.riskFlags());
            boolean suspectedForgery = reviewDecision.suspectedForgery();
            boolean lowRelevance = reviewDecision.lowRelevance();
            boolean requiresHumanReview =
                    humanReview.required()
                            || "NEEDS_HUMAN_REVIEW".equals(assessment.recommendation())
                            || reviewDecision.requiresHumanReview();
            List<Map<String, Object>> riskFlags =
                    new ArrayList<>(assessment.riskFlags());
            boolean hasSuspectedForgeryFlag =
                    riskFlags.stream()
                            .anyMatch(
                                    flag ->
                                            "SUSPECTED_FORGERY"
                                                    .equals(flag.get("code")));
            if (suspectedForgery && !hasSuspectedForgeryFlag) {
                riskFlags.add(
                        Map.of(
                                "code", "SUSPECTED_FORGERY",
                                "severity", "HIGH",
                                "message", "真实性评分低于疑似造假人工复核阈值"));
            }
            if (lowRelevance
                    && riskFlags.stream()
                            .noneMatch(flag -> "LOW_RELEVANCE".equals(flag.get("code")))) {
                riskFlags.add(
                        Map.of(
                                "code", "LOW_RELEVANCE",
                                "severity", "MEDIUM",
                                "message", "证据与提交方声明证明目标的相关性评分较低"));
            }
            List<String> humanReviewReasonCodes =
                    new ArrayList<>(humanReview.reasonCodes());
            if (suspectedForgery
                    && !humanReviewReasonCodes.contains(
                            "LOW_AUTHENTICITY_SUSPECTED_FORGERY")) {
                humanReviewReasonCodes.add("LOW_AUTHENTICITY_SUSPECTED_FORGERY");
            }
            if (lowRelevance
                    && !humanReviewReasonCodes.contains("LOW_RELEVANCE_SCORE")) {
                humanReviewReasonCodes.add("LOW_RELEVANCE_SCORE");
            }
            List<String> humanReviewInstructions =
                    new ArrayList<>(humanReview.instructions());
            if ((suspectedForgery || lowRelevance) && humanReviewInstructions.isEmpty()) {
                humanReviewInstructions.add(
                        "复核原始文件、形成链路、完整上下文及其与提交方声明证明目标的关联性；低分只触发人工复核，不得直接认定造假或触发处罚。");
            }
            Map<String, Object> agentFindings = new java.util.LinkedHashMap<>();
            agentFindings.put("agent_run_id", runId);
            agentFindings.put("analysis_method", assessment.analysisMethod());
            agentFindings.put("inspected_modalities", assessment.inspectedModalities());
            agentFindings.put("fact_links", assessment.factLinks());
            agentFindings.put("authenticity_score", assessment.authenticityScore());
            agentFindings.put("relevance_score", assessment.relevanceScore());
            agentFindings.put("completeness_score", assessment.completenessScore());
            agentFindings.put("assessment_confidence", assessment.assessmentConfidence());
            agentFindings.put(
                    "low_authenticity_threshold",
                    EvidenceAssessmentReviewPolicy.LOW_AUTHENTICITY_THRESHOLD);
            agentFindings.put(
                    "low_relevance_threshold",
                    EvidenceAssessmentReviewPolicy.LOW_RELEVANCE_THRESHOLD);
            agentFindings.put("suspected_forgery", suspectedForgery);
            agentFindings.put("low_relevance", lowRelevance);
            agentFindings.put("source_basis", assessment.sourceBasis());
            agentFindings.put("supported_fact_ids", assessment.supportedFactIds());
            agentFindings.put("unsupported_claims", assessment.unsupportedClaims());
            agentFindings.put(
                    "formation_time_assessment", assessment.formationTimeAssessment());
            agentFindings.put("confidence_score", assessment.assessmentConfidence());
            agentFindings.put(
                    "confidence_level", confidenceLevel(assessment.assessmentConfidence()));
            agentFindings.put("findings", assessment.findings());
            agentFindings.put("limitations", assessment.limitations());
            agentFindings.put("risk_flags", List.copyOf(riskFlags));
            agentFindings.put("asset_audit", assessment.assetAudit());
            agentFindings.put("recommendation", assessment.recommendation());
            agentFindings.put("verification_feedback", safeText(assessment.summary()));
            agentFindings.put("fact_matrix_patch", result.factMatrixPatch());
            agentFindings.put("human_review_tasks", result.humanReviewTasks());
            agentFindings.put("internal_handoff", result.internalHandoff());
            agentFindings.put(
                    "human_review",
                    Map.of(
                            "required", requiresHumanReview,
                            "reason_codes", List.copyOf(humanReviewReasonCodes),
                            "instructions", List.copyOf(humanReviewInstructions)));

            Map<String, Object> reasons = new java.util.LinkedHashMap<>();
            reasons.put("summary", safeText(assessment.summary()));
            reasons.put("limitations", assessment.limitations());
            reasons.put("risk_flags", List.copyOf(riskFlags));
            reasons.put("human_review_reason_codes", List.copyOf(humanReviewReasonCodes));
            reasons.put("human_review_instructions", List.copyOf(humanReviewInstructions));

            verificationRepository.save(
                    EvidenceVerificationEntity.create(
                            "VERIFY_" + compactUuid(),
                            caseId,
                            assessment.evidenceId(),
                            version,
                            verificationStatus(
                                    assessment, suspectedForgery, requiresHumanReview),
                            json(
                                    Map.of(
                                            "checks",
                                            List.of(
                                                    "authorized_current_event_attachment",
                                                    "multimodal_evidence_assessment"),
                                            "inspected_modalities",
                                            assessment.inspectedModalities())),
                            json(agentFindings),
                            json(reasons),
                            requiresHumanReview,
                            Instant.now(clock),
                            AGENT_SENDER_ID,
                            traceId));
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.visibleEvidenceIds(String,CaseAccessSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnService.visibleEvidenceIds(String,CaseAccessSessionEntity)」：判断可见性证据Ids；实际协作者为 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「Collectors.toCollection」、「evidenceVisibleToSession」，最终返回「Set<String>」。
    // 上游调用：「EvidenceAgentTurnService.visibleEvidenceIds(String,CaseAccessSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage」。
    // 下游影响：「EvidenceAgentTurnService.visibleEvidenceIds(String,CaseAccessSessionEntity)」向下依次触达 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「Collectors.toCollection」、「evidenceVisibleToSession」；计算结果以「Set<String>」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.visibleEvidenceIds(String,CaseAccessSessionEntity)」负责主链路中的“证据Ids”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private Set<String> visibleEvidenceIds(
            String caseId, CaseAccessSessionEntity accessSession) {
        return evidenceItemRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                .stream()
                .filter(item -> evidenceVisibleToSession(item, accessSession))
                .map(EvidenceItemEntity::getId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.nextVerificationVersion(String)」。
    // 具体功能：「EvidenceAgentTurnService.nextVerificationVersion(String)」：构建下一核验版本；实际协作者为 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」，最终返回「int」。
    // 上游调用：「EvidenceAgentTurnService.nextVerificationVersion(String)」的上游调用点包括 「EvidenceAgentTurnService.persistEvidenceAssessments」。
    // 下游影响：「EvidenceAgentTurnService.nextVerificationVersion(String)」向下依次触达 「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.nextVerificationVersion(String)」负责主链路中的“下一核验版本”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private int nextVerificationVersion(String evidenceId) {
        return verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidenceId)
                        .map(EvidenceVerificationEntity::getVerificationVersion)
                        .orElse(0)
                + 1;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.verificationStatus(EvidenceAssessment,boolean)」。
    // 具体功能：「EvidenceAgentTurnService.verificationStatus(EvidenceAssessment,boolean)」：构建核验状态；实际协作者为 「assessment.recommendation」；处理的关键状态/协议值包括 「SUSPICIOUS」，最终返回「EvidenceVerificationStatus」。
    // 上游调用：「EvidenceAgentTurnService.verificationStatus(EvidenceAssessment,boolean)」的上游调用点包括 「EvidenceAgentTurnService.persistEvidenceAssessments」。
    // 下游影响：「EvidenceAgentTurnService.verificationStatus(EvidenceAssessment,boolean)」向下依次触达 「assessment.recommendation」；计算结果以「EvidenceVerificationStatus」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.verificationStatus(EvidenceAssessment,boolean)」负责主链路中的“核验状态”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static EvidenceVerificationStatus verificationStatus(
            EvidenceAgentTurnResult.EvidenceAssessment assessment,
            boolean suspectedForgery,
            boolean requiresHumanReview) {
        if (suspectedForgery) {
            return EvidenceVerificationStatus.SUSPICIOUS;
        }
        if (requiresHumanReview) {
            return EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW;
        }
        return "SUSPICIOUS".equals(assessment.recommendation())
                ? EvidenceVerificationStatus.SUSPICIOUS
                : EvidenceVerificationStatus.PLAUSIBLE;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.evidenceVisibleToSession(EvidenceItemEntity,CaseAccessSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnService.evidenceVisibleToSession(EvidenceItemEntity,CaseAccessSessionEntity)」：判断证据可见会话；实际协作者为 「accessSession.privileged」、「accessSession.getActorRole」、「item.getSubmittedByRole」、「accessSession.getActorId」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.evidenceVisibleToSession(EvidenceItemEntity,CaseAccessSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.visibleEvidenceIds」。
    // 下游影响：「EvidenceAgentTurnService.evidenceVisibleToSession(EvidenceItemEntity,CaseAccessSessionEntity)」向下依次触达 「accessSession.privileged」、「accessSession.getActorRole」、「item.getSubmittedByRole」、「accessSession.getActorId」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.evidenceVisibleToSession(EvidenceItemEntity,CaseAccessSessionEntity)」负责主链路中的“证据可见会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private boolean evidenceVisibleToSession(
            EvidenceItemEntity item, CaseAccessSessionEntity accessSession) {
        if (accessSession.privileged()) {
            return true;
        }
        return accessSession.getActorRole().name().equals(item.getSubmittedByRole())
                && accessSession.getActorId().equals(item.getSubmittedById())
                && item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.confidenceLevel(double)」。
    // 具体功能：「EvidenceAgentTurnService.confidenceLevel(double)」：构建可信度级别；处理的关键状态/协议值包括 「HIGH」、「MEDIUM」、「LOW」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.confidenceLevel(double)」的上游调用点包括 「EvidenceAgentTurnService.persistEvidenceAssessments」。
    // 下游影响：「EvidenceAgentTurnService.confidenceLevel(double)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.confidenceLevel(double)」负责主链路中的“可信度级别”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String confidenceLevel(double score) {
        if (score >= 0.8) {
            return "HIGH";
        }
        if (score >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.safeText(String)」。
    // 具体功能：「EvidenceAgentTurnService.safeText(String)」：生成安全值文本，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.safeText(String)」的上游调用点包括 「EvidenceAgentTurnService.persistEvidenceAssessments」。
    // 下游影响：「EvidenceAgentTurnService.safeText(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.safeText(String)」负责主链路中的“文本”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.sorted(Set)」。
    // 具体功能：「EvidenceAgentTurnService.sorted(Set)」：把无序证据 ID 集合转换为字典序不可变列表，使错误详情、事件幂等键和测试快照在不同 JVM 运行中保持稳定，最终返回「List<String>」。
    // 上游调用：「EvidenceAgentTurnService.sorted(Set)」的上游调用点包括 「EvidenceAgentTurnService.freezeHearingSupplementDossier」、「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage」。
    // 下游影响：「EvidenceAgentTurnService.sorted(Set)」向下依次触达 「values.stream().sorted」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.sorted(Set)」负责主链路中的“sorted”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,ActorRole,String,int,String,String,String)」。
    // 具体功能：「EvidenceAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,ActorRole,String,int,String,String,String)」：追加Agent消息：先把新状态写入 PostgreSQL 事实表；实际协作者为 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」；处理的关键状态/协议值包括 「[]」、「MESSAGE_」，最终返回「RoomMessageEntity」。
    // 上游调用：「EvidenceAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,ActorRole,String,int,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,ActorRole,String,int,String,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」；计算结果以「RoomMessageEntity」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,ActorRole,String,int,String,String,String)」负责主链路中的“Agent消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private RoomMessageEntity appendAgentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AgentConversationSessionEntity agentSession,
            ActorRole audienceParty,
            String utterance,
            int turnNo,
            String traceId,
            String idempotencyKey,
            String runId) {
        var existing =
                messageRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean hearingSupplement = room.getRoomType() == RoomType.HEARING;
        String audienceJson =
                hearingSupplement ? hearingSupplementAudienceJson() : audienceJson(audienceParty);
        String audienceActorIdsJson =
                hearingSupplement ? "[]" : json(List.of(agentSession.getActorId()));
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity message =
                RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                hearingSupplement ? AGENT_ROLE : AGENT_SENDER_ROLE,
                                AGENT_SENDER_ID,
                                audienceJson,
                                audienceActorIdsJson,
                                MessageType.AGENT_MESSAGE,
                                utterance,
                                "[]",
                                idempotencyKey,
                                Instant.now(clock),
                                traceId);
        message.attachAgentRun(runId);
        RoomMessageEntity saved = messageRepository.save(message);
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                audienceActorIdsJson,
                AGENT_SENDER_ID);
        return saved;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.visibleActorScopedConversationMessages(CaseRoomEntity,CaseAccessSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnService.visibleActorScopedConversationMessages(CaseRoomEntity,CaseAccessSessionEntity)」：判断可见性操作者Scoped会话Messages；实际协作者为 「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「room.getId」、「visibleToAccessSession」，最终返回「List<RoomMessageEntity>」。
    // 上游调用：「EvidenceAgentTurnService.visibleActorScopedConversationMessages(CaseRoomEntity,CaseAccessSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.visibleActorScopedConversationMessages(CaseRoomEntity,CaseAccessSessionEntity)」向下依次触达 「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「room.getId」、「visibleToAccessSession」；计算结果以「List<RoomMessageEntity>」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.visibleActorScopedConversationMessages(CaseRoomEntity,CaseAccessSessionEntity)」负责主链路中的“操作者Scoped会话Messages”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<RoomMessageEntity> visibleActorScopedConversationMessages(
            CaseRoomEntity room, CaseAccessSessionEntity accessSession) {
        return messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()).stream()
                .filter(message -> visibleToAccessSession(message, accessSession))
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.isOnlySupersededOpeningMessages(List)」。
    // 具体功能：「EvidenceAgentTurnService.isOnlySupersededOpeningMessages(List)」：判断是否OnlySuperseded开场消息Messages；实际协作者为 「visibleConversation.stream().allMatch」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.isOnlySupersededOpeningMessages(List)」的上游调用点包括 「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.isOnlySupersededOpeningMessages(List)」向下依次触达 「visibleConversation.stream().allMatch」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.isOnlySupersededOpeningMessages(List)」负责主链路中的“OnlySuperseded开场消息Messages”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isOnlySupersededOpeningMessages(
            List<RoomMessageEntity> visibleConversation) {
        return !visibleConversation.isEmpty()
                && visibleConversation.stream()
                        .allMatch(EvidenceAgentTurnService::isSupersededOpeningMessage);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.isSupersededOpeningMessage(RoomMessageEntity)」。
    // 具体功能：「EvidenceAgentTurnService.isSupersededOpeningMessage(RoomMessageEntity)」：判断是否Superseded开场消息消息；实际协作者为 「message.getMessageType」、「message.getSenderRole」、「message.getSenderId」、「message.getMessageText」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.isSupersededOpeningMessage(RoomMessageEntity)」只由「EvidenceAgentTurnService」内部流程使用，负责封装“Superseded开场消息消息”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceAgentTurnService.isSupersededOpeningMessage(RoomMessageEntity)」向下依次触达 「message.getMessageType」、「message.getSenderRole」、「message.getSenderId」、「message.getMessageText」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.isSupersededOpeningMessage(RoomMessageEntity)」负责主链路中的“Superseded开场消息消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isSupersededOpeningMessage(RoomMessageEntity message) {
        if (message.getMessageType() != MessageType.AGENT_MESSAGE
                || !AGENT_SENDER_ROLE.equals(message.getSenderRole())
                || !AGENT_SENDER_ID.equals(message.getSenderId())) {
            return false;
        }
        String text = message.getMessageText();
        return text != null
                && SUPERSEDED_GENERIC_OPENING_MARKERS.stream().anyMatch(text::contains);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.visibleToAccessSession(RoomMessageEntity,CaseAccessSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnService.visibleToAccessSession(RoomMessageEntity,CaseAccessSessionEntity)」：判断可见性访问会话；实际协作者为 「permissionService.canReadActorAudience」、「accessSession.privileged」、「accessSession.getActorRole」、「message.getSenderRole」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.visibleToAccessSession(RoomMessageEntity,CaseAccessSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.visibleActorScopedConversationMessages」。
    // 下游影响：「EvidenceAgentTurnService.visibleToAccessSession(RoomMessageEntity,CaseAccessSessionEntity)」向下依次触达 「permissionService.canReadActorAudience」、「accessSession.privileged」、「accessSession.getActorRole」、「message.getSenderRole」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.visibleToAccessSession(RoomMessageEntity,CaseAccessSessionEntity)」负责主链路中的“访问会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private boolean visibleToAccessSession(
            RoomMessageEntity message, CaseAccessSessionEntity accessSession) {
        if (accessSession.privileged()) {
            return true;
        }
        if (isParty(accessSession.getActorRole()) && isPartySender(message)) {
            return accessSession.getActorRole().name().equals(message.getSenderRole())
                    && accessSession.getActorId().equals(message.getSenderId());
        }
        List<String> audiences = readStringList(message.getAudienceJson());
        if (!audiences.isEmpty() && !audiences.contains(accessSession.getActorRole().name())) {
            return false;
        }
        return permissionService.canReadActorAudience(
                accessSession, readStringList(message.getAudienceActorIdsJson()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.readStringList(String)」。
    // 具体功能：「EvidenceAgentTurnService.readStringList(String)」：读取字符串列表；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<String>」。
    // 上游调用：「EvidenceAgentTurnService.readStringList(String)」的上游调用点包括 「EvidenceAgentTurnService.visibleToAccessSession」。
    // 下游影响：「EvidenceAgentTurnService.readStringList(String)」向下依次触达 「objectMapper.readValue」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.readStringList(String)」统一“字符串列表”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message audience", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.view(RoomMessageEntity)」。
    // 具体功能：「EvidenceAgentTurnService.view(RoomMessageEntity)」：解析视图；实际协作者为 「objectMapper.readValue」、「entity.getAttachmentRefsJson」、「entity.getId」、「entity.getCaseId」；不满足前置条件时抛出 「IllegalStateException」，最终返回「RoomMessageView」。
    // 上游调用：「EvidenceAgentTurnService.view(RoomMessageEntity)」的上游调用点包括 「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.view(RoomMessageEntity)」向下依次触达 「objectMapper.readValue」、「entity.getAttachmentRefsJson」、「entity.getId」、「entity.getCaseId」；计算结果以「RoomMessageView」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.view(RoomMessageEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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
                    entity.getMessageSource(),
                    entity.getMessageText(),
                    attachments,
                    entity.getAgentRunId(),
                    entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence opening message attachments", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.openingIdempotencyKey(String,AgentConversationSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnService.openingIdempotencyKey(String,AgentConversationSessionEntity)」：开放开场消息Idempotency键；实际协作者为 「agentSession.getId」；处理的关键状态/协议值包括 「agent-evidence-opening:」、「:」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.openingIdempotencyKey(String,AgentConversationSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceAgentTurnService.openingIdempotencyKey(String,AgentConversationSessionEntity)」向下依次触达 「agentSession.getId」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.openingIdempotencyKey(String,AgentConversationSessionEntity)」负责主链路中的“开场消息Idempotency键”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String openingIdempotencyKey(
            String caseId, AgentConversationSessionEntity agentSession) {
        return "agent-evidence-opening:"
                + OPENING_IDEMPOTENCY_VERSION
                + ":"
                + caseId
                + ":"
                + agentSession.getId();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.turnIdempotencyKey(FulfillmentCaseEntity,AgentConversationSessionEntity,ActorRole,int)」。
    // 具体功能：「EvidenceAgentTurnService.turnIdempotencyKey(FulfillmentCaseEntity,AgentConversationSessionEntity,ActorRole,int)」：构建轮次Idempotency键；实际协作者为 「dispute.getId」、「agentSession.getId」；处理的关键状态/协议值包括 「agent-evidence-turn:」、「:」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.turnIdempotencyKey(FulfillmentCaseEntity,AgentConversationSessionEntity,ActorRole,int)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.turnIdempotencyKey(FulfillmentCaseEntity,AgentConversationSessionEntity,ActorRole,int)」向下依次触达 「dispute.getId」、「agentSession.getId」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.turnIdempotencyKey(FulfillmentCaseEntity,AgentConversationSessionEntity,ActorRole,int)」负责主链路中的“轮次Idempotency键”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String turnIdempotencyKey(
            FulfillmentCaseEntity dispute,
            AgentConversationSessionEntity agentSession,
            ActorRole audienceParty,
            int turnNo) {
        return "agent-evidence-turn:"
                + dispute.getId()
                + ":"
                + agentSession.getId()
                + ":"
                + audienceParty.name()
                + ":"
                + turnNo;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.defaultObject(JsonNode)」。
    // 具体功能：「EvidenceAgentTurnService.defaultObject(JsonNode)」：构建默认对象；实际协作者为 「node.isNull」、「objectMapper.createObjectNode」，最终返回「JsonNode」。
    // 上游调用：「EvidenceAgentTurnService.defaultObject(JsonNode)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.defaultObject(JsonNode)」向下依次触达 「node.isNull」、「objectMapper.createObjectNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.defaultObject(JsonNode)」负责主链路中的“默认对象”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode defaultObject(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.defaultArray(JsonNode)」。
    // 具体功能：「EvidenceAgentTurnService.defaultArray(JsonNode)」：构建默认数组；实际协作者为 「node.isNull」、「objectMapper.createArrayNode」，最终返回「JsonNode」。
    // 上游调用：「EvidenceAgentTurnService.defaultArray(JsonNode)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.defaultArray(JsonNode)」向下依次触达 「node.isNull」、「objectMapper.createArrayNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.defaultArray(JsonNode)」负责主链路中的“默认数组”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode defaultArray(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createArrayNode() : node;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.audienceJson(ActorRole)」。
    // 具体功能：「EvidenceAgentTurnService.audienceJson(ActorRole)」：构建受众 JSONJSON；实际协作者为 「json」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.audienceJson(ActorRole)」的上游调用点包括 「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「EvidenceAgentTurnService.audienceJson(ActorRole)」向下依次触达 「json」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.audienceJson(ActorRole)」负责主链路中的“受众 JSONJSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String audienceJson(ActorRole party) {
        return json(
                List.of(
                        party.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.hearingSupplementAudienceJson()」。
    // 具体功能：「EvidenceAgentTurnService.hearingSupplementAudienceJson()」：构建庭审补证受众 JSONJSON；实际协作者为 「json」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.hearingSupplementAudienceJson()」的上游调用点包括 「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「EvidenceAgentTurnService.hearingSupplementAudienceJson()」向下依次触达 「json」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.hearingSupplementAudienceJson()」负责主链路中的“庭审补证受众 JSONJSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String hearingSupplementAudienceJson() {
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.json(Object)」。
    // 具体功能：「EvidenceAgentTurnService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.json(Object)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」、「EvidenceAgentTurnService.persistEvidenceAssessments」、「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「EvidenceAgentTurnService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence agent turn", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.isEvidenceTurnMessage(MessageType)」。
    // 具体功能：「EvidenceAgentTurnService.isEvidenceTurnMessage(MessageType)」：判断是否证据轮次消息，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.isEvidenceTurnMessage(MessageType)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「EvidenceAgentTurnService.isEvidenceTurnMessage(MessageType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.isEvidenceTurnMessage(MessageType)」负责主链路中的“证据轮次消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isEvidenceTurnMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.isParty(ActorRole)」。
    // 具体功能：「EvidenceAgentTurnService.isParty(ActorRole)」：判断是否当事方，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.isParty(ActorRole)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「EvidenceAgentTurnService.visibleToAccessSession」。
    // 下游影响：「EvidenceAgentTurnService.isParty(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.isParty(ActorRole)」负责主链路中的“当事方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.isPartySender(RoomMessageEntity)」。
    // 具体功能：「EvidenceAgentTurnService.isPartySender(RoomMessageEntity)」：判断是否当事方Sender；实际协作者为 「message.getSenderRole」，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.isPartySender(RoomMessageEntity)」的上游调用点包括 「EvidenceAgentTurnService.visibleToAccessSession」。
    // 下游影响：「EvidenceAgentTurnService.isPartySender(RoomMessageEntity)」向下依次触达 「message.getSenderRole」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.isPartySender(RoomMessageEntity)」负责主链路中的“当事方Sender”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isPartySender(RoomMessageEntity message) {
        return ActorRole.USER.name().equals(message.getSenderRole())
                || ActorRole.MERCHANT.name().equals(message.getSenderRole());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.participantMemoryContent(RoomMessageCommand)」。
    // 具体功能：「EvidenceAgentTurnService.participantMemoryContent(RoomMessageCommand)」：构建参与人记忆内容；实际协作者为 「message.text」、「message.attachmentRefs」、「json」；处理的关键状态/协议值包括 「attachment_refs」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.participantMemoryContent(RoomMessageCommand)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「EvidenceAgentTurnService.participantMemoryContent(RoomMessageCommand)」向下依次触达 「message.text」、「message.attachmentRefs」、「json」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.participantMemoryContent(RoomMessageCommand)」负责主链路中的“参与人记忆内容”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String participantMemoryContent(RoomMessageCommand message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text();
        }
        return json(Map.of("attachment_refs", message.attachmentRefs()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.blank(String)」。
    // 具体功能：「EvidenceAgentTurnService.blank(String)」：判断空白值，最终返回「boolean」。
    // 上游调用：「EvidenceAgentTurnService.blank(String)」的上游调用点包括 「EvidenceAgentTurnService.safeRun」、「EvidenceAgentTurnService.validateEvidenceAssessmentCoverage」。
    // 下游影响：「EvidenceAgentTurnService.blank(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.blank(String)」负责主链路中的“”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.compactUuid()」。
    // 具体功能：「EvidenceAgentTurnService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.compactUuid()」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」。
    // 下游影响：「EvidenceAgentTurnService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceAgentTurnService.promptProfileId(ActorRole)」。
    // 具体功能：「EvidenceAgentTurnService.promptProfileId(ActorRole)」：构建promptProfile标识；处理的关键状态/协议值包括 「:」、「:v1」，最终返回「String」。
    // 上游调用：「EvidenceAgentTurnService.promptProfileId(ActorRole)」的上游调用点包括 「EvidenceAgentTurnService.resolveSession」。
    // 下游影响：「EvidenceAgentTurnService.promptProfileId(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceAgentTurnService.promptProfileId(ActorRole)」负责主链路中的“promptProfile标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String promptProfileId(ActorRole role) {
        return AGENT_ROLE + ":" + role.name() + ":v1";
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「TurnContext」。
    // 类型职责：定义轮次上下文跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record TurnContext(FulfillmentCaseEntity dispute, CaseRoomEntity room) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「SessionContext」。
    // 类型职责：定义会话上下文跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record SessionContext(
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            AgentInvocationContext agentContext) {}
}
