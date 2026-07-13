/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证接待房间Integration，覆盖 「acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「IntakeRoomServiceIntegrationTest」。
// 类型职责：集中验证接待房间Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    IntakeRoomService.class,
    ParticipantService.class,
    NotificationService.class,
    CaseLifecycleNotificationService.class,
    CaseEventService.class,
    RoomMessageService.class,
    IntakeRoomServiceIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class IntakeRoomServiceIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_intake_room")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「IntakeRoomServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「IntakeRoomServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「IntakeRoomServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeRoomServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「房间协作与权限」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_intake_room");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private IntakeRoomService service;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseParticipantRepository participantRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;
    @Autowired private RoomMessageService roomMessageService;
    @Autowired private RoomMessageRepository messageRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @MockitoBean private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @MockitoBean private IntakeAgentTurnService intakeAgentTurnService;
    @MockitoBean private AccessSessionResolver accessSessionResolver;
    @MockitoBean private SessionPermissionService sessionPermissionService;
    @MockitoBean private EvidenceAgentTurnService evidenceAgentTurnService;
    @MockitoBean private HearingRoundService hearingRoundService;

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline()」。
    // 具体功能：「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline()」：复现“核对完整业务行为（场景方法「acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」）”场景：驱动 「caseRepository.saveAndFlush」、「service.confirm」、「caseRepository.flush」、「caseRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_INTEGRATION」、「ORDER-INTEGRATION」、「LOG-INTEGRATION」、「user-local」。
    // 上游调用：「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_INTEGRATION」、「ORDER-INTEGRATION」、「LOG-INTEGRATION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTEGRATION",
                        "ORDER-INTEGRATION",
                        null,
                        "LOG-INTEGRATION",
                        "user-local",
                        "merchant-local",
                        "idem-integration",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "用户表示没有收到已签收包裹",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-INTEGRATION",
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);

        service.confirm(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeConfirmationCommand(
                        true,
                        "SIGNED_NOT_RECEIVED",
                        RiskLevel.HIGH,
                        "确认信息无误，同意发起争议审理"));
        caseRepository.flush();

        FulfillmentCaseEntity persisted =
                caseRepository.findById(dispute.getId()).orElseThrow();
        assertThat(persisted.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(persisted.getCurrentRoom()).isEqualTo("EVIDENCE");
        assertThat(persisted.getCurrentDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));
        assertThat(participantRepository.findAllByCaseId(dispute.getId()))
                .extracting(participant -> participant.getParticipantRole())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(roomRepository.findAllByCaseId(dispute.getId()))
                .extracting(
                        room -> room.getRoomType() + ":" + room.getRoomStatus())
                .containsExactlyInAnyOrder(
                        RoomType.INTAKE + ":" + RoomStatus.CLOSED,
                        RoomType.EVIDENCE + ":" + RoomStatus.OPEN);
        assertThat(
                        clockRepository.findByCaseIdAndClockType(
                                dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .hasValueSatisfying(
                        phaseClock -> {
                            assertThat(phaseClock.getClockStatus())
                                    .isEqualTo(PhaseClockStatus.RUNNING);
                            assertThat(phaseClock.getDeadlineAt())
                                    .isEqualTo(
                                            OffsetDateTime.parse(
                                                    "2026-07-03T02:00:00Z"));
                        });
        assertThat(
                        notificationRepository
                                .findAllByRecipientIdOrderByCreatedAtDesc(
                                        "merchant-local"))
                .extracting(notification -> notification.getNotificationType())
                .containsExactlyInAnyOrder(
                        NotificationType.DISPUTE_SUMMONS,
                        NotificationType.EVIDENCE_ROOM_OPENED);
        assertThat(
                        notificationRepository
                                .findAllByRecipientIdOrderByCreatedAtDesc(
                                        "merchant-local"))
                .allSatisfy(
                        notification ->
                                assertThat(notification.getDeepLink())
                                        .isEqualTo(
                                                "/disputes/CASE_INTEGRATION/evidence"));
        assertThat(outboxRepository.count()).isEqualTo(2);

        var posted =
                roomMessageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new RoomMessageCommand(
                                MessageType.PARTY_TEXT,
                                "补充提交开箱照片。",
                                List.of("EVIDENCE_1")),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-integration-1",
                        "TRACE_integration");
        caseRepository.flush();

        assertThat(posted.sequenceNo()).isEqualTo(1);
        assertThat(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(posted.roomId()))
                .singleElement()
                .satisfies(
                        message ->
                                assertThat(message.getMessageText())
                                        .isEqualTo("补充提交开箱照片。"));
        assertThat(
                        eventRepository
                                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                        dispute.getId(), 0))
                .extracting(
                        event ->
                                event.getSequenceNo()
                                        + ":"
                                        + event.getEventType())
                .containsExactly(
                        "1:EVIDENCE_OPENED",
                        "2:ROOM_MESSAGE_CREATED");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake()」。
    // 具体功能：「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake()」：复现“核对完整业务行为（场景方法「notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」）”场景：驱动 「caseRepository.saveAndFlush」、「service.confirm」、「caseRepository.flush」、「caseRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_REJECTED_INTEGRATION」、「ORDER-REJECTED-INTEGRATION」、「LOG-REJECTED-INTEGRATION」、「user-local」。
    // 上游调用：「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_REJECTED_INTEGRATION」、「ORDER-REJECTED-INTEGRATION」、「LOG-REJECTED-INTEGRATION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_REJECTED_INTEGRATION",
                        "ORDER-REJECTED-INTEGRATION",
                        null,
                        "LOG-REJECTED-INTEGRATION",
                        "user-local",
                        "merchant-local",
                        "idem-rejected-integration",
                        "FULFILLMENT_CONFLICT",
                        "查询物流进度",
                        "用户只是询问物流状态，不构成履约争端",
                        RiskLevel.LOW,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-REJECTED-INTEGRATION",
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);

        var result =
                service.confirm(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                false,
                                "NOT_A_FULFILLMENT_DISPUTE",
                                RiskLevel.LOW,
                                "仅为普通物流查询，不予受理"));
        caseRepository.flush();

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(result.currentRoom()).isNull();
        assertThat(result.deadlineAt()).isNull();
        assertThat(caseRepository.findById(dispute.getId()).orElseThrow().getCaseStatus())
                .isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(participantRepository.findAllByCaseId(dispute.getId()))
                .extracting(participant -> participant.getParticipantRole())
                .containsExactly(ActorRole.USER);
        assertThat(roomRepository.findAllByCaseId(dispute.getId()))
                .extracting(room -> room.getRoomType() + ":" + room.getRoomStatus())
                .containsExactly(RoomType.INTAKE + ":" + RoomStatus.CLOSED);
        assertThat(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .isEmpty();
        assertThat(notificationRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(
                        eventRepository.findByCaseIdAndEventKey(
                                dispute.getId(),
                                "intake-confirmed:" + dispute.getId()))
                .hasValueSatisfying(
                        event -> assertThat(event.getEventType()).isEqualTo("INTAKE_REJECTED"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】类型「FixedClockConfiguration」。
    // 类型职责：在 Spring 启动期装配Fixed时钟所需 Bean 和基础设施参数；本类型显式提供 「fixedClock」、「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.fixedClock()」。
        // 具体功能：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.fixedClock()」：作为测试辅助方法为“核对完整业务行为（场景方法「fixedClock」）”组装或读取「Clock.fixed」、「Instant.parse」，供本测试类的场景方法复用。
        // 上游调用：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.fixedClock()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.fixedClock()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.fixedClock()」守住「房间协作与权限」的可执行规格，尤其防止 「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);
        }

        // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.objectMapper()」。
        // 具体功能：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「IntakeRoomServiceIntegrationTest.FixedClockConfiguration.objectMapper()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
