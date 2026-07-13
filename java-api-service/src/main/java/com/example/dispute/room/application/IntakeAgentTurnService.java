/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排接待Agent轮次规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「startInitialTurn」、「continueFromParticipantMessage」、「supports」、「finalizeResult」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunFinalizer;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeAgentTurnService」。
// 类型职责：编排接待Agent轮次规则、权限校验与事实读写；本类型显式提供 「IntakeAgentTurnService」、「IntakeAgentTurnService」、「startInitialTurn」、「continueFromParticipantMessage」、「startStreamingRun」、「supports」。
// 协作关系：主要由 「CaseApplicationService.createNew」、「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「RoomMessageService.create」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class IntakeAgentTurnService implements AgentRunFinalizer {

    public static final String AGENT_ROLE = "DISPUTE_INTAKE_OFFICER";
    private static final String AGENT_SENDER_ROLE = "CUSTOMER_SERVICE";
    private static final String AGENT_SENDER_ID = "dispute-intake-officer";
    private static final int DIALOGUE_WINDOW_MESSAGE_LIMIT = 6;
    private static final int RECENT_DIALOGUE_MESSAGE_LIMIT = DIALOGUE_WINDOW_MESSAGE_LIMIT - 1;
    private static final String DEGRADED_REASON_AGENT_CALL_FAILED = "AGENT_CALL_FAILED";
    private static final String DEGRADED_REASON_AGENT_OUTPUT_EMPTY = "AGENT_OUTPUT_EMPTY";
    private static final Logger log = LoggerFactory.getLogger(IntakeAgentTurnService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final IntakeAgentTurnClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private AgentRunCoordinator agentRunCoordinator;

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock)」。
    // 具体功能：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「intakeDossierRepository」(CaseIntakeDossierRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「accessSessionResolver」(AccessSessionResolver)、「agentSessionResolver」(AgentSessionResolver)、「permissionService」(SessionPermissionService)、「client」(IntakeAgentTurnClient)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「IntakeAgentTurnService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock)」的上游创建点包括 「IntakeAgentTurnServiceTest.setUp」。
    // 下游影响：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock)」负责主链路中的“接待Agent轮次服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public IntakeAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            IntakeAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.memoryRepository = memoryRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.accessSessionResolver = accessSessionResolver;
        this.agentSessionResolver = agentSessionResolver;
        this.permissionService = permissionService;
        this.client = client;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」。
    // 具体功能：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「intakeDossierRepository」(CaseIntakeDossierRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「accessSessionResolver」(AccessSessionResolver)、「agentSessionResolver」(AgentSessionResolver)、「permissionService」(SessionPermissionService)、「client」(IntakeAgentTurnClient)、「objectMapper」(ObjectMapper)、「clock」(Clock)、「agentRunCoordinator」(AgentRunCoordinator) 并保存为「IntakeAgentTurnService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」的上游创建点包括 「IntakeAgentTurnServiceTest.setUp」。
    // 下游影响：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeAgentTurnService.IntakeAgentTurnService(FulfillmentCaseRepository,CaseRoomRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,RoomMessageRepository,CaseEventService,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,IntakeAgentTurnClient,ObjectMapper,Clock,AgentRunCoordinator)」负责主链路中的“接待Agent轮次服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public IntakeAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            IntakeAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock,
            AgentRunCoordinator agentRunCoordinator) {
        this(
                caseRepository,
                roomRepository,
                memoryRepository,
                intakeDossierRepository,
                messageRepository,
                eventService,
                accessSessionResolver,
                agentSessionResolver,
                permissionService,
                client,
                objectMapper,
                clock);
        this.agentRunCoordinator = agentRunCoordinator;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.startInitialTurn(String,AuthenticatedActor,IntakeLobbySeed,String,String)」。
    // 具体功能：「IntakeAgentTurnService.startInitialTurn(String,AuthenticatedActor,IntakeLobbySeed,String,String)」：为接待室发起方解析私有 Agent 会话，构建初始案件事实与脱敏大厅种子，分配下一 turnNo 并启动可恢复的 INTAKE_TURN AgentRun，最终返回「AgentRunAcceptedView」。
    // 上游调用：「IntakeAgentTurnService.startInitialTurn(String,AuthenticatedActor,IntakeLobbySeed,String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「CaseApplicationService.createNew」、「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」。
    // 下游影响：「IntakeAgentTurnService.startInitialTurn(String,AuthenticatedActor,IntakeLobbySeed,String,String)」向下依次触达 「memoryRepository.findMaxTurnNoByAgentSessionId」、「IntakeInitialCaseFacts.from」、「session.agentSession」、「context.dispute」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「IntakeAgentTurnService.startInitialTurn(String,AuthenticatedActor,IntakeLobbySeed,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentRunAcceptedView startInitialTurn(
            String caseId,
            AuthenticatedActor actor,
            IntakeLobbySeed lobbySeed,
            String traceId,
            String requestId) {
        // 先在 Java 事实源中解析案件、房间和 party-private 会话，再构造 Agent 输入。
        // lobbySeed 会经过脱敏，短订单号/售后号不会被当作自然语言事实重复送给模型。
        TurnContext context = prepare(caseId, RoomType.INTAKE);
        SessionContext session = resolveSession(caseId, actor, RoomType.INTAKE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        IntakeLobbySeed sanitizedLobbySeed = sanitizeLobbySeed(lobbySeed);
        String turnSource = initialFormSource(context.dispute());
        JsonNode previousScrollSnapshot = latestScrollSnapshot(session.agentSession().getId());
        IntakeAgentTurnCommand command =
                new IntakeAgentTurnCommand(
                        caseId,
                        RoomType.INTAKE,
                        turnSource,
                        IntakeInitialCaseFacts.from(sanitizedLobbySeed, turnSource),
                        null,
                        List.of(),
                        previousScrollSnapshot,
                        List.of(),
                        session.agentContext());
        if (agentRunCoordinator != null) {
            // 正式链路只创建可恢复 AgentRun；同步 client 分支仅用于兼容旧测试或部署。
            return startStreamingRun(
                    context, session, turnNo, actor, command, traceId, requestId);
        }
        IntakeAgentTurnResult result = safeRun(command, previousScrollSnapshot, traceId, requestId);
        persistAgentTurn(context, session, turnNo, result, traceId);
        return null;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageEntity,String,String)」。
    // 具体功能：「IntakeAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageEntity,String,String)」：把已落库的参与方消息先追加为 RoomTurnMemory，再组装最近对话、当前卷轴和发起方陈述，以同一 Trace/Request ID 启动下一次接待 AgentRun，最终返回「AgentRunAcceptedView」。
    // 上游调用：「IntakeAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageEntity,String,String)」的上游调用点包括 「RoomMessageService.create」、「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」、「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts」。
    // 下游影响：「IntakeAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageEntity,String,String)」向下依次触达 「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.save」、「RoomTurnMemoryEntity.participantTurn」、「message.getMessageType」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「IntakeAgentTurnService.continueFromParticipantMessage(String,RoomType,AuthenticatedActor,RoomMessageEntity,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentRunAcceptedView continueFromParticipantMessage(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            RoomMessageEntity message,
            String traceId,
            String requestId) {
        if (roomType != RoomType.INTAKE
                || message.getMessageType() != MessageType.PARTY_TEXT
                || !isParty(actor.role())) {
            return null;
        }
        TurnContext context = prepare(caseId, RoomType.INTAKE);
        SessionContext session = resolveSession(caseId, actor, RoomType.INTAKE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        // 用户原话先成为不可变会话记忆，再启动 Agent。即使模型失败，参与方提交也不会丢失。
        memoryRepository.save(
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_" + compactUuid(),
                        caseId,
                        RoomType.INTAKE,
                        turnNo,
                        actor.actorId(),
                        actor.role().name(),
                        message.getMessageText(),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        JsonNode previousScrollSnapshot = latestScrollSnapshot(session.agentSession().getId());
        IntakeAgentTurnCommand command =
                new IntakeAgentTurnCommand(
                        caseId,
                        RoomType.INTAKE,
                        "ROOM_MESSAGE",
                        null,
                        dialogueMessage(message, "ROOM_MESSAGE"),
                        recentDialogueMessages(context.room(), message.getSequenceNo()),
                        previousScrollSnapshot,
                        initiatorStatementTranscript(session.agentSession()),
                        session.agentContext());
        if (agentRunCoordinator != null) {
            return startStreamingRun(
                    context, session, turnNo, actor, command, traceId, requestId);
        }
        IntakeAgentTurnResult result = safeRun(command, previousScrollSnapshot, traceId, requestId);
        persistAgentTurn(context, session, turnNo, result, traceId);
        return null;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,IntakeAgentTurnCommand,String,String)」。
    // 具体功能：「IntakeAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,IntakeAgentTurnCommand,String,String)」：把会话、案件、房间、受众、prompt profile 和完整命令封装为 AgentRunStartCommand，使用 agent-intake-turn 幂等键交给统一流式协调器；本方法不直接调用 Python，最终返回「AgentRunAcceptedView」。
    // 上游调用：「IntakeAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,IntakeAgentTurnCommand,String,String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,IntakeAgentTurnCommand,String,String)」向下依次触达 「agentRunCoordinator.start」、「session.agentSession」、「context.dispute」、「context.room」；计算结果以「AgentRunAcceptedView」交给调用方。
    // 系统意义：「IntakeAgentTurnService.startStreamingRun(TurnContext,SessionContext,int,AuthenticatedActor,IntakeAgentTurnCommand,String,String)」负责主链路中的“Streaming运行”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private AgentRunAcceptedView startStreamingRun(
            TurnContext context,
            SessionContext session,
            int turnNo,
            AuthenticatedActor actor,
            IntakeAgentTurnCommand command,
            String traceId,
            String requestId) {
        String idempotencyKey =
                "agent-intake-turn:" + session.agentSession().getId() + ":" + turnNo;
        return agentRunCoordinator.start(
                new AgentRunStartCommand(
                        context.dispute().getId(),
                        context.room().getId(),
                        "INTAKE_TURN",
                        objectMapper.valueToTree(command),
                        List.of(
                                actor.role().name(),
                                ActorRole.CUSTOMER_SERVICE.name(),
                                ActorRole.PLATFORM_REVIEWER.name(),
                                ActorRole.ADMIN.name(),
                                ActorRole.SYSTEM.name()),
                        List.of(actor.actorId()),
                        idempotencyKey,
                        traceId,
                        requestId,
                        actor.actorId()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.supports(String)」。
    // 具体功能：「IntakeAgentTurnService.supports(String)」：判断是否支持；处理的关键状态/协议值包括 「INTAKE_TURN」，最终返回「boolean」。
    // 上游调用：「IntakeAgentTurnService.supports(String)」由使用「IntakeAgentTurnService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「IntakeAgentTurnService.supports(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「IntakeAgentTurnService.supports(String)」负责主链路中的“”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @Override
    public boolean supports(String operation) {
        return "INTAKE_TURN".equals(operation);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「IntakeAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」：作为 INTAKE_TURN Finalizer 重新解析持久化请求上下文，验证结果与 case/room/session 一致，再原子保存 Agent 记忆、接待卷宗补丁、房间消息和生命周期事件，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」由使用「IntakeAgentTurnService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「IntakeAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」向下依次触达 「objectMapper.convertValue」、「finalization.request」、「command.agentContext」、「finalization.caseId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「IntakeAgentTurnService.finalizeResult(AgentRunFinalizationContext,JsonNode)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Override
    @Transactional
    public void finalizeResult(AgentRunFinalizationContext finalization, JsonNode rawResult) {
        IntakeAgentTurnCommand command =
                objectMapper.convertValue(finalization.request(), IntakeAgentTurnCommand.class);
        IntakeAgentTurnResult result =
                objectMapper.convertValue(rawResult, IntakeAgentTurnResult.class);
        ActorRole actorRole = ActorRole.valueOf(command.agentContext().actorRole());
        AuthenticatedActor actor =
                new AuthenticatedActor(command.agentContext().actorId(), actorRole);
        SessionContext session = resolveSession(finalization.caseId(), actor, RoomType.INTAKE);
        // Finalizer 不信任旧请求中的会话标识：重新解析当前访问/Agent 会话并逐项比对，
        // 防止运行期间会话被撤销或迟到结果写入另一方的私有记忆。
        if (!session.agentSession().getId().equals(command.agentContext().agentSessionId())
                || !session.accessSession().getId().equals(
                        command.agentContext().accessSessionId())) {
            throw new IllegalStateException("intake agent session changed before finalization");
        }
        TurnContext context = prepare(finalization.caseId(), RoomType.INTAKE);
        int turnNo = turnNo(finalization.idempotencyKey());
        persistAgentTurn(
                context,
                session,
                turnNo,
                result,
                finalization.runId(),
                finalization.traceId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」。
    // 具体功能：「IntakeAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」：解析案件访问会话并要求接待室可读，再按 party-private scope 取得 AgentConversationSession，保证用户和商家的接待记忆互不串用，最终返回「SessionContext」。
    // 上游调用：「IntakeAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.finalizeResult」。
    // 下游影响：「IntakeAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」向下依次触达 「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「agentSessionResolver.resolve」、「AgentInvocationContext.partyPrivate」；计算结果以「SessionContext」交给调用方。
    // 系统意义：「IntakeAgentTurnService.resolveSession(String,AuthenticatedActor,RoomType)」负责主链路中的“会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private SessionContext resolveSession(
            String caseId, AuthenticatedActor actor, RoomType roomType) {
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
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
                        "INTAKE_INITIATOR_PRIVATE"));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.prepare(String,RoomType)」。
    // 具体功能：「IntakeAgentTurnService.prepare(String,RoomType)」：准备轮次上下文：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「room.getRoomStatus」；不满足前置条件时抛出 「IllegalStateException」，最终返回「TurnContext」。
    // 上游调用：「IntakeAgentTurnService.prepare(String,RoomType)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.finalizeResult」。
    // 下游影响：「IntakeAgentTurnService.prepare(String,RoomType)」向下依次触达 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「room.getRoomStatus」；计算结果以「TurnContext」交给调用方。
    // 系统意义：「IntakeAgentTurnService.prepare(String,RoomType)」负责主链路中的“轮次上下文”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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
            throw new IllegalStateException("intake room is not open");
        }
        return new TurnContext(dispute, room);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.safeRun(IntakeAgentTurnCommand,JsonNode,String,String)」。
    // 具体功能：「IntakeAgentTurnService.safeRun(IntakeAgentTurnCommand,JsonNode,String,String)」：兼容旧同步客户端：调用 Python 后要求 room_utterance 非空；失败时记录 Trace/Request ID 并抛统一 AgentExecutionException，最终返回「IntakeAgentTurnResult」。
    // 上游调用：「IntakeAgentTurnService.safeRun(IntakeAgentTurnCommand,JsonNode,String,String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.safeRun(IntakeAgentTurnCommand,JsonNode,String,String)」向下依次触达 「client.run」、「result.roomUtterance」、「log.warn」、「command.caseId」；计算结果以「IntakeAgentTurnResult」交给调用方。
    // 系统意义：「IntakeAgentTurnService.safeRun(IntakeAgentTurnCommand,JsonNode,String,String)」负责主链路中的“运行”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private IntakeAgentTurnResult safeRun(
            IntakeAgentTurnCommand command,
            JsonNode previousScrollSnapshot,
            String traceId,
            String requestId) {
        try {
            IntakeAgentTurnResult result = client.run(command, traceId, requestId);
            if (result == null || blank(result.roomUtterance())) {
                log.warn(
                        "Intake agent turn failed closed because output was empty: case_id={}, room_type={}, turn_source={}, trace_id={}, request_id={}",
                        command.caseId(),
                        command.roomType(),
                        command.turnSource(),
                        traceId,
                        requestId);
                throw new AgentExecutionException(
                        ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                        "intake agent returned empty output",
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
                    "intake agent model request failed",
                    Map.of("trace_id", traceId, "request_id", requestId));
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.logAgentFailure(IntakeAgentTurnCommand,String,String,RuntimeException)」。
    // 具体功能：「IntakeAgentTurnService.logAgentFailure(IntakeAgentTurnCommand,String,String,RuntimeException)」：执行logAgent失败；实际协作者为 「log.warn」、「command.caseId」、「command.roomType」、「command.turnSource」，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.logAgentFailure(IntakeAgentTurnCommand,String,String,RuntimeException)」的上游调用点包括 「IntakeAgentTurnService.safeRun」。
    // 下游影响：「IntakeAgentTurnService.logAgentFailure(IntakeAgentTurnCommand,String,String,RuntimeException)」向下依次触达 「log.warn」、「command.caseId」、「command.roomType」、「command.turnSource」。
    // 系统意义：「IntakeAgentTurnService.logAgentFailure(IntakeAgentTurnCommand,String,String,RuntimeException)」负责主链路中的“logAgent失败”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void logAgentFailure(
            IntakeAgentTurnCommand command,
            String traceId,
            String requestId,
            RuntimeException failure) {
        log.warn(
                "Intake agent turn failed closed after agent call failure: case_id={}, room_type={}, turn_source={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
                command.caseId(),
                command.roomType(),
                command.turnSource(),
                traceId,
                requestId,
                failure.getClass().getName(),
                failure.getMessage(),
                failure);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.sanitizeLobbySeed(IntakeLobbySeed)」。
    // 具体功能：「IntakeAgentTurnService.sanitizeLobbySeed(IntakeLobbySeed)」：脱敏接待大厅种子数据；实际协作者为 「seed.orderReference」、「seed.afterSalesReference」、「seed.logisticsReference」、「seed.initiatorRole」，最终返回「IntakeLobbySeed」。
    // 上游调用：「IntakeAgentTurnService.sanitizeLobbySeed(IntakeLobbySeed)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」。
    // 下游影响：「IntakeAgentTurnService.sanitizeLobbySeed(IntakeLobbySeed)」向下依次触达 「seed.orderReference」、「seed.afterSalesReference」、「seed.logisticsReference」、「seed.initiatorRole」；计算结果以「IntakeLobbySeed」交给调用方。
    // 系统意义：「IntakeAgentTurnService.sanitizeLobbySeed(IntakeLobbySeed)」负责主链路中的“接待大厅种子数据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private IntakeLobbySeed sanitizeLobbySeed(IntakeLobbySeed seed) {
        return new IntakeLobbySeed(
                validIdentifierOrNull(seed.orderReference()),
                validIdentifierOrNull(seed.afterSalesReference()),
                validIdentifierOrNull(seed.logisticsReference()),
                seed.initiatorRole(),
                seed.rawText(),
                validIdentifierOrNull(seed.requestedOutcomeHint()),
                sanitizeClaimResolutionSeed(seed.claimResolutionSeed()),
                sanitizeRespondentAttitudeSeed(seed.respondentAttitudeSeed()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.sanitizeClaimResolutionSeed(ClaimResolutionSeed)」。
    // 具体功能：「IntakeAgentTurnService.sanitizeClaimResolutionSeed(ClaimResolutionSeed)」：脱敏主张Resolution种子数据；实际协作者为 「seed.initiatorRole」、「seed.requestedResolution」、「seed.requestedAmount」、「seed.requestedItems」，最终返回「IntakeLobbySeed.ClaimResolutionSeed」。
    // 上游调用：「IntakeAgentTurnService.sanitizeClaimResolutionSeed(ClaimResolutionSeed)」的上游调用点包括 「IntakeAgentTurnService.sanitizeLobbySeed」。
    // 下游影响：「IntakeAgentTurnService.sanitizeClaimResolutionSeed(ClaimResolutionSeed)」向下依次触达 「seed.initiatorRole」、「seed.requestedResolution」、「seed.requestedAmount」、「seed.requestedItems」；计算结果以「IntakeLobbySeed.ClaimResolutionSeed」交给调用方。
    // 系统意义：「IntakeAgentTurnService.sanitizeClaimResolutionSeed(ClaimResolutionSeed)」负责主链路中的“主张Resolution种子数据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static IntakeLobbySeed.ClaimResolutionSeed sanitizeClaimResolutionSeed(
            IntakeLobbySeed.ClaimResolutionSeed seed) {
        if (seed == null) return null;
        return new IntakeLobbySeed.ClaimResolutionSeed(
                validIdentifierOrNull(seed.initiatorRole()),
                validIdentifierOrNull(seed.requestedResolution()),
                seed.requestedAmount(),
                blankToNull(seed.requestedItems()),
                blankToNull(seed.requestReason()),
                blankToNullPreservingText(seed.originalStatement()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed(RespondentAttitudeSeed)」。
    // 具体功能：「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed(RespondentAttitudeSeed)」：脱敏被申请方态度种子数据；实际协作者为 「seed.respondentRole」、「seed.attitude」、「seed.position」、「seed.source」，最终返回「IntakeLobbySeed.RespondentAttitudeSeed」。
    // 上游调用：「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed(RespondentAttitudeSeed)」的上游调用点包括 「IntakeAgentTurnService.sanitizeLobbySeed」。
    // 下游影响：「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed(RespondentAttitudeSeed)」向下依次触达 「seed.respondentRole」、「seed.attitude」、「seed.position」、「seed.source」；计算结果以「IntakeLobbySeed.RespondentAttitudeSeed」交给调用方。
    // 系统意义：「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed(RespondentAttitudeSeed)」负责主链路中的“被申请方态度种子数据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static IntakeLobbySeed.RespondentAttitudeSeed sanitizeRespondentAttitudeSeed(
            IntakeLobbySeed.RespondentAttitudeSeed seed) {
        if (seed == null) return null;
        return new IntakeLobbySeed.RespondentAttitudeSeed(
                validIdentifierOrNull(seed.respondentRole()),
                validIdentifierOrNull(seed.attitude()),
                blankToNull(seed.position()),
                blankToNull(seed.source()),
                seed.confidence());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String)」。
    // 具体功能：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String)」：把 Agent 输入/输出、memory frame 和模型元数据写入 RoomTurnMemory，更新当前接待卷宗，再以稳定幂等键追加 Agent 房间消息和 INTAKE_DOSSIER_UPDATED 事件，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.finalizeResult」、「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String)」向下依次触达 「persistAgentTurn」、「compactUuid」。
    // 系统意义：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String)」负责主链路中的“Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            IntakeAgentTurnResult result,
            String traceId) {
        persistAgentTurn(
                context,
                session,
                turnNo,
                result,
                "INTAKE_RUN_" + compactUuid(),
                traceId);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String,String)」。
    // 具体功能：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String,String)」：把 Agent 输入/输出、memory frame 和模型元数据写入 RoomTurnMemory，更新当前接待卷宗，再以稳定幂等键追加 Agent 房间消息和 INTAKE_DOSSIER_UPDATED 事件，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String,String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.finalizeResult」、「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String,String)」向下依次触达 「memoryRepository.save」、「eventService.recordLifecycleEvent」、「RoomTurnMemoryEntity.agentTurn」、「context.dispute」。
    // 系统意义：「IntakeAgentTurnService.persistAgentTurn(TurnContext,SessionContext,int,IntakeAgentTurnResult,String,String)」负责主链路中的“Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            IntakeAgentTurnResult result,
            String runId,
            String traceId) {
        memoryRepository.save(
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_" + compactUuid(),
                        context.dispute().getId(),
                        RoomType.INTAKE,
                        turnNo,
                        AGENT_SENDER_ID,
                        AGENT_ROLE,
                        result.roomUtterance(),
                        json(dossierPatchWithMemoryFrame(result)),
                        json(defaultObject(result.scrollSnapshot())),
                        json(defaultArray(result.canvasOperations())),
                        runId,
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        upsertCurrentDossier(context.dispute().getId(), turnNo, result);
        appendAgentMessage(
                context.dispute(),
                context.room(),
                session.agentSession(),
                result.roomUtterance(),
                turnNo,
                traceId,
                runId);
        eventService.recordLifecycleEvent(
                context.dispute().getId(),
                context.room().getId(),
                "INTAKE_DOSSIER_UPDATED",
                Map.of("turn_no", turnNo, "agent_role", AGENT_ROLE),
                "intake-dossier-updated:"
                        + context.dispute().getId()
                        + ":"
                        + session.agentSession().getId()
                        + ":"
                        + turnNo,
                AGENT_SENDER_ID);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.upsertCurrentDossier(String,int,IntakeAgentTurnResult)」。
    // 具体功能：「IntakeAgentTurnService.upsertCurrentDossier(String,int,IntakeAgentTurnResult)」：按 case+INTAKE 房间创建或更新接待卷宗，把 scroll snapshot、dossier patch、质量分、是否可进入下一步和受理建议保存为最新投影，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.upsertCurrentDossier(String,int,IntakeAgentTurnResult)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.upsertCurrentDossier(String,int,IntakeAgentTurnResult)」向下依次触达 「intakeDossierRepository.findByCaseIdAndRoomType」、「intakeDossierRepository.save」、「CaseIntakeDossierEntity.create」、「result.scrollSnapshot」。
    // 系统意义：「IntakeAgentTurnService.upsertCurrentDossier(String,int,IntakeAgentTurnResult)」负责主链路中的“upsertCurrent卷宗”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void upsertCurrentDossier(
            String caseId,
            int turnNo,
            IntakeAgentTurnResult result) {
        JsonNode dossier = defaultObject(result.scrollSnapshot());
        int qualityScore = dossier.path("intake_quality").path("score").asInt(0);
        boolean readyForNextStep =
                dossier.path("intake_quality").path("ready_for_next_step").asBoolean(false);
        String recommendation =
                textOrDefault(
                        dossier.path("admission").path("recommendation"),
                        result.admissionRecommendation());
        String dossierJson = json(dossier);
        CaseIntakeDossierEntity entity;
        var existing = intakeDossierRepository.findByCaseIdAndRoomType(caseId, RoomType.INTAKE);
        if (existing.isPresent()) {
            entity = existing.orElseThrow();
            entity.replaceWith(
                    dossierJson,
                    qualityScore,
                    readyForNextStep,
                    recommendation,
                    turnNo,
                    AGENT_SENDER_ID);
        } else {
            entity =
                    CaseIntakeDossierEntity.create(
                            "INTAKE_DOSSIER_" + compactUuid(),
                            caseId,
                            RoomType.INTAKE,
                            dossierJson,
                            qualityScore,
                            readyForNextStep,
                            recommendation,
                            turnNo,
                            AGENT_SENDER_ID);
        }
        intakeDossierRepository.save(entity);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,String,int,String,String)」。
    // 具体功能：「IntakeAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,String,int,String,String)」：追加Agent消息：先把新状态写入 PostgreSQL 事实表；实际协作者为 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」；处理的关键状态/协议值包括 「agent-intake-turn:」、「:」、「MESSAGE_」、「[]」，最终返回「void」。
    // 上游调用：「IntakeAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,String,int,String,String)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,String,int,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」。
    // 系统意义：「IntakeAgentTurnService.appendAgentMessage(FulfillmentCaseEntity,CaseRoomEntity,AgentConversationSessionEntity,String,int,String,String)」负责主链路中的“Agent消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void appendAgentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AgentConversationSessionEntity agentSession,
            String utterance,
            int turnNo,
            String traceId,
            String runId) {
        String idempotencyKey = "agent-intake-turn:" + agentSession.getId() + ":" + turnNo;
        if (messageRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        String audienceActorIdsJson = json(List.of(agentSession.getActorId()));
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity message =
                RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                AGENT_SENDER_ROLE,
                                AGENT_SENDER_ID,
                                audienceJson(),
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
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.latestScrollSnapshot(String)」。
    // 具体功能：「IntakeAgentTurnService.latestScrollSnapshot(String)」：构建最新版本Scroll快照；实际协作者为 「findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，最终返回「JsonNode」。
    // 上游调用：「IntakeAgentTurnService.latestScrollSnapshot(String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.latestScrollSnapshot(String)」向下依次触达 「findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「IntakeAgentTurnService.latestScrollSnapshot(String)」负责主链路中的“最新版本Scroll快照”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode latestScrollSnapshot(String agentSessionId) {
        return memoryRepository
                .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(agentSessionId)
                .map(RoomTurnMemoryEntity::getScrollSnapshotJson)
                .map(this::readJson)
                .orElseGet(objectMapper::createObjectNode);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.recentDialogueMessages(CaseRoomEntity,long)」。
    // 具体功能：「IntakeAgentTurnService.recentDialogueMessages(CaseRoomEntity,long)」：构建最近对话Messages；实际协作者为 「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「Math.max」、「room.getId」、「message.getSequenceNo」，最终返回「List<IntakeDialogueMessage>」。
    // 上游调用：「IntakeAgentTurnService.recentDialogueMessages(CaseRoomEntity,long)」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.recentDialogueMessages(CaseRoomEntity,long)」向下依次触达 「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「Math.max」、「room.getId」、「message.getSequenceNo」；计算结果以「List<IntakeDialogueMessage>」交给调用方。
    // 系统意义：「IntakeAgentTurnService.recentDialogueMessages(CaseRoomEntity,long)」负责主链路中的“最近对话Messages”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<IntakeDialogueMessage> recentDialogueMessages(
            CaseRoomEntity room, long currentSequence) {
        List<RoomMessageEntity> candidates =
                messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()).stream()
                        .filter(message -> message.getSequenceNo() < currentSequence)
                        .filter(
                                message ->
                                        message.getMessageType() == MessageType.PARTY_TEXT
                                                || message.getMessageType()
                                                        == MessageType.AGENT_MESSAGE)
                        .toList();
        int start = Math.max(0, candidates.size() - RECENT_DIALOGUE_MESSAGE_LIMIT);
        while (start < candidates.size()
                && candidates.get(start).getMessageType() != MessageType.AGENT_MESSAGE) {
            start++;
        }
        return candidates.subList(start, candidates.size()).stream()
                .map(this::dialogueMessage)
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity)」。
    // 具体功能：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity)」：提供「dialogueMessage」的便捷重载：接收 「message」(RoomMessageEntity)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity)」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.dialogueMessage」。
    // 下游影响：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity)」向下依次触达 「message.getMessageType」、「dialogueMessage」；计算结果以「IntakeDialogueMessage」交给调用方。
    // 系统意义：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity)」负责主链路中的“对话消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private IntakeDialogueMessage dialogueMessage(RoomMessageEntity message) {
        String source;
        if (message.getMessageType() == MessageType.AGENT_MESSAGE) {
            source = "AGENT_RESPONSE";
        } else {
            source = "ROOM_MESSAGE";
        }
        return dialogueMessage(message, source);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity,String)」。
    // 具体功能：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity,String)」：构建对话消息；实际协作者为 「message.getMessageType」、「message.getSenderRole」、「message.getId」、「message.getSequenceNo」；处理的关键状态/协议值包括 「AGENT」，最终返回「IntakeDialogueMessage」。
    // 上游调用：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity,String)」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.dialogueMessage」。
    // 下游影响：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity,String)」向下依次触达 「message.getMessageType」、「message.getSenderRole」、「message.getId」、「message.getSequenceNo」；计算结果以「IntakeDialogueMessage」交给调用方。
    // 系统意义：「IntakeAgentTurnService.dialogueMessage(RoomMessageEntity,String)」负责主链路中的“对话消息”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private IntakeDialogueMessage dialogueMessage(RoomMessageEntity message, String source) {
        String role =
                message.getMessageType() == MessageType.AGENT_MESSAGE
                        ? "AGENT"
                        : message.getSenderRole();
        return new IntakeDialogueMessage(
                message.getId(),
                message.getSequenceNo(),
                role,
                source,
                message.getMessageText());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.initiatorStatementTranscript(AgentConversationSessionEntity)」。
    // 具体功能：「IntakeAgentTurnService.initiatorStatementTranscript(AgentConversationSessionEntity)」：构建发起方StatementTranscript；实际协作者为 「findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc」、「agentSession.getId」，最终返回「List<IntakeParticipantMessage>」。
    // 上游调用：「IntakeAgentTurnService.initiatorStatementTranscript(AgentConversationSessionEntity)」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.initiatorStatementTranscript(AgentConversationSessionEntity)」向下依次触达 「findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc」、「agentSession.getId」；计算结果以「List<IntakeParticipantMessage>」交给调用方。
    // 系统意义：「IntakeAgentTurnService.initiatorStatementTranscript(AgentConversationSessionEntity)」负责主链路中的“发起方StatementTranscript”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private List<IntakeParticipantMessage> initiatorStatementTranscript(
            AgentConversationSessionEntity agentSession) {
        return memoryRepository
                .findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(
                        agentSession.getId())
                .stream()
                .map(IntakeAgentTurnService::toParticipantMessage)
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.toParticipantMessage(RoomTurnMemoryEntity)」。
    // 具体功能：「IntakeAgentTurnService.toParticipantMessage(RoomTurnMemoryEntity)」：转换参与人消息；实际协作者为 「memory.getTurnNo」、「memory.getAnswerRole」、「memory.getAnswerContent」；处理的关键状态/协议值包括 「INTAKE_TURN_」，最终返回「IntakeParticipantMessage」。
    // 上游调用：「IntakeAgentTurnService.toParticipantMessage(RoomTurnMemoryEntity)」只由「IntakeAgentTurnService」内部流程使用，负责封装“参与人消息”这一步校验、映射或状态转换。
    // 下游影响：「IntakeAgentTurnService.toParticipantMessage(RoomTurnMemoryEntity)」向下依次触达 「memory.getTurnNo」、「memory.getAnswerRole」、「memory.getAnswerContent」；计算结果以「IntakeParticipantMessage」交给调用方。
    // 系统意义：「IntakeAgentTurnService.toParticipantMessage(RoomTurnMemoryEntity)」统一“参与人消息”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static IntakeParticipantMessage toParticipantMessage(
            RoomTurnMemoryEntity memory) {
        return new IntakeParticipantMessage(
                "INTAKE_TURN_" + memory.getTurnNo(),
                memory.getAnswerRole(),
                memory.getAnswerContent());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.initialFormSource(FulfillmentCaseEntity)」。
    // 具体功能：「IntakeAgentTurnService.initialFormSource(FulfillmentCaseEntity)」：构建initialForm来源；实际协作者为 「dispute.getSourceType」；处理的关键状态/协议值包括 「EXTERNAL_IMPORT」、「FORM_SUBMISSION」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.initialFormSource(FulfillmentCaseEntity)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」。
    // 下游影响：「IntakeAgentTurnService.initialFormSource(FulfillmentCaseEntity)」向下依次触达 「dispute.getSourceType」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.initialFormSource(FulfillmentCaseEntity)」负责主链路中的“initialForm来源”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String initialFormSource(FulfillmentCaseEntity dispute) {
        return dispute.getSourceType() == CaseSourceType.EXTERNAL_IMPORT
                ? "EXTERNAL_IMPORT"
                : "FORM_SUBMISSION";
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.degraded(JsonNode,String,String)」。
    // 具体功能：「IntakeAgentTurnService.degraded(JsonNode,String,String)」：构建降级结果；实际协作者为 「previousScrollSnapshot.isNull」、「objectMapper.createObjectNode」、「objectMapper.valueToTree」；处理的关键状态/协议值包括 「agent_degraded」、「agent_degraded_reason」、「trace_id」、「next_best_action」，最终返回「IntakeAgentTurnResult」。
    // 上游调用：「IntakeAgentTurnService.degraded(JsonNode,String,String)」只由「IntakeAgentTurnService」内部流程使用，负责封装“降级结果”这一步校验、映射或状态转换。
    // 下游影响：「IntakeAgentTurnService.degraded(JsonNode,String,String)」向下依次触达 「previousScrollSnapshot.isNull」、「objectMapper.createObjectNode」、「objectMapper.valueToTree」；计算结果以「IntakeAgentTurnResult」交给调用方。
    // 系统意义：「IntakeAgentTurnService.degraded(JsonNode,String,String)」负责主链路中的“降级结果”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private IntakeAgentTurnResult degraded(
            JsonNode previousScrollSnapshot, String reason, String traceId) {
        JsonNode scroll =
                previousScrollSnapshot == null || previousScrollSnapshot.isNull()
                        ? objectMapper.createObjectNode()
                        : previousScrollSnapshot;
        return new IntakeAgentTurnResult(
                "接待官暂时没有完成智能整理，系统正在等待模型服务恢复。请稍后刷新页面；首轮案情追问和展板生成完成前，暂不能继续提交陈述。",
                objectMapper.valueToTree(
                        Map.of(
                                "agent_degraded",
                                true,
                                "agent_degraded_reason",
                                reason,
                                "trace_id",
                                traceId,
                                "next_best_action",
                                "WAIT_FOR_AGENT_INITIAL_REPLY")),
                scroll,
                objectMapper.valueToTree(List.of()),
                objectMapper.createObjectNode(),
                "CONTINUE",
                List.of(),
                false,
                "STUB",
                0.0);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.readJson(String)」。
    // 具体功能：「IntakeAgentTurnService.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.createObjectNode」、「objectMapper.readTree」，最终返回「JsonNode」。
    // 上游调用：「IntakeAgentTurnService.readJson(String)」只由「IntakeAgentTurnService」内部流程使用，负责封装“JSON”这一步校验、映射或状态转换。
    // 下游影响：「IntakeAgentTurnService.readJson(String)」向下依次触达 「objectMapper.createObjectNode」、「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「IntakeAgentTurnService.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.defaultObject(JsonNode)」。
    // 具体功能：「IntakeAgentTurnService.defaultObject(JsonNode)」：构建默认对象；实际协作者为 「node.isNull」、「objectMapper.createObjectNode」，最终返回「JsonNode」。
    // 上游调用：「IntakeAgentTurnService.defaultObject(JsonNode)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」、「IntakeAgentTurnService.upsertCurrentDossier」、「IntakeAgentTurnService.dossierPatchWithMemoryFrame」。
    // 下游影响：「IntakeAgentTurnService.defaultObject(JsonNode)」向下依次触达 「node.isNull」、「objectMapper.createObjectNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「IntakeAgentTurnService.defaultObject(JsonNode)」负责主链路中的“默认对象”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode defaultObject(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.dossierPatchWithMemoryFrame(IntakeAgentTurnResult)」。
    // 具体功能：「IntakeAgentTurnService.dossierPatchWithMemoryFrame(IntakeAgentTurnResult)」：构建卷宗Patch包含记忆帧：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「result.dossierPatch」、「source.isObject」、「objectMapper.createObjectNode」、「result.memoryFrame」；处理的关键状态/协议值包括 「memory_frame」，最终返回「JsonNode」。
    // 上游调用：「IntakeAgentTurnService.dossierPatchWithMemoryFrame(IntakeAgentTurnResult)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.dossierPatchWithMemoryFrame(IntakeAgentTurnResult)」向下依次触达 「result.dossierPatch」、「source.isObject」、「objectMapper.createObjectNode」、「result.memoryFrame」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「IntakeAgentTurnService.dossierPatchWithMemoryFrame(IntakeAgentTurnResult)」负责主链路中的“卷宗Patch包含记忆帧”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode dossierPatchWithMemoryFrame(IntakeAgentTurnResult result) {
        JsonNode source = defaultObject(result.dossierPatch());
        ObjectNode patch =
                source.isObject()
                        ? ((ObjectNode) source).deepCopy()
                        : objectMapper.createObjectNode();
        JsonNode memoryFrame = defaultObject(result.memoryFrame());
        if (!memoryFrame.isEmpty()) {
            patch.set("memory_frame", memoryFrame);
        }
        return patch;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.defaultArray(JsonNode)」。
    // 具体功能：「IntakeAgentTurnService.defaultArray(JsonNode)」：构建默认数组；实际协作者为 「node.isNull」、「objectMapper.createArrayNode」，最终返回「JsonNode」。
    // 上游调用：「IntakeAgentTurnService.defaultArray(JsonNode)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」。
    // 下游影响：「IntakeAgentTurnService.defaultArray(JsonNode)」向下依次触达 「node.isNull」、「objectMapper.createArrayNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「IntakeAgentTurnService.defaultArray(JsonNode)」负责主链路中的“默认数组”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode defaultArray(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createArrayNode() : node;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.textOrDefault(JsonNode,String)」。
    // 具体功能：「IntakeAgentTurnService.textOrDefault(JsonNode,String)」：构建文本或者默认；实际协作者为 「node.isMissingNode」、「node.isNull」、「node.asText」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.textOrDefault(JsonNode,String)」的上游调用点包括 「IntakeAgentTurnService.upsertCurrentDossier」。
    // 下游影响：「IntakeAgentTurnService.textOrDefault(JsonNode,String)」向下依次触达 「node.isMissingNode」、「node.isNull」、「node.asText」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.textOrDefault(JsonNode,String)」负责主链路中的“文本或者默认”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.textOrDefault(String,String)」。
    // 具体功能：「IntakeAgentTurnService.textOrDefault(String,String)」：构建文本或者默认，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.textOrDefault(String,String)」的上游调用点包括 「IntakeAgentTurnService.upsertCurrentDossier」。
    // 下游影响：「IntakeAgentTurnService.textOrDefault(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.textOrDefault(String,String)」负责主链路中的“文本或者默认”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String textOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.audienceJson()」。
    // 具体功能：「IntakeAgentTurnService.audienceJson()」：构建受众 JSONJSON；实际协作者为 「json」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.audienceJson()」的上游调用点包括 「IntakeAgentTurnService.appendAgentMessage」。
    // 下游影响：「IntakeAgentTurnService.audienceJson()」向下依次触达 「json」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.audienceJson()」负责主链路中的“受众 JSONJSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String audienceJson() {
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.json(Object)」。
    // 具体功能：「IntakeAgentTurnService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.json(Object)」的上游调用点包括 「IntakeAgentTurnService.persistAgentTurn」、「IntakeAgentTurnService.upsertCurrentDossier」、「IntakeAgentTurnService.appendAgentMessage」、「IntakeAgentTurnService.audienceJson」。
    // 下游影响：「IntakeAgentTurnService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize intake agent turn", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.isParty(ActorRole)」。
    // 具体功能：「IntakeAgentTurnService.isParty(ActorRole)」：判断是否当事方，最终返回「boolean」。
    // 上游调用：「IntakeAgentTurnService.isParty(ActorRole)」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」。
    // 下游影响：「IntakeAgentTurnService.isParty(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「IntakeAgentTurnService.isParty(ActorRole)」负责主链路中的“当事方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.blank(String)」。
    // 具体功能：「IntakeAgentTurnService.blank(String)」：判断空白值，最终返回「boolean」。
    // 上游调用：「IntakeAgentTurnService.blank(String)」的上游调用点包括 「IntakeAgentTurnService.safeRun」。
    // 下游影响：「IntakeAgentTurnService.blank(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「IntakeAgentTurnService.blank(String)」负责主链路中的“”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.blankToNull(String)」。
    // 具体功能：「IntakeAgentTurnService.blankToNull(String)」：判断空白值空值，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.blankToNull(String)」的上游调用点包括 「IntakeAgentTurnService.sanitizeClaimResolutionSeed」、「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed」。
    // 下游影响：「IntakeAgentTurnService.blankToNull(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.blankToNull(String)」负责主链路中的“空值”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.blankToNullPreservingText(String)」。
    // 具体功能：「IntakeAgentTurnService.blankToNullPreservingText(String)」：判断空白值空值Preserving文本，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.blankToNullPreservingText(String)」的上游调用点包括 「IntakeAgentTurnService.sanitizeClaimResolutionSeed」。
    // 下游影响：「IntakeAgentTurnService.blankToNullPreservingText(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.blankToNullPreservingText(String)」负责主链路中的“空值Preserving文本”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String blankToNullPreservingText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.validIdentifierOrNull(String)」。
    // 具体功能：「IntakeAgentTurnService.validIdentifierOrNull(String)」：构建valid标识或者空值，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.validIdentifierOrNull(String)」的上游调用点包括 「IntakeAgentTurnService.sanitizeLobbySeed」、「IntakeAgentTurnService.sanitizeClaimResolutionSeed」、「IntakeAgentTurnService.sanitizeRespondentAttitudeSeed」。
    // 下游影响：「IntakeAgentTurnService.validIdentifierOrNull(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.validIdentifierOrNull(String)」负责主链路中的“valid标识或者空值”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String validIdentifierOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() >= 3 ? normalized : null;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.compactUuid()」。
    // 具体功能：「IntakeAgentTurnService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.compactUuid()」的上游调用点包括 「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.persistAgentTurn」、「IntakeAgentTurnService.upsertCurrentDossier」、「IntakeAgentTurnService.appendAgentMessage」。
    // 下游影响：「IntakeAgentTurnService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.turnNo(String)」。
    // 具体功能：「IntakeAgentTurnService.turnNo(String)」：构建轮次编号；实际协作者为 「Integer.parseInt」、「idempotencyKey.lastIndexOf」；不满足前置条件时抛出 「IllegalStateException」、「NumberFormatException」，最终返回「int」。
    // 上游调用：「IntakeAgentTurnService.turnNo(String)」的上游调用点包括 「IntakeAgentTurnService.finalizeResult」。
    // 下游影响：「IntakeAgentTurnService.turnNo(String)」向下依次触达 「Integer.parseInt」、「idempotencyKey.lastIndexOf」；计算结果以「int」交给调用方。
    // 系统意义：「IntakeAgentTurnService.turnNo(String)」负责主链路中的“轮次编号”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static int turnNo(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalStateException("agent run is missing intake turn identity");
        }
        int separator = idempotencyKey.lastIndexOf(':');
        try {
            int value = Integer.parseInt(idempotencyKey.substring(separator + 1));
            if (value < 1) throw new NumberFormatException("turn number must be positive");
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("invalid intake agent turn identity", failure);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeAgentTurnService.promptProfileId(ActorRole)」。
    // 具体功能：「IntakeAgentTurnService.promptProfileId(ActorRole)」：构建promptProfile标识；处理的关键状态/协议值包括 「:」、「:v1」，最终返回「String」。
    // 上游调用：「IntakeAgentTurnService.promptProfileId(ActorRole)」的上游调用点包括 「IntakeAgentTurnService.resolveSession」。
    // 下游影响：「IntakeAgentTurnService.promptProfileId(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeAgentTurnService.promptProfileId(ActorRole)」负责主链路中的“promptProfile标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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
