/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证房间消息并且事件，覆盖 「persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords」、「roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping」、「hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」、「hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」、「writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether」、「rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventView;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「RoomMessageAndEventServiceTest」。
// 类型职责：集中验证房间消息并且事件的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords」、「roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping」、「hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」、「hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」、「writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class RoomMessageAndEventServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseTimelineEventRepository eventRepository;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;
    @Mock private EvidenceAgentTurnService evidenceAgentTurnService;
    @Mock private AccessSessionResolver accessSessionResolver;
    @Mock private IntakeProgressService intakeProgressService;

    private CaseEventService eventService;
    private RoomMessageService messageService;
    private final SessionPermissionService permissionService = new SessionPermissionService();

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.setUp()」。
    // 具体功能：「RoomMessageAndEventServiceTest.setUp()」：在每个测试场景运行前创建「accessSessionResolver.resolve」、「invocation.getArgument」、「lenient」、「any」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「RoomMessageAndEventServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RoomMessageAndEventServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        eventService =
                new CaseEventService(
                        eventRepository,
                        caseRepository,
                        participantRepository,
                        accessSessionResolver,
                        permissionService,
                        new ObjectMapper(),
                        CLOCK);
        messageService =
                new RoomMessageService(
                        caseRepository,
                        roomRepository,
                        participantRepository,
                        messageRepository,
                        eventService,
                        intakeAgentTurnService,
                        evidenceAgentTurnService,
                        accessSessionResolver,
                        permissionService,
                        intakeProgressService,
                        CLOCK);
        lenient()
                .when(accessSessionResolver.resolve(any(), any()))
                .thenAnswer(
                        invocation -> {
                            String caseId = invocation.getArgument(0);
                            AuthenticatedActor actor = invocation.getArgument(1);
                            return accessSession(caseId, actor);
                        });
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords()」。
    // 具体功能：「RoomMessageAndEventServiceTest.persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords()」：复现“核对完整业务行为（场景方法「persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords」）”场景：驱动 「isTrue」、「RoomMessageEntity.class.isAnnotationPresent」、「CaseTimelineEventEntity.class.isAnnotationPresent」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「RoomMessageAndEventServiceTest.persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords() {
        assertThat(RoomMessageEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(CaseTimelineEventEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
    }

    @Test
    void intakeOpeningIsGatedByRespondentProgressAndDelegatedToTheIntakeOfficer() {
        FulfillmentCaseEntity dispute = intakeCase();
        AuthenticatedActor respondent =
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        RoomMessageView expected =
                new RoomMessageView(
                        "MESSAGE_RESPONDENT_OPENING",
                        dispute.getId(),
                        "ROOM_INTAKE",
                        1,
                        "CUSTOMER_SERVICE",
                        "dispute-intake-officer",
                        MessageType.AGENT_MESSAGE,
                        "请先回应发起方诉求。",
                        List.of(),
                        null,
                        CLOCK.instant());
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(intakeAgentTurnService.ensureRespondentOpening(
                        dispute.getId(),
                        RoomType.INTAKE,
                        respondent,
                        "TRACE_OPENING",
                        "REQ_OPENING"))
                .thenReturn(expected);

        Object opening =
                messageService.ensureOpening(
                        dispute.getId(),
                        RoomType.INTAKE,
                        respondent,
                        "TRACE_OPENING",
                        "REQ_OPENING");

        assertThat(opening).isSameAs(expected);
        verify(intakeProgressService).assertIntakePost(dispute, respondent);
        verify(intakeAgentTurnService)
                .ensureRespondentOpening(
                        dispute.getId(),
                        RoomType.INTAKE,
                        respondent,
                        "TRACE_OPENING",
                        "REQ_OPENING");
        verify(evidenceAgentTurnService, never())
                .ensureOpeningOrStart(any(), any(), any(), any(), any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping()」。
    // 具体功能：「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping()」：复现“核对完整业务行为（场景方法「roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping」）”场景：驱动 「caseRepository.findById」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「messageService.list」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MESSAGE_JUDGE_ROUND_1」、「CASE_ROOM_TEST」、「ROOM_HEARING」、「JUDGE」。
    // 上游调用：「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_JUDGE_ROUND_1」、「CASE_ROOM_TEST」、「ROOM_HEARING」、「JUDGE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping() {
        RoomMessageEntity judgeMessage =
                RoomMessageEntity.create(
                        "MESSAGE_JUDGE_ROUND_1",
                        "CASE_ROOM_TEST",
                        "ROOM_HEARING",
                        7,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第一轮陈述已封存，法官将提出下一轮定向问题。",
                        "[]",
                        "hearing-flow-v2-judge-message",
                        Instant.parse("2026-07-03T00:00:00Z"),
                        "TRACE_JUDGE_ROUND_1");
        when(caseRepository.findById(evidenceCase().getId()))
                .thenReturn(Optional.of(evidenceCase()));
        when(roomRepository.findByCaseIdAndRoomType(
                        evidenceCase().getId(), RoomType.HEARING))
                .thenReturn(
                        Optional.of(
                                CaseRoomEntity.open(
                                        "ROOM_HEARING",
                                        evidenceCase().getId(),
                                        RoomType.HEARING,
                                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                                        "system")));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc("ROOM_HEARING"))
                .thenReturn(List.of(judgeMessage));

        List<RoomMessageView> messages =
                messageService.list(
                        evidenceCase().getId(),
                        RoomType.HEARING,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().senderRole()).isEqualTo("JUDGE");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement()」。
    // 具体功能：「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement()」：复现“核对完整业务行为（场景方法「hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_HEARING」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_HEARING」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hearingPartyTextIsRejectedOutsideStructuredEndpoints() {
        FulfillmentCaseEntity dispute = evidenceCase();
        AuthenticatedActor user = new AuthenticatedActor("user-local", ActorRole.USER);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "用户补充：请核验签收位置和投递照片。",
                        List.of());

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.HEARING,
                                        command,
                                        user,
                                        "hearing-msg-1",
                                        "TRACE_HEARING_MSG"))
                .isInstanceOf(com.example.dispute.common.exception.ForbiddenException.class);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement()」。
    // 具体功能：「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement()」：复现“核对完整业务行为（场景方法「hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_HEARING」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_HEARING」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_HEARING",
                        dispute.getId(),
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        AuthenticatedActor user = new AuthenticatedActor("user-local", ActorRole.USER);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "hearing-evidence-ref-1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(4L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(8L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RoomMessageView message =
                messageService.post(
                        dispute.getId(),
                        RoomType.HEARING,
                        new RoomMessageCommand(
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                "用户补充提交 1 份庭审证据。",
                                List.of("EVIDENCE_HEARING_SUPPLEMENT_1")),
                        user,
                        "hearing-evidence-ref-1",
                        "TRACE_HEARING_EVIDENCE_REF");

        assertThat(message.messageType()).isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether()」。
    // 具体功能：「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether()」：复现“核对完整业务行为（场景方法「writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "msg-1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "The parcel is marked delivered, but I did not receive it.",
                        List.of());
        var message =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        command,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_1");

        assertThat(message.sequenceNo()).isEqualTo(1);
        assertThat(message.messageText()).contains("did not receive");
        ArgumentCaptor<CaseTimelineEventEntity> event =
                ArgumentCaptor.forClass(CaseTimelineEventEntity.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getSequenceNo()).isEqualTo(1);
        assertThat(event.getValue().getEventType()).isEqualTo("ROOM_MESSAGE_CREATED");
        verify(caseRepository, org.mockito.Mockito.times(2))
                .findByIdForUpdate(dispute.getId());

        ArgumentCaptor<RoomMessageEntity> persistedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(persistedMessage.capture());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "msg-1"))
                .thenReturn(Optional.of(persistedMessage.getValue()));

        var replayed =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        command,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_2");

        assertThat(replayed.id()).isEqualTo(message.id());
        verify(messageRepository, org.mockito.Mockito.times(1)).save(any());
        verify(eventRepository, org.mockito.Mockito.times(1)).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent()」。
    // 具体功能：「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent()」：复现“拒绝非法输入或越权操作（场景方法「rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageService.post」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「MESSAGE_EXISTING」。
    // 上游调用：「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「MESSAGE_EXISTING」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        RoomMessageEntity existing =
                roomMessage(
                        "MESSAGE_EXISTING",
                        "ROOM_EVIDENCE",
                        1,
                        ActorRole.USER,
                        MessageType.PARTY_TEXT,
                        "original statement",
                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\"]");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "same-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "attempted replacement",
                                                List.of()),
                                        new AuthenticatedActor(
                                                "user-local", ActorRole.USER),
                                        "same-key",
                                        "TRACE_CONFLICT"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different room message");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.platformReviewerCannotPostAPartyStatement()」。
    // 具体功能：「RoomMessageAndEventServiceTest.platformReviewerCannotPostAPartyStatement()」：复现“核对完整业务行为（场景方法「platformReviewerCannotPostAPartyStatement」）”场景：驱动 「caseRepository.findByIdForUpdate」、「messageService.post」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer-local」、「reviewer-party-message」、「TRACE_REVIEWER」。
    // 上游调用：「RoomMessageAndEventServiceTest.platformReviewerCannotPostAPartyStatement()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.platformReviewerCannotPostAPartyStatement()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.platformReviewerCannotPostAPartyStatement()」守住「房间协作与权限」的可执行规格，尤其防止 「reviewer-local」、「reviewer-party-message」、「TRACE_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void platformReviewerCannotPostAPartyStatement() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "Reviewer must not impersonate a party.",
                                                List.of()),
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER),
                                        "reviewer-party-message",
                                        "TRACE_REVIEWER"))
                .isInstanceOf(
                        com.example.dispute.common.exception.ForbiddenException.class);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted()」。
    // 具体功能：「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted()」：复现“核对完整业务行为（场景方法「intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_INTAKE」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_INTAKE」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_INTAKE",
                        dispute.getId(),
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "intake-msg-1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我没有收到包裹，希望退款。",
                        List.of("PHOTO_1"));
        messageService.post(
                dispute.getId(),
                RoomType.INTAKE,
                command,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "intake-msg-1",
                "TRACE_INTAKE");

        verify(intakeAgentTurnService)
                .continueFromParticipantMessage(
                        eq(dispute.getId()),
                        eq(RoomType.INTAKE),
                        eq(new AuthenticatedActor("user-local", ActorRole.USER)),
                        any(RoomMessageEntity.class),
                        eq("TRACE_INTAKE"),
                        eq("TRACE_INTAKE"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted()」。
    // 具体功能：「RoomMessageAndEventServiceTest.evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted()」：复现“核对完整业务行为（场景方法「evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "evidence-reference-msg"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_UPLOAD_1"));
        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                command,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "evidence-reference-msg",
                "TRACE_EVIDENCE_REFERENCE");

        verify(evidenceAgentTurnService)
                .continueFromParticipantMessage(
                        eq(dispute.getId()),
                        eq(RoomType.EVIDENCE),
                        eq(new AuthenticatedActor("merchant-local", ActorRole.MERCHANT)),
                        eq(command),
                        any(String.class),
                        eq(CLOCK.instant()),
                        eq("TRACE_EVIDENCE_REFERENCE"),
                        eq("TRACE_EVIDENCE_REFERENCE"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()」。
    // 具体功能：「RoomMessageAndEventServiceTest.evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()」：复现“核对完整业务行为（场景方法「evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-evidence-text"))
                .thenReturn(Optional.empty());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-evidence-reference"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId()))
                .thenReturn(0L, 1L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId()))
                .thenReturn(0L, 1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "User private evidence-room note for the clerk.",
                        List.of()),
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-evidence-text",
                "TRACE_USER_EVIDENCE_TEXT");
        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_MERCHANT_PRIVATE")),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-evidence-reference",
                "TRACE_MERCHANT_EVIDENCE_REFERENCE");

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messages.capture());

        List<String> userAudience =
                List.of(
                        new ObjectMapper()
                                .readValue(
                                        messages.getAllValues().get(0).getAudienceJson(),
                                        String[].class));
        assertThat(userAudience)
                .containsExactly(
                        "USER",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(userAudience).doesNotContain("MERCHANT");

        List<String> merchantAudience =
                List.of(
                        new ObjectMapper()
                                .readValue(
                                        messages.getAllValues().get(1).getAudienceJson(),
                                        String[].class));
        assertThat(merchantAudience)
                .containsExactly(
                        "MERCHANT",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(merchantAudience).doesNotContain("USER");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens()」。
    // 具体功能：「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens()」：复现“核对完整业务行为（场景方法「intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens」）”场景：驱动 「caseRepository.findByIdForUpdate」、「messageService.post」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「merchant-local」、「intake-merchant-rejected」、「TRACE_REJECT_NON_INITIATOR」。
    // 上游调用：「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens()」守住「房间协作与权限」的可执行规格，尤其防止 「merchant-local」、「intake-merchant-rejected」、「TRACE_REJECT_NON_INITIATOR」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void intakeRoomUsesThePartyProgressGate() {
        FulfillmentCaseEntity dispute = intakeCase();
        AuthenticatedActor respondent =
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        org.mockito.Mockito.doThrow(
                        new com.example.dispute.common.exception.ForbiddenException(
                                "respondent intake is not open"))
                .when(intakeProgressService)
                .assertIntakePost(dispute, respondent);

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "I am the other side and should respond in the evidence room.",
                                                List.of()),
                                        respondent,
                                        "intake-merchant-rejected",
                                        "TRACE_REJECT_NON_INITIATOR"))
                .isInstanceOf(
                        com.example.dispute.common.exception.ForbiddenException.class)
                .hasMessageContaining("respondent intake is not open");

        verify(roomRepository, org.mockito.Mockito.never())
                .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE);
        verify(messageRepository, org.mockito.Mockito.never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission()」。
    // 具体功能：「RoomMessageAndEventServiceTest.evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission()」：复现“核对完整业务行为（场景方法「evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-evidence-msg"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var message =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new RoomMessageCommand(
                                MessageType.PARTY_TEXT,
                                "Merchant uploads the inspection explanation in evidence room.",
                                List.of()),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-evidence-msg",
                        "TRACE_EVIDENCE_MERCHANT");

        assertThat(message.senderRole()).isEqualTo("MERCHANT");
        assertThat(message.messageText()).contains("inspection explanation");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles()」。
    // 具体功能：「RoomMessageAndEventServiceTest.evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles()」：复现“核对完整业务行为（场景方法「evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findByCaseIdAndIdempotencyKey」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-private-evidence-chat"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "This is a merchant-only explanation to the evidence clerk.",
                        List.of()),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-private-evidence-chat",
                "TRACE_EVIDENCE_PRIVATE");

        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getAudienceJson())
                .contains("MERCHANT")
                .contains("CUSTOMER_SERVICE")
                .contains("PLATFORM_REVIEWER")
                .doesNotContain("USER");

        ArgumentCaptor<CaseTimelineEventEntity> savedEvent =
                ArgumentCaptor.forClass(CaseTimelineEventEntity.class);
        verify(eventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getAudienceJson())
                .contains("MERCHANT")
                .contains("CUSTOMER_SERVICE")
                .contains("PLATFORM_REVIEWER")
                .doesNotContain("USER");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser()」。
    // 具体功能：「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser()」：复现“核对完整业务行为（场景方法「replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser」）”场景：驱动 「caseRepository.findById」、「eventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「eventService.replay」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「[\"USER\",\"MERCHANT\"]」、「shared」、「[\"MERCHANT\"]」、「merchant-private」。
    // 上游调用：「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser()」守住「房间协作与权限」的可执行规格，尤其防止 「[\"USER\",\"MERCHANT\"]」、「shared」、「[\"MERCHANT\"]」、「merchant-private」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenReturn(
                        List.of(
                                event(5, "[\"USER\",\"MERCHANT\"]", "shared"),
                                event(6, "[\"MERCHANT\"]", "merchant-private")));

        var replay =
                eventService.replay(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(replay)
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(5L);
        assertThat(replay.getFirst().payloadJson()).contains("shared");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole()」。
    // 具体功能：「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole()」：复现“核对完整业务行为（场景方法「roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole」）”场景：驱动 「caseRepository.findById」、「roomRepository.findByCaseIdAndRoomType」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(
                        List.of(
                                roomMessage(
                                        "MESSAGE_USER_OTHER_PRIVATE",
                                        room.getId(),
                                        1,
                                        ActorRole.USER,
                                        "user-other",
                                        MessageType.PARTY_TEXT,
                                        "same role but different user must not see this",
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-other\"]"),
                                roomMessage(
                                        "MESSAGE_USER_LOCAL_PRIVATE",
                                        room.getId(),
                                        2,
                                        ActorRole.USER,
                                        "user-local",
                                        MessageType.PARTY_TEXT,
                                        "current user's private evidence chat",
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-local\"]")));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(history)
                .extracting(RoomMessageView::messageText)
                .containsExactly("current user's private evidence chat");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole()」。
    // 具体功能：「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole()」：复现“核对完整业务行为（场景方法「replayFiltersPrivateEventsByExactActorWithinTheSameRole」）”场景：驱动 「caseRepository.findById」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「eventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「eventService.replay」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「[\"USER\",\"PLATFORM_REVIEWER\"]」、「[\"user-other\"]」、「same-role-other-private」。
    // 上游调用：「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「[\"USER\",\"PLATFORM_REVIEWER\"]」、「[\"user-other\"]」、「same-role-other-private」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void replayFiltersPrivateEventsByExactActorWithinTheSameRole() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenReturn(
                        List.of(
                                event(
                                        5,
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-other\"]",
                                        "same-role-other-private"),
                                event(
                                        6,
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-local\"]",
                                        "current-user-private")));

        var replay =
                eventService.replay(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(replay)
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(6L);
        assertThat(replay.getFirst().payloadJson()).contains("current-user-private");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent()」。
    // 具体功能：「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent()」：复现“核对完整业务行为（场景方法「subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent」）”场景：驱动 「caseRepository.findById」、「caseRepository.findByIdForUpdate」、「eventRepository.findByCaseIdAndEventKey」、「eventRepository.findMaxSequenceByCaseId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「[\"USER\",\"MERCHANT\"]」、「historical」、「live」、「event-6」。
    // 上游调用：「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent()」守住「房间协作与权限」的可执行规格，尤其防止 「[\"USER\",\"MERCHANT\"]」、「historical」、「live」、「event-6」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent() {
        FulfillmentCaseEntity dispute = evidenceCase();
        AtomicInteger replayQueries = new AtomicInteger();
        CaseTimelineEventEntity historical =
                event(5, "[\"USER\",\"MERCHANT\"]", "historical");
        CaseTimelineEventEntity live =
                event(6, "[\"USER\",\"MERCHANT\"]", "live");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(eventRepository.findByCaseIdAndEventKey(
                        dispute.getId(), "event-6"))
                .thenReturn(Optional.empty());
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(5L);
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenAnswer(
                        invocation -> {
                            if (replayQueries.getAndIncrement() == 0) {
                                eventService.recordLifecycleEvent(
                                        dispute.getId(),
                                        null,
                                        live.getEventType(),
                                        Map.of("text", "live"),
                                        "event-6",
                                        "system");
                                return List.of(historical);
                            }
                            return List.of(historical, live);
                        });

        SseEmitter emitter =
                eventService.subscribe(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        @SuppressWarnings("unchecked")
        Set<DataWithMediaType> earlyEvents =
                (Set<DataWithMediaType>)
                        ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(earlyEvents)
                .isNotNull()
                .extracting(DataWithMediaType::getData)
                .filteredOn(CaseEventView.class::isInstance)
                .map(CaseEventView.class::cast)
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(5L, 6L);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()」。
    // 具体功能：「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()」：复现“核对完整业务行为（场景方法「heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException」）”场景：驱动 「eventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「eventService.heartbeat」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「rawtypes」、「unchecked」、「subscriptions」。
    // 上游调用：「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「rawtypes」、「unchecked」、「subscriptions」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()
            throws ReflectiveOperationException {
        FulfillmentCaseEntity dispute = evidenceCase();
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity accessSession = accessSession(dispute.getId(), actor);
        Object subscription =
                subscription(
                        accessSession,
                        new ThrowingSseEmitter(
                                new IllegalStateException("client disconnected")),
                        0L);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, java.util.concurrent.CopyOnWriteArrayList> subscriptions =
                (Map<String, java.util.concurrent.CopyOnWriteArrayList>)
                        ReflectionTestUtils.getField(eventService, "subscriptions");
        subscriptions.put(
                dispute.getId(),
                new java.util.concurrent.CopyOnWriteArrayList<>(List.of(subscription)));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 0L))
                .thenReturn(List.of());

        assertThatCode(() -> eventService.heartbeat()).doesNotThrowAnyException();

        assertThat(subscriptions).doesNotContainKey(dispute.getId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty()」。
    // 具体功能：「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty()」：复现“核对完整业务行为（场景方法「roomHistoryFiltersReviewerOnlyMessagesForAParty」）”场景：驱动 「caseRepository.findById」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」、「messageService.list」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「MESSAGE_SHARED」。
    // 上游调用：「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE」、「2026-07-03T00:00:00Z」、「system」、「MESSAGE_SHARED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void roomHistoryFiltersReviewerOnlyMessagesForAParty() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(
                        List.of(
                                roomMessage(
                                        "MESSAGE_SHARED",
                                        room.getId(),
                                        1,
                                        ActorRole.USER,
                                        MessageType.PARTY_TEXT,
                                        "shared",
                                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\"]"),
                                roomMessage(
                                        "MESSAGE_REVIEWER",
                                        room.getId(),
                                        2,
                                        ActorRole.PLATFORM_REVIEWER,
                                        MessageType.REVIEWER_NOTE,
                                        "reviewer only",
                                        "[\"PLATFORM_REVIEWER\"]")));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(history)
                .extracting(item -> item.messageText())
                .containsExactly("shared");

        var adminHistory =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("admin-local", ActorRole.ADMIN));

        assertThat(adminHistory)
                .extracting(item -> item.messageText())
                .containsExactly("shared", "reviewer only");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty()」。
    // 具体功能：「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty()」：复现“核对完整业务行为（场景方法「intakeHistoryIsHiddenFromTheNonInitiatingParty」）”场景：驱动 「caseRepository.findById」、「roomRepository.findByCaseIdAndRoomType」、「messageService.list」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROOM_INTAKE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」。
    // 上游调用：「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty()」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_INTAKE」、「2026-07-03T00:00:00Z」、「system」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void intakeHistoryIsHiddenFromTheNonInitiatingParty() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_INTAKE",
                        dispute.getId(),
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.INTAKE,
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));

        assertThat(history).isEmpty();
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.event(long,String,String)」。
    // 具体功能：「RoomMessageAndEventServiceTest.event(long,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「event」）”组装或读取「event」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.event(long,String,String)」由本测试类中的 「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser」、「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole」、「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent」、「RoomMessageAndEventServiceTest.event」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.event(long,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.event(long,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「[]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseTimelineEventEntity event(
            long sequenceNo, String audienceJson, String payload) {
        return event(sequenceNo, audienceJson, "[]", payload);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.event(long,String,String,String)」。
    // 具体功能：「RoomMessageAndEventServiceTest.event(long,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「event」）”组装或读取「CaseTimelineEventEntity.create」、「Instant.parse」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.event(long,String,String,String)」由本测试类中的 「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser」、「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole」、「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent」、「RoomMessageAndEventServiceTest.event」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.event(long,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.event(long,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「EVENT_」、「CASE_ROOM_TEST」、「ROOM_MESSAGE_CREATED」、「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseTimelineEventEntity event(
            long sequenceNo, String audienceJson, String audienceActorIdsJson, String payload) {
        return CaseTimelineEventEntity.create(
                "EVENT_" + sequenceNo,
                "CASE_ROOM_TEST",
                null,
                sequenceNo,
                "ROOM_MESSAGE_CREATED",
                Instant.parse("2026-07-03T00:00:00Z"),
                "[]",
                "{\"text\":\"" + payload + "\"}",
                audienceJson,
                audienceActorIdsJson,
                "event-" + sequenceNo,
                "system");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,MessageType,String,String)」。
    // 具体功能：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,MessageType,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「roomMessage」）”组装或读取「roomMessage」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,MessageType,String,String)」由本测试类中的 「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent」、「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole」、「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty」、「RoomMessageAndEventServiceTest.roomMessage」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,MessageType,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,MessageType,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「reviewer-local」、「[]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static RoomMessageEntity roomMessage(
            String id,
            String roomId,
            long sequenceNo,
            ActorRole senderRole,
            MessageType messageType,
            String text,
            String audienceJson) {
        return roomMessage(
                id,
                roomId,
                sequenceNo,
                senderRole,
                senderRole == ActorRole.USER ? "user-local" : "reviewer-local",
                messageType,
                text,
                audienceJson,
                "[]");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,String,MessageType,String,String,String)」。
    // 具体功能：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,String,MessageType,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「roomMessage」）”组装或读取「RoomMessageEntity.create」、「Instant.parse」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,String,MessageType,String,String,String)」由本测试类中的 「RoomMessageAndEventServiceTest.rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent」、「RoomMessageAndEventServiceTest.roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole」、「RoomMessageAndEventServiceTest.roomHistoryFiltersReviewerOnlyMessagesForAParty」、「RoomMessageAndEventServiceTest.roomMessage」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,String,MessageType,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.roomMessage(String,String,long,ActorRole,String,MessageType,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_ROOM_TEST」、「[]」、「idem-」、「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static RoomMessageEntity roomMessage(
            String id,
            String roomId,
            long sequenceNo,
            ActorRole senderRole,
            String senderId,
            MessageType messageType,
            String text,
            String audienceJson,
            String audienceActorIdsJson) {
        return RoomMessageEntity.create(
                id,
                "CASE_ROOM_TEST",
                roomId,
                sequenceNo,
                senderRole == ActorRole.PLATFORM_REVIEWER
                        ? MessageSenderType.REVIEWER
                        : MessageSenderType.PARTY,
                senderRole.name(),
                senderId,
                audienceJson,
                audienceActorIdsJson,
                messageType,
                text,
                "[]",
                "idem-" + id,
                Instant.parse("2026-07-03T00:00:00Z"),
                "TRACE_" + id);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.accessSession(String,AuthenticatedActor)」。
    // 具体功能：「RoomMessageAndEventServiceTest.accessSession(String,AuthenticatedActor)」：作为测试辅助方法为“核对完整业务行为（场景方法「accessSession」）”组装或读取「CaseAccessSessionEntity.create」、「actor.role」、「actor.actorId」、「permissionLevel」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.accessSession(String,AuthenticatedActor)」由本测试类中的 「RoomMessageAndEventServiceTest.setUp」、「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.accessSession(String,AuthenticatedActor)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.accessSession(String,AuthenticatedActor)」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_」、「_」、「default」、「test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        return CaseAccessSessionEntity.create(
                "ACCESS_" + caseId + "_" + actor.role().name() + "_" + actor.actorId(),
                "default",
                caseId,
                actor.actorId(),
                actor.role(),
                permissionLevel(actor.role()),
                "test");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.subscription(CaseAccessSessionEntity,SseEmitter,long)」。
    // 具体功能：「RoomMessageAndEventServiceTest.subscription(CaseAccessSessionEntity,SseEmitter,long)」：作为测试辅助方法为“核对完整业务行为（场景方法「subscription」）”组装或读取「IllegalStateException」、「AtomicLong」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.subscription(CaseAccessSessionEntity,SseEmitter,long)」由本测试类中的 「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.subscription(CaseAccessSessionEntity,SseEmitter,long)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.subscription(CaseAccessSessionEntity,SseEmitter,long)」守住「房间协作与权限」的可执行规格，尤其防止 「Subscription」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Object subscription(
            CaseAccessSessionEntity accessSession, SseEmitter emitter, long lastSequence)
            throws ReflectiveOperationException {
        Class<?> subscriptionType = null;
        for (Class<?> nestedType : CaseEventService.class.getDeclaredClasses()) {
            if ("Subscription".equals(nestedType.getSimpleName())) {
                subscriptionType = nestedType;
                break;
            }
        }
        if (subscriptionType == null) {
            throw new IllegalStateException("CaseEventService.Subscription not found");
        }
        var constructor =
                subscriptionType.getDeclaredConstructor(
                        CaseAccessSessionEntity.class, SseEmitter.class, AtomicLong.class);
        constructor.setAccessible(true);
        return constructor.newInstance(accessSession, emitter, new AtomicLong(lastSequence));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】类型「ThrowingSseEmitter」。
    // 类型职责：承载ThrowingSseEmitter在当前业务模块中的规则与协作边界；本类型显式提供 「ThrowingSseEmitter」、「send」。
    // 协作关系：主要由 「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class ThrowingSseEmitter extends SseEmitter {
        private final RuntimeException failure;

        // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.ThrowingSseEmitter.ThrowingSseEmitter(RuntimeException)」。
        // 具体功能：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.ThrowingSseEmitter(RuntimeException)」：作为测试辅助方法为“核对完整业务行为（场景方法「ThrowingSseEmitter」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.ThrowingSseEmitter(RuntimeException)」由本测试类中的 「RoomMessageAndEventServiceTest.heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException」 调用。
        // 下游影响：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.ThrowingSseEmitter(RuntimeException)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.ThrowingSseEmitter(RuntimeException)」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        private ThrowingSseEmitter(RuntimeException failure) {
            super(60_000L);
            this.failure = failure;
        }

        // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.ThrowingSseEmitter.send(SseEventBuilder)」。
        // 具体功能：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.send(SseEventBuilder)」：作为「ThrowingSseEmitter」测试替身实现「send」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.send(SseEventBuilder)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.send(SseEventBuilder)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「RoomMessageAndEventServiceTest.ThrowingSseEmitter.send(SseEventBuilder)」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            throw failure;
        }
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.permissionLevel(ActorRole)」。
    // 具体功能：「RoomMessageAndEventServiceTest.permissionLevel(ActorRole)」：作为测试辅助方法为“核对完整业务行为（场景方法「permissionLevel」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.permissionLevel(ActorRole)」由本测试类中的 「RoomMessageAndEventServiceTest.accessSession」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.permissionLevel(ActorRole)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.permissionLevel(ActorRole)」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static PermissionLevel permissionLevel(ActorRole role) {
        return switch (role) {
            case USER -> PermissionLevel.PARTY_USER;
            case MERCHANT -> PermissionLevel.PARTY_MERCHANT;
            case CUSTOMER_SERVICE -> PermissionLevel.SERVICE_ASSIST;
            case PLATFORM_REVIEWER -> PermissionLevel.REVIEWER_ALL;
            case ADMIN -> PermissionLevel.ADMIN_ALL;
            case SYSTEM -> PermissionLevel.SYSTEM_ALL;
        };
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.evidenceCase()」。
    // 具体功能：「RoomMessageAndEventServiceTest.evidenceCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.evidenceCase()」由本测试类中的 「RoomMessageAndEventServiceTest.roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping」、「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」、「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」、「RoomMessageAndEventServiceTest.writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.evidenceCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.evidenceCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_ROOM_TEST」、「ORDER-ROOM」、「LOG-ROOM」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_ROOM_TEST",
                "ORDER-ROOM",
                null,
                "LOG-ROOM",
                "user-local",
                "merchant-local",
                "idem-room",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-ROOM",
                "external-adapter");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageAndEventServiceTest.intakeCase()」。
    // 具体功能：「RoomMessageAndEventServiceTest.intakeCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「intakeCase」）”组装或读取「FulfillmentCaseEntity.imported」，供本测试类的场景方法复用。
    // 上游调用：「RoomMessageAndEventServiceTest.intakeCase()」由本测试类中的 「RoomMessageAndEventServiceTest.intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted」、「RoomMessageAndEventServiceTest.intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens」、「RoomMessageAndEventServiceTest.intakeHistoryIsHiddenFromTheNonInitiatingParty」 调用。
    // 下游影响：「RoomMessageAndEventServiceTest.intakeCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageAndEventServiceTest.intakeCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_INTAKE_ROOM_TEST」、「ORDER-ROOM」、「LOG-ROOM」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity intakeCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_INTAKE_ROOM_TEST",
                "ORDER-ROOM",
                null,
                "LOG-ROOM",
                "user-local",
                "merchant-local",
                "idem-room-intake",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "OMS",
                "EXT-ROOM-INTAKE",
                "external-adapter");
    }
}
