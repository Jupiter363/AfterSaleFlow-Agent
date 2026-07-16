/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证接待Agent轮次，覆盖 「initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded」、「initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」、「participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」、「participantTurnDoesNotRepeatInitialFormFacts」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeAgentTurnClient;
import com.example.dispute.room.application.IntakeAgentTurnCommand;
import com.example.dispute.room.application.IntakeAgentTurnResult;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「IntakeAgentTurnServiceTest」。
// 类型职责：集中验证接待Agent轮次的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded」、「initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」、「participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class IntakeAgentTurnServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private AccessSessionResolver accessSessionResolver;
    @Mock private AgentSessionResolver agentSessionResolver;
    @Mock private SessionPermissionService permissionService;
    @Mock private IntakeAgentTurnClient client;

    private ObjectMapper objectMapper;
    private IntakeAgentTurnService service;

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.setUp()」。
    // 具体功能：「IntakeAgentTurnServiceTest.setUp()」：在每个测试场景运行前创建「accessSessionResolver.resolve」、「agentSessionResolver.resolve」、「invocation.getArgument」、「lenient」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「IntakeAgentTurnServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「IntakeAgentTurnServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new IntakeAgentTurnService(
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
                        CLOCK);
        lenient()
                .when(accessSessionResolver.resolve(any(), any()))
                .thenAnswer(
                        invocation ->
                                accessSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1)));
        lenient()
                .when(agentSessionResolver.resolve(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                agentSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2),
                                        invocation.getArgument(3),
                                        invocation.getArgument(4)));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot()」。
    // 具体功能：「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot()」：复现“核对完整业务行为（场景方法「initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TRACE_1」、「REQ_1」、「我先确认一下：你是说订单显示已签收但没有收到，对吗？」、「current_outcome」。
    // 上游调用：「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot()」守住「房间协作与权限」的可执行规格，尤其防止 「TRACE_1」、「REQ_1」、「我先确认一下：你是说订单显示已签收但没有收到，对吗？」、「current_outcome」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_1"), eq("REQ_1")))
                .thenReturn(
                        result(
                                "我先确认一下：你是说订单显示已签收但没有收到，对吗？",
                                Map.of("current_outcome", "ASK_FOR_CLARIFICATION")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String originalStatement = "  我没有收到这个包裹，请核验签收记录。\n";
        service.startInitialTurn(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeLobbySeed(
                        "ORDER-1",
                        "AFTER-1",
                        "LOG-1",
                        "USER",
                        "物流轨迹显示订单已签收，但发起方表示未收到包裹。",
                        null,
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND",
                                null,
                                "蓝牙耳机 1 件",
                                "未收到商品，希望退款。",
                                originalStatement),
                        null),
                "TRACE_1",
                "REQ_1");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_1"), eq("REQ_1"));
        assertThat(command.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(command.getValue().turnSource()).isEqualTo("EXTERNAL_IMPORT");
        assertThat(command.getValue().currentUserMessage()).isNull();
        assertThat(command.getValue().initialCaseFacts().formSource())
                .isEqualTo("EXTERNAL_IMPORT");
        assertThat(command.getValue().initialCaseFacts().formDescription())
                .isEqualTo("物流轨迹显示订单已签收，但发起方表示未收到包裹。");
        assertThat(command.getValue().initialCaseFacts().claimResolutionSeed().requestedResolution())
                .isEqualTo("REFUND");
        assertThat(command.getValue().initialCaseFacts().claimResolutionSeed().originalStatement())
                .isNull();
        assertThat(command.getValue().initialCaseFacts().respondentAttitudeSeed()).isNull();
        assertThat(command.getValue().recentDialogueMessages()).isEmpty();
        assertThat(command.getValue().initiatorStatementTranscript()).isEmpty();
        assertThat(command.getValue().previousCaseDetail().isObject()).isTrue();
        assertThat(command.getValue().agentContext().actorId()).isEqualTo("user-local");
        assertThat(command.getValue().agentContext().actorRole()).isEqualTo("USER");
        assertThat(command.getValue().agentContext().agentKey())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(command.getValue().agentContext().agentSessionId()).isNotBlank();
        assertThat(command.getValue().agentContext().scopeType())
                .isEqualTo("INTAKE_PARTY_PRIVATE");

        ArgumentCaptor<RoomTurnMemoryEntity> memory =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository).save(memory.capture());
        assertThat(memory.getValue().getAnswerContent()).isNull();
        assertThat(memory.getValue().getAgentRole())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(memory.getValue().getTurnNo()).isEqualTo(1);
        assertThat(memory.getValue().getScrollSnapshotJson())
                .contains("ASK_FOR_CLARIFICATION");
        assertThat(memory.getValue().getDossierPatchJson())
                .contains("\"memory_frame\"")
                .contains("\"prompt_memory\":\"short memory\"");

        ArgumentCaptor<CaseIntakeDossierEntity> currentDossier =
                ArgumentCaptor.forClass(CaseIntakeDossierEntity.class);
        verify(intakeDossierRepository).save(currentDossier.capture());
        assertThat(currentDossier.getValue().getCaseId()).isEqualTo(dispute.getId());
        assertThat(currentDossier.getValue().getDossierVersion()).isEqualTo(1);
        assertThat(currentDossier.getValue().getQualityScore()).isEqualTo(86);
        assertThat(currentDossier.getValue().isReadyForNextStep()).isTrue();
        assertThat(currentDossier.getValue().getDossierJson())
                .contains("intake_case_detail.v1")
                .contains("ASK_FOR_CLARIFICATION");

        ArgumentCaptor<RoomMessageEntity> roomMessages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(roomMessages.capture());
        assertThat(roomMessages.getValue().getMessageType())
                .isEqualTo(MessageType.AGENT_MESSAGE);
    }

    @Test
    void respondentOpeningUsesOnlyTheFrozenMatrixAndDoesNotMutateTheDossier() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        String frozenDossier =
                """
                {
                  "schema_version": "intake_case_detail.v1",
                  "claim_resolution": {
                    "original_statement": "INITIATOR_PRIVATE_SECRET"
                  },
                  "case_fact_matrix": {
                    "schema_version": "case_fact_matrix.v2",
                    "matrix_kind": "INITIATOR_FROZEN",
                    "party_map": {
                      "initiator_role": "USER",
                      "respondent_role": "MERCHANT"
                    },
                    "case_overview": {
                      "neutral_summary": "用户主张页面承诺包含基础安装。"
                    },
                    "claims": {
                      "initiator_claim": {
                        "position_summary": "用户要求退还150元安装费。",
                        "reason_summary": "页面承诺包含基础安装。",
                        "source_refs": ["MESSAGE_PRIVATE_SECRET"]
                      }
                    },
                    "fact_rows": [
                      {
                        "fact_id": "FACT_INSTALL_SCOPE",
                        "materiality": "CORE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "positions": {
                          "USER": {"stance": "CONFIRM"},
                          "MERCHANT": {"stance": "NOT_ADDRESSED"}
                        }
                      }
                    ]
                  }
                }
                """;
        dispute.admitToEvidence(
                "SIGNED_NOT_RECEIVED",
                RiskLevel.HIGH,
                frozenDossier,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"),
                "user-local");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(3L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var opening =
                service.ensureRespondentOpening(
                        dispute.getId(),
                        RoomType.INTAKE,
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "TRACE_RESPONDENT_OPENING",
                        "REQ_RESPONDENT_OPENING");

        assertThat(opening.sequenceNo()).isEqualTo(4L);
        assertThat(opening.messageText())
                .contains("只代表发起方陈述，尚未经过证据核验")
                .contains("用户要求退还150元安装费")
                .contains("商品页面是否标注包含基础安装")
                .doesNotContain("INITIATOR_PRIVATE_SECRET")
                .doesNotContain("MESSAGE_PRIVATE_SECRET");
        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getAudienceActorIdsJson())
                .isEqualTo("[\"merchant-local\"]");
        assertThat(savedMessage.getValue().getIdempotencyKey())
                .startsWith("agent-intake-respondent-opening:");
        verify(intakeDossierRepository, never()).save(any());
        verify(client, never()).run(any(), any(), any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()」。
    // 具体功能：「IntakeAgentTurnServiceTest.initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()」：复现“核对完整业务行为（场景方法「initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded」）”场景：驱动 「transactional.propagation」、「getAnnotation」、「IntakeAgentTurnService.class.getMethod」、「assertThat(transactional).isNotNull」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「startInitialTurn」。
    // 上游调用：「IntakeAgentTurnServiceTest.initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()」守住「房间协作与权限」的可执行规格，尤其防止 「startInitialTurn」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()
            throws NoSuchMethodException {
        Transactional transactional =
                IntakeAgentTurnService.class
                        .getMethod(
                                "startInitialTurn",
                                String.class,
                                AuthenticatedActor.class,
                                IntakeLobbySeed.class,
                                String.class,
                                String.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent()」。
    // 具体功能：「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent()」：复现“核对完整业务行为（场景方法「initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TRACE_SHORT_INITIAL」、「REQ_SHORT_INITIAL」、「我会先补齐缺失的订单、售后和物流引用。」、「admission_recommendation」。
    // 上游调用：「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent()」守住「房间协作与权限」的可执行规格，尤其防止 「TRACE_SHORT_INITIAL」、「REQ_SHORT_INITIAL」、「我会先补齐缺失的订单、售后和物流引用。」、「admission_recommendation」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent() {
        FulfillmentCaseEntity dispute = manuallyCreatedIntakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_SHORT_INITIAL"), eq("REQ_SHORT_INITIAL")))
                .thenReturn(
                        result(
                                "我会先补齐缺失的订单、售后和物流引用。",
                                Map.of("admission_recommendation", "NEED_MORE_INFO")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.startInitialTurn(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeLobbySeed(
                        "2",
                        "2",
                        "2",
                        "USER",
                        "物流显示签收，但我没有收到包裹。",
                        null),
                "TRACE_SHORT_INITIAL",
                "REQ_SHORT_INITIAL");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_SHORT_INITIAL"), eq("REQ_SHORT_INITIAL"));
        assertThat(command.getValue().turnSource()).isEqualTo("FORM_SUBMISSION");
        assertThat(command.getValue().initialCaseFacts().formSource())
                .isEqualTo("FORM_SUBMISSION");
        assertThat(command.getValue().initialCaseFacts().orderReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().afterSalesReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().logisticsReference()).isNull();
        assertThat(command.getValue().initiatorStatementTranscript()).isEmpty();
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent()」。
    // 具体功能：「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent()」：复现“核对完整业务行为（场景方法「participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MEMORY_PREVIOUS」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「我先确认一下签收争议。」。
    // 上游调用：「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent()」守住「房间协作与权限」的可执行规格，尤其防止 「MEMORY_PREVIOUS」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「我先确认一下签收争议。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        RoomTurnMemoryEntity previous =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS",
                        dispute.getId(),
                        RoomType.INTAKE,
                        1,
                        "dispute-intake-officer",
                        "DISPUTE_INTAKE_OFFICER",
                        "我先确认一下签收争议。",
                        "{}",
                        "{\"current_outcome\":\"ASK_FOR_CLARIFICATION\"}",
                        "[]",
                        "RUN_1");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.of(previous));
        when(client.run(any(), eq("TRACE_2"), eq("REQ_2")))
                .thenReturn(
                        result(
                                "收到，我已经把你的诉求更新为退款，并准备继续核验商家侧信息。",
                                Map.of("requested_outcome", "REFUND")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_EXISTING",
                        dispute.getId(),
                        RoomType.INTAKE,
                        "{\"schema_version\":\"intake_case_detail.v1\"}",
                        60,
                        false,
                        "NEED_MORE_INFO",
                        1,
                        "dispute-intake-officer")));
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                participantMessage(
                        dispute,
                        room,
                        2,
                        "MESSAGE_CURRENT_2",
                        "我确认没收到，诉求是退款。"),
                "TRACE_2",
                "REQ_2");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_2"), eq("REQ_2"));
        assertThat(command.getValue().turnSource()).isEqualTo("ROOM_MESSAGE");
        assertThat(command.getValue().currentUserMessage().messageId()).isEqualTo("MESSAGE_CURRENT_2");
        assertThat(command.getValue().currentUserMessage().text()).contains("退款");
        assertThat(command.getValue().initialCaseFacts()).isNull();
        assertThat(command.getValue().previousCaseDetail().path("current_outcome").asText())
                .isEqualTo("ASK_FOR_CLARIFICATION");
        assertThat(command.getValue().recentDialogueMessages()).hasSizeLessThanOrEqualTo(5);
        assertThat(command.getValue().recentDialogueMessages().size() + 1)
                .isLessThanOrEqualTo(6);

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        assertThat(memories.getAllValues().get(0).getAnswerRole()).isEqualTo("USER");
        assertThat(memories.getAllValues().get(0).getAnswerContent()).contains("退款");
        assertThat(memories.getAllValues().get(1).getAgentRole())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(memories.getAllValues().get(1).getScrollSnapshotJson())
                .contains("REFUND");

        ArgumentCaptor<CaseIntakeDossierEntity> currentDossier =
                ArgumentCaptor.forClass(CaseIntakeDossierEntity.class);
        verify(intakeDossierRepository).save(currentDossier.capture());
        assertThat(currentDossier.getValue().getDossierVersion()).isEqualTo(2);
        assertThat(currentDossier.getValue().getSourceTurnNo()).isEqualTo(2);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow()」。
    // 具体功能：「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow()」：复现“核对完整业务行为（场景方法「participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「DISPUTE_INTAKE_OFFICER」、「intake-user-v1」、「MEMEO_DEFAULT」。
    // 上游调用：「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「DISPUTE_INTAKE_OFFICER」、「intake-user-v1」、「MEMEO_DEFAULT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity accessSession = accessSession(dispute.getId(), actor);
        AgentConversationSessionEntity agentSession =
                agentSession(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "intake-user-v1",
                        "MEMEO_DEFAULT");
        List<RoomTurnMemoryEntity> participantMemories = new ArrayList<>();
        List<String> expectedStatements = new ArrayList<>();
        for (int turnNo = 1; turnNo <= 11; turnNo++) {
            String statement = "第 " + turnNo + " 条发起方原始输入";
            expectedStatements.add(statement);
            participantMemories.add(
                    RoomTurnMemoryEntity.participantTurn(
                            "MEMORY_HISTORY_" + turnNo,
                            dispute.getId(),
                            RoomType.INTAKE,
                            turnNo,
                            actor.actorId(),
                            actor.role().name(),
                            statement,
                            agentSession,
                            accessSession,
                            "{}"));
        }
        List<RoomMessageEntity> visibleHistory = new ArrayList<>();
        for (int round = 1; round <= 4; round++) {
            long agentSequence = round * 2L - 1L;
            visibleHistory.add(
                    agentMessage(
                            dispute,
                            room,
                            agentSequence,
                            "MESSAGE_HISTORY_AGENT_" + round,
                            "历史接待官回复 " + round));
            if (round < 4) {
                visibleHistory.add(
                        participantMessage(
                                dispute,
                                room,
                                agentSequence + 1,
                                "MESSAGE_HISTORY_USER_" + round,
                                "历史用户陈述 " + round));
            }
        }
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(11);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(memoryRepository
                        .findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(any()))
                .thenAnswer(invocation -> List.copyOf(participantMemories));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(visibleHistory);
        when(client.run(any(), eq("TRACE_TRANSCRIPT"), eq("REQ_TRANSCRIPT")))
                .thenReturn(
                        result(
                                "已记录全部原始陈述。",
                                Map.of("requested_outcome", "REFUND")));
        when(memoryRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            RoomTurnMemoryEntity saved = invocation.getArgument(0);
                            if (saved.getAnswerContent() != null) {
                                participantMemories.add(saved);
                            }
                            return saved;
                        });
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String latestStatement = "\n我的第 12 条原始补充，保留首尾空白。  ";
        expectedStatements.add(latestStatement);
        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                actor,
                participantMessage(
                        dispute,
                        room,
                        8,
                        "MESSAGE_CURRENT_12",
                        latestStatement),
                "TRACE_TRANSCRIPT",
                "REQ_TRANSCRIPT");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_TRANSCRIPT"), eq("REQ_TRANSCRIPT"));
        assertThat(command.getValue().recentDialogueMessages())
                .hasSize(5)
                .first()
                .satisfies(
                        message -> {
                            assertThat(message.sequenceNo()).isEqualTo(3);
                            assertThat(message.role()).isEqualTo("AGENT");
                        });
        assertThat(command.getValue().recentDialogueMessages()).last()
                .satisfies(
                        message -> {
                            assertThat(message.sequenceNo()).isEqualTo(7);
                            assertThat(message.role()).isEqualTo("AGENT");
                        });
        assertThat(command.getValue().recentDialogueMessages())
                .extracting(message -> message.role())
                .containsExactly("AGENT", "USER", "AGENT", "USER", "AGENT");
        assertThat(command.getValue().recentDialogueMessages().size() + 1).isEqualTo(6);
        assertThat(command.getValue().initiatorStatementTranscript())
                .hasSize(12)
                .extracting(message -> message.text())
                .containsExactlyElementsOf(expectedStatements);
        assertThat(command.getValue().initiatorStatementTranscript())
                .extracting(message -> message.messageId())
                .containsExactly(
                        "INTAKE_TURN_1",
                        "INTAKE_TURN_2",
                        "INTAKE_TURN_3",
                        "INTAKE_TURN_4",
                        "INTAKE_TURN_5",
                        "INTAKE_TURN_6",
                        "INTAKE_TURN_7",
                        "INTAKE_TURN_8",
                        "INTAKE_TURN_9",
                        "INTAKE_TURN_10",
                        "INTAKE_TURN_11",
                        "INTAKE_TURN_12");
        assertThat(command.getValue().initiatorStatementTranscript())
                .allSatisfy(message -> assertThat(message.role()).isEqualTo("USER"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts()」。
    // 具体功能：「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts()」：复现“核对完整业务行为（场景方法「participantTurnDoesNotRepeatInitialFormFacts」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_INTAKE_SHORT_REFS」、「2」、「user-local」、「merchant-local」。
    // 上游调用：「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_INTAKE_SHORT_REFS」、「2」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void participantTurnDoesNotRepeatInitialFormFacts() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTAKE_SHORT_REFS",
                        "2",
                        "2",
                        "2",
                        "user-local",
                        "merchant-local",
                        "idem-short-refs",
                        "SIGNED_NOT_RECEIVED",
                        "履约问题待处理",
                        "物流显示签收，但我没有收到包裹。",
                        RiskLevel.MEDIUM,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-short-refs",
                        "system");
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_SHORT"), eq("REQ_SHORT")))
                .thenReturn(
                        result(
                                "我会继续补齐缺失的订单、售后和物流引用。",
                                Map.of("admission_recommendation", "NEED_MORE_INFO")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                participantMessage(
                        dispute,
                        room,
                        2,
                        "MESSAGE_SHORT",
                        "我补充：希望平台核实物流并退款。"),
                "TRACE_SHORT",
                "REQ_SHORT");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_SHORT"), eq("REQ_SHORT"));
        assertThat(command.getValue().initialCaseFacts()).isNull();
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory()」。
    // 具体功能：「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory()」：复现“核对完整业务行为（场景方法「participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MEMORY_PREVIOUS」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「我先确认一下签收争议。」。
    // 上游调用：「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory()」守住「房间协作与权限」的可执行规格，尤其防止 「MEMORY_PREVIOUS」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「我先确认一下签收争议。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        RoomTurnMemoryEntity previous =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS",
                        dispute.getId(),
                        RoomType.INTAKE,
                        1,
                        "dispute-intake-officer",
                        "DISPUTE_INTAKE_OFFICER",
                        "我先确认一下签收争议。",
                        "{}",
                        "{\"current_outcome\":\"ASK_FOR_CLARIFICATION\"}",
                        "[]",
                        "RUN_1");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.of(previous));
        when(client.run(any(), eq("TRACE_FAIL"), eq("REQ_FAIL")))
                .thenThrow(new RuntimeException("python returned 422"));

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        participantMessage(
                                                dispute,
                                                room,
                                                2,
                                                "MESSAGE_FAIL",
                                                "我继续补充：商家回复让我找快递，但快递说只能让商家发起核查。"),
                                        "TRACE_FAIL",
                                        "REQ_FAIL"))
                .isInstanceOf(
                        com.example.dispute.common.exception.AgentExecutionException.class)
                .hasMessageContaining("intake agent model request failed");

        verify(memoryRepository, org.mockito.Mockito.times(1)).save(any());
        verify(client).run(any(), eq("TRACE_FAIL"), eq("REQ_FAIL"));
        verify(intakeDossierRepository, org.mockito.Mockito.never()).save(any());
        verify(messageRepository, org.mockito.Mockito.never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.result(String,Map)」。
    // 具体功能：「IntakeAgentTurnServiceTest.result(String,Map)」：作为测试辅助方法为“核对完整业务行为（场景方法「result」）”组装或读取「IntakeAgentTurnResult」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.result(String,Map)」由本测试类中的 「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」、「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.result(String,Map)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.result(String,Map)」守住「房间协作与权限」的可执行规格，尤其防止 「schema_version」、「intake_case_detail.v1」、「case_story」、「title」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private IntakeAgentTurnResult result(String utterance, Map<String, Object> scrollSnapshot) {
        Map<String, Object> caseDetail =
                Map.of(
                        "schema_version", "intake_case_detail.v1",
                        "case_story", Map.of("title", "签收未收到"),
                        "intake_quality",
                                Map.of(
                                        "score", 86,
                                        "threshold", 80,
                                        "ready_for_next_step", true),
                        "admission", Map.of("recommendation", "ACCEPTED"),
                        "legacy", scrollSnapshot);
        return new IntakeAgentTurnResult(
                utterance,
                objectMapper.valueToTree(Map.of("summary", utterance)),
                objectMapper.valueToTree(caseDetail),
                objectMapper.valueToTree(List.of(Map.of("op", "UPSERT_CARD"))),
                objectMapper.valueToTree(Map.of("prompt_memory", "short memory")),
                "CONTINUE",
                List.of(),
                false,
                "STUB",
                0.86);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.participantMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」。
    // 具体功能：「IntakeAgentTurnServiceTest.participantMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「participantMessage」）”组装或读取「RoomMessageEntity.create」、「CLOCK.instant」、「dispute.getId」、「room.getId」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.participantMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」由本测试类中的 「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」、「IntakeAgentTurnServiceTest.participantTurnDoesNotRepeatInitialFormFacts」、「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.participantMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.participantMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「[\"user-local\"]」、「[]」、「test-intake-message:」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static RoomMessageEntity participantMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            long sequence,
            String messageId,
            String text) {
        return RoomMessageEntity.create(
                messageId,
                dispute.getId(),
                room.getId(),
                sequence,
                MessageSenderType.PARTY,
                ActorRole.USER.name(),
                "user-local",
                "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                "[\"user-local\"]",
                MessageType.PARTY_TEXT,
                text,
                "[]",
                "test-intake-message:" + messageId,
                CLOCK.instant(),
                "TRACE_TEST");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.agentMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」。
    // 具体功能：「IntakeAgentTurnServiceTest.agentMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「agentMessage」）”组装或读取「RoomMessageEntity.create」、「CLOCK.instant」、「dispute.getId」、「room.getId」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.agentMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」由本测试类中的 「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.agentMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.agentMessage(FulfillmentCaseEntity,CaseRoomEntity,long,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「CUSTOMER_SERVICE」、「dispute-intake-officer」、「[\"user-local\"]」、「[]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static RoomMessageEntity agentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            long sequence,
            String messageId,
            String text) {
        return RoomMessageEntity.create(
                messageId,
                dispute.getId(),
                room.getId(),
                sequence,
                MessageSenderType.AGENT,
                "CUSTOMER_SERVICE",
                "dispute-intake-officer",
                "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                "[\"user-local\"]",
                MessageType.AGENT_MESSAGE,
                text,
                "[]",
                "agent-intake-turn:test:" + sequence,
                CLOCK.instant(),
                "TRACE_TEST");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」。
    // 具体功能：「IntakeAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」：作为测试辅助方法为“核对完整业务行为（场景方法「accessSession」）”组装或读取「CaseAccessSessionEntity.create」、「actor.role」、「actor.actorId」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」由本测试类中的 「IntakeAgentTurnServiceTest.setUp」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        PermissionLevel level =
                actor.role() == ActorRole.MERCHANT
                        ? PermissionLevel.PARTY_MERCHANT
                        : PermissionLevel.PARTY_USER;
        return CaseAccessSessionEntity.create(
                "ACCESS_" + actor.actorId(),
                "default",
                caseId,
                actor.actorId(),
                actor.role(),
                level,
                actor.actorId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「IntakeAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「agentSession」）”组装或读取「AgentConversationSessionEntity.create」、「accessSession.getActorId」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」由本测试类中的 「IntakeAgentTurnServiceTest.setUp」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「AGENT_SESSION_」、「_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentConversationSessionEntity agentSession(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return AgentConversationSessionEntity.create(
                "AGENT_SESSION_" + accessSession.getActorId() + "_" + roomType.name(),
                accessSession,
                roomType,
                agentKey,
                promptProfileId,
                memoryPolicyId,
                accessSession.getActorId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.intakeCase()」。
    // 具体功能：「IntakeAgentTurnServiceTest.intakeCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「intakeCase」）”组装或读取「FulfillmentCaseEntity.imported」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.intakeCase()」由本测试类中的 「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」、「IntakeAgentTurnServiceTest.participantTurnFailsClosedWithoutPersistingSyntheticAgentMemory」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.intakeCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.intakeCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_INTAKE_AGENT」、「ORDER-1」、「AFTER-1」、「LOG-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity intakeCase() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTAKE_AGENT",
                        "ORDER-1",
                        "AFTER-1",
                        "LOG-1",
                        "user-local",
                        "merchant-local",
                        "idem-intake-agent",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "用户表示订单显示签收但没有收到包裹。",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-1",
                        "system");
        return dispute;
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.manuallyCreatedIntakeCase()」。
    // 具体功能：「IntakeAgentTurnServiceTest.manuallyCreatedIntakeCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「manuallyCreatedIntakeCase」）”组装或读取「FulfillmentCaseEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.manuallyCreatedIntakeCase()」由本测试类中的 「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.manuallyCreatedIntakeCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.manuallyCreatedIntakeCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_MANUAL_INTAKE_AGENT」、「ORDER-1」、「AFTER-1」、「LOG-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity manuallyCreatedIntakeCase() {
        return FulfillmentCaseEntity.create(
                "CASE_MANUAL_INTAKE_AGENT",
                "ORDER-1",
                "AFTER-1",
                "LOG-1",
                "user-local",
                "merchant-local",
                ActorRole.USER,
                "idem-manual-intake-agent",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户通过争议表单说明订单显示签收但未收到包裹。",
                RiskLevel.HIGH,
                "user-local");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeAgentTurnServiceTest.intakeRoom(FulfillmentCaseEntity)」。
    // 具体功能：「IntakeAgentTurnServiceTest.intakeRoom(FulfillmentCaseEntity)」：作为测试辅助方法为“核对完整业务行为（场景方法「intakeRoom」）”组装或读取「CaseRoomEntity.open」、「OffsetDateTime.parse」、「dispute.getId」，供本测试类的场景方法复用。
    // 上游调用：「IntakeAgentTurnServiceTest.intakeRoom(FulfillmentCaseEntity)」由本测试类中的 「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」、「IntakeAgentTurnServiceTest.participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」 调用。
    // 下游影响：「IntakeAgentTurnServiceTest.intakeRoom(FulfillmentCaseEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeAgentTurnServiceTest.intakeRoom(FulfillmentCaseEntity)」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_INTAKE_AGENT」、「2026-07-05T00:00:00Z」、「system」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseRoomEntity intakeRoom(FulfillmentCaseEntity dispute) {
        return CaseRoomEntity.open(
                "ROOM_INTAKE_AGENT",
                dispute.getId(),
                RoomType.INTAKE,
                OffsetDateTime.parse("2026-07-05T00:00:00Z"),
                "system");
    }
}
