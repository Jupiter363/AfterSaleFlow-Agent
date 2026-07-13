/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证接待房间，覆盖 「acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow」、「platformReviewerCanAcceptImportedIntakeAndSummonBothParties」、「acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate」、「acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady」、「notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence」、「resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「IntakeRoomServiceTest」。
// 类型职责：集中验证接待房间的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow」、「platformReviewerCanAcceptImportedIntakeAndSummonBothParties」、「acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate」、「acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady」、「notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class IntakeRoomServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository phaseClockRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private NotificationService notificationService;
    @Mock private CaseLifecycleNotificationService lifecycleNotifications;
    @Mock private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @Mock private CaseEventService caseEventService;

    private IntakeRoomService service;

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.setUp()」。
    // 具体功能：「IntakeRoomServiceTest.setUp()」：在每个测试场景运行前创建「caseRepository.save」、「roomRepository.save」、「participantRepository.saveAll」、「Duration.ofHours」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「IntakeRoomServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「IntakeRoomServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeRoomServiceTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        ParticipantService participants = new ParticipantService(participantRepository);
        service =
                new IntakeRoomService(
                        caseRepository,
                        roomRepository,
                        phaseClockRepository,
                        intakeDossierRepository,
                        participants,
                        notificationService,
                        lifecycleNotifications,
                        evidenceWindowCoordinator,
                        caseEventService,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                Duration.ofMinutes(5),
                                3,
                                Duration.ofSeconds(15),
                                true),
                        CLOCK);
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(participantRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow()」。
    // 具体功能：「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow()」：复现“核对完整业务行为（场景方法「acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow」）”场景：驱动 「caseRepository.findByIdForUpdate」、「phaseClockRepository.save」、「service.confirm」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_ACCEPTED」、「user-local」、「SIGNED_NOT_RECEIVED」、「确认信息无误，同意发起争议审理」。
    // 上游调用：「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_ACCEPTED」、「user-local」、「SIGNED_NOT_RECEIVED」、「确认信息无误，同意发起争议审理」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_ACCEPTED");
        when(caseRepository.findByIdForUpdate("CASE_ACCEPTED"))
                .thenReturn(Optional.of(dispute));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.confirm(
                        "CASE_ACCEPTED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                true,
                                "SIGNED_NOT_RECEIVED",
                                RiskLevel.HIGH,
                                "确认信息无误，同意发起争议审理"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(result.currentRoom()).isEqualTo(RoomType.EVIDENCE);
        assertThat(result.deadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CaseParticipantEntity>> participants =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(participants.capture());
        assertThat(participants.getValue())
                .extracting(CaseParticipantEntity::getParticipantRole)
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);

        ArgumentCaptor<CaseRoomEntity> rooms = ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository, org.mockito.Mockito.times(2)).save(rooms.capture());
        assertThat(rooms.getAllValues())
                .anySatisfy(
                        room -> {
                            assertThat(room.getRoomType()).isEqualTo(RoomType.INTAKE);
                            assertThat(room.getRoomStatus()).isEqualTo(RoomStatus.CLOSED);
                        })
                .anySatisfy(
                        room -> {
                            assertThat(room.getRoomType()).isEqualTo(RoomType.EVIDENCE);
                            assertThat(room.getRoomStatus()).isEqualTo(RoomStatus.OPEN);
                        });

        ArgumentCaptor<CasePhaseClockEntity> phaseClock =
                ArgumentCaptor.forClass(CasePhaseClockEntity.class);
        verify(phaseClockRepository).save(phaseClock.capture());
        assertThat(phaseClock.getValue().getClockType())
                .isEqualTo(PhaseClockType.EVIDENCE_SUBMISSION);
        assertThat(phaseClock.getValue().getDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));
        ArgumentCaptor<NotificationCommand> summons =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService).send(summons.capture());
        assertThat(summons.getValue().recipientId()).isEqualTo("merchant-local");
        assertThat(summons.getValue().notificationType())
                .isEqualTo(NotificationType.DISPUTE_SUMMONS);
        assertThat(summons.getValue().deepLink())
                .isEqualTo("/disputes/CASE_ACCEPTED/evidence");
        verify(lifecycleNotifications)
                .evidenceRoomOpened(
                        dispute,
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"));
        verify(caseEventService)
                .recordLifecycleEvent(
                        org.mockito.ArgumentMatchers.eq("CASE_ACCEPTED"),
                        any(),
                        org.mockito.ArgumentMatchers.eq("EVIDENCE_OPENED"),
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                "intake-confirmed:CASE_ACCEPTED"),
                        org.mockito.ArgumentMatchers.eq("user-local"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties()」。
    // 具体功能：「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties()」：复现“核对完整业务行为（场景方法「platformReviewerCanAcceptImportedIntakeAndSummonBothParties」）”场景：驱动 「caseRepository.findByIdForUpdate」、「phaseClockRepository.save」、「service.confirm」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_PLATFORM_ACCEPTED」、「reviewer-local」、「SIGNED_NOT_RECEIVED」、「unchecked」。
    // 上游调用：「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_PLATFORM_ACCEPTED」、「reviewer-local」、「SIGNED_NOT_RECEIVED」、「unchecked」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void platformReviewerCanAcceptImportedIntakeAndSummonBothParties() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_PLATFORM_ACCEPTED");
        when(caseRepository.findByIdForUpdate("CASE_PLATFORM_ACCEPTED"))
                .thenReturn(Optional.of(dispute));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.confirm(
                        "CASE_PLATFORM_ACCEPTED",
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER),
                        new IntakeConfirmationCommand(
                                true,
                                "SIGNED_NOT_RECEIVED",
                                RiskLevel.HIGH,
                                "confirmed by intake officer"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(result.currentRoom()).isEqualTo(RoomType.EVIDENCE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CaseParticipantEntity>> participants =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(participants.capture());
        assertThat(participants.getValue())
                .extracting(CaseParticipantEntity::getParticipantRole)
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);

        ArgumentCaptor<NotificationCommand> summons =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService, org.mockito.Mockito.times(2)).send(summons.capture());
        assertThat(summons.getAllValues())
                .extracting(NotificationCommand::recipientId)
                .containsExactlyInAnyOrder("user-local", "merchant-local");
        assertThat(summons.getAllValues())
                .extracting(NotificationCommand::recipientRole)
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate()」。
    // 具体功能：「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate()」：复现“核对完整业务行为（场景方法「acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「phaseClockRepository.save」、「service.confirm」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_IMPORTED」、「ROOM_IMPORTED_INTAKE」、「2026-07-02T20:00:00Z」、「external-adapter」。
    // 上游调用：「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_IMPORTED」、「ROOM_IMPORTED_INTAKE」、「2026-07-02T20:00:00Z」、「external-adapter」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_IMPORTED");
        CaseRoomEntity existing =
                CaseRoomEntity.open(
                        "ROOM_IMPORTED_INTAKE",
                        "CASE_IMPORTED",
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-02T20:00:00Z"),
                        "external-adapter");
        when(caseRepository.findByIdForUpdate("CASE_IMPORTED"))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        "CASE_IMPORTED", RoomType.INTAKE))
                .thenReturn(Optional.of(existing));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm(
                "CASE_IMPORTED",
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeConfirmationCommand(
                        true,
                        "SIGNED_NOT_RECEIVED",
                        RiskLevel.HIGH,
                        "确认受理"));

        ArgumentCaptor<CaseRoomEntity> rooms =
                ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository, org.mockito.Mockito.times(2)).save(rooms.capture());
        assertThat(rooms.getAllValues().get(0).getId())
                .isEqualTo("ROOM_IMPORTED_INTAKE");
        assertThat(rooms.getAllValues().get(0).getRoomStatus())
                .isEqualTo(RoomStatus.CLOSED);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady()」。
    // 具体功能：「IntakeRoomServiceTest.acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady()」：复现“核对完整业务行为（场景方法「acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady」）”场景：驱动 「caseRepository.findByIdForUpdate」、「phaseClockRepository.save」、「service.confirm」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_SLOT_COMPLETION_ACCEPTED」、「ORDER-CASE_SLOT_COMPLETION_ACCEPTED」、「LOG-CASE_SLOT_COMPLETION_ACCEPTED」、「user-local」。
    // 上游调用：「IntakeRoomServiceTest.acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_SLOT_COMPLETION_ACCEPTED」、「ORDER-CASE_SLOT_COMPLETION_ACCEPTED」、「LOG-CASE_SLOT_COMPLETION_ACCEPTED」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptedSlotCompletionIntakeCanBeConfirmedAfterTheAgentDossierIsReady() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_SLOT_COMPLETION_ACCEPTED",
                        "ORDER-CASE_SLOT_COMPLETION_ACCEPTED",
                        null,
                        "LOG-CASE_SLOT_COMPLETION_ACCEPTED",
                        "user-local",
                        "merchant-local",
                        "idem-CASE_SLOT_COMPLETION_ACCEPTED",
                        "PRODUCT_QUALITY",
                        "Smart watch screen crack dispute",
                        "The intake officer has gathered enough dossier details.",
                        RiskLevel.MEDIUM,
                        CaseStatus.WAITING_SLOT_COMPLETION,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-CASE_SLOT_COMPLETION_ACCEPTED",
                        "external-adapter");
        when(caseRepository.findByIdForUpdate("CASE_SLOT_COMPLETION_ACCEPTED"))
                .thenReturn(Optional.of(dispute));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.confirm(
                        "CASE_SLOT_COMPLETION_ACCEPTED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                true,
                                "PRODUCT_QUALITY",
                                RiskLevel.MEDIUM,
                                "AI 接待官已整理完整，确认发起争议审理"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(result.currentRoom()).isEqualTo(RoomType.EVIDENCE);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence()」。
    // 具体功能：「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence()」：复现“核对完整业务行为（场景方法「notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence」）”场景：驱动 「caseRepository.findByIdForUpdate」、「service.confirm」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_REJECTED」、「user-local」、「NOT_A_FULFILLMENT_DISPUTE」、「确认本次请求不构成履约争端」。
    // 上游调用：「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_REJECTED」、「user-local」、「NOT_A_FULFILLMENT_DISPUTE」、「确认本次请求不构成履约争端」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_REJECTED");
        when(caseRepository.findByIdForUpdate("CASE_REJECTED"))
                .thenReturn(Optional.of(dispute));

        var result =
                service.confirm(
                        "CASE_REJECTED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                false,
                                "NOT_A_FULFILLMENT_DISPUTE",
                                RiskLevel.LOW,
                                "确认本次请求不构成履约争端"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(result.currentRoom()).isNull();
        assertThat(result.deadlineAt()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CaseParticipantEntity>> participants =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(participants.capture());
        assertThat(participants.getValue())
                .extracting(CaseParticipantEntity::getParticipantRole)
                .containsExactly(ActorRole.USER);
        verify(phaseClockRepository, never()).save(any());
        verify(notificationService, never()).send(any());

        ArgumentCaptor<CaseRoomEntity> rooms = ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository).save(rooms.capture());
        assertThat(rooms.getAllValues())
                .extracting(CaseRoomEntity::getRoomType)
                .containsExactly(RoomType.INTAKE);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence()」。
    // 具体功能：「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence()」：复现“核对完整业务行为（场景方法「resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「service.cancel」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_CANCELLED」、「ROOM_CANCELLED_INTAKE」、「2026-07-02T20:00:00Z」、「external-adapter」。
    // 上游调用：「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_CANCELLED」、「ROOM_CANCELLED_INTAKE」、「2026-07-02T20:00:00Z」、「external-adapter」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void resolvedIntakeCancellationClosesTheRoomWithoutOpeningEvidence() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_CANCELLED");
        CaseRoomEntity existing =
                CaseRoomEntity.open(
                        "ROOM_CANCELLED_INTAKE",
                        "CASE_CANCELLED",
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-02T20:00:00Z"),
                        "external-adapter");
        when(caseRepository.findByIdForUpdate("CASE_CANCELLED"))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        "CASE_CANCELLED", RoomType.INTAKE))
                .thenReturn(Optional.of(existing));

        var result =
                service.cancel(
                        "CASE_CANCELLED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "user resolved with merchant");

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.CANCELLED);
        assertThat(result.currentRoom()).isNull();
        assertThat(result.deadlineAt()).isNull();
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.CANCELLED);
        ArgumentCaptor<CaseRoomEntity> rooms = ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository).save(rooms.capture());
        assertThat(rooms.getValue().getRoomStatus()).isEqualTo(RoomStatus.CLOSED);
        verify(participantRepository, never()).saveAll(any());
        verify(phaseClockRepository, never()).save(any());
        verify(notificationService, never()).send(any());
        verify(caseEventService)
                .recordLifecycleEvent(
                        org.mockito.ArgumentMatchers.eq("CASE_CANCELLED"),
                        org.mockito.ArgumentMatchers.eq("ROOM_CANCELLED_INTAKE"),
                        org.mockito.ArgumentMatchers.eq("INTAKE_CANCELLED"),
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                "intake-cancelled:CASE_CANCELLED"),
                        org.mockito.ArgumentMatchers.eq("user-local"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase()」。
    // 具体功能：「IntakeRoomServiceTest.acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase()」：复现“核对完整业务行为（场景方法「acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase」）”场景：驱动 「caseRepository.findByIdForUpdate」、「intakeDossierRepository.findByCaseIdAndRoomType」、「phaseClockRepository.save」、「service.confirm」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_DOSSIER_ACCEPTED」、「INTAKE_DOSSIER_CASE_DOSSIER_ACCEPTED」、「ACCEPTED」、「dispute-intake-officer」。
    // 上游调用：「IntakeRoomServiceTest.acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_DOSSIER_ACCEPTED」、「INTAKE_DOSSIER_CASE_DOSSIER_ACCEPTED」、「ACCEPTED」、「dispute-intake-officer」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptedIntakeSnapshotsTheLatestAgentDossierIntoTheCase() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_DOSSIER_ACCEPTED");
        String dossierJson =
                "{\"schema_version\":\"intake_case_detail.v1\",\"case_story\":{\"title\":\"商家质检与签收划痕争议\"},\"intake_quality\":{\"score\":88,\"ready_for_next_step\":true}}";
        when(caseRepository.findByIdForUpdate("CASE_DOSSIER_ACCEPTED"))
                .thenReturn(Optional.of(dispute));
        when(intakeDossierRepository.findByCaseIdAndRoomType(
                        "CASE_DOSSIER_ACCEPTED", RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                CaseIntakeDossierEntity.create(
                                        "INTAKE_DOSSIER_CASE_DOSSIER_ACCEPTED",
                                        "CASE_DOSSIER_ACCEPTED",
                                        RoomType.INTAKE,
                                        dossierJson,
                                        88,
                                        true,
                                        "ACCEPTED",
                                        3,
                                        "dispute-intake-officer")));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm(
                "CASE_DOSSIER_ACCEPTED",
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                new IntakeConfirmationCommand(
                        true,
                        "PRODUCT_QUALITY",
                        RiskLevel.MEDIUM,
                        "确认发起并上报"));

        assertThat(dispute.getIntakeResultJson()).isEqualTo(dossierJson);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier()」。
    // 具体功能：「IntakeRoomServiceTest.acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier()」：复现“核对完整业务行为（场景方法「acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier」）”场景：驱动 「caseRepository.findByIdForUpdate」、「intakeDossierRepository.findByCaseIdAndRoomType」、「phaseClockRepository.save」、「service.confirm」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_DOSSIER_REMARK」、「INTAKE_DOSSIER_CASE_DOSSIER_REMARK」、「ACCEPTED」、「dispute-intake-officer」。
    // 上游调用：「IntakeRoomServiceTest.acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomServiceTest.acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomServiceTest.acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_DOSSIER_REMARK」、「INTAKE_DOSSIER_CASE_DOSSIER_REMARK」、「ACCEPTED」、「dispute-intake-officer」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptedIntakeAllowsEmptyConfirmationNoteAndKeepsHandoffRemarkFromDossier() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_DOSSIER_REMARK");
        String dossierJson =
                """
                {
                  "schema_version": "intake_case_detail.v1",
                  "case_story": {"title": "商家质检与签收划痕争议"},
                  "intake_quality": {"score": 88, "ready_for_next_step": true},
                  "handoff_notes": {
                    "remark_status": "HAS_REMARKS",
                    "latest_remark": "请证据书记官重点核查快递柜取件记录。",
                    "remarks": [
                      {
                        "role": "USER",
                        "text": "请证据书记官重点核查快递柜取件记录。",
                        "source_message_id": "MESSAGE_REMARK_1"
                      }
                    ]
                  }
                }
                """;
        when(caseRepository.findByIdForUpdate("CASE_DOSSIER_REMARK"))
                .thenReturn(Optional.of(dispute));
        when(intakeDossierRepository.findByCaseIdAndRoomType(
                        "CASE_DOSSIER_REMARK", RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                CaseIntakeDossierEntity.create(
                                        "INTAKE_DOSSIER_CASE_DOSSIER_REMARK",
                                        "CASE_DOSSIER_REMARK",
                                        RoomType.INTAKE,
                                        dossierJson,
                                        88,
                                        true,
                                        "ACCEPTED",
                                        4,
                                        "dispute-intake-officer")));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm(
                "CASE_DOSSIER_REMARK",
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeConfirmationCommand(
                        true,
                        "PRODUCT_QUALITY",
                        RiskLevel.MEDIUM,
                        null));

        assertThat(dispute.getIntakeResultJson()).isEqualTo(dossierJson);
        assertThat(dispute.getIntakeResultJson()).contains("请证据书记官重点核查快递柜取件记录。");
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomServiceTest.pendingCase(String)」。
    // 具体功能：「IntakeRoomServiceTest.pendingCase(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「pendingCase」）”组装或读取「FulfillmentCaseEntity.imported」，供本测试类的场景方法复用。
    // 上游调用：「IntakeRoomServiceTest.pendingCase(String)」由本测试类中的 「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow」、「IntakeRoomServiceTest.platformReviewerCanAcceptImportedIntakeAndSummonBothParties」、「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate」、「IntakeRoomServiceTest.notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence」 调用。
    // 下游影响：「IntakeRoomServiceTest.pendingCase(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「IntakeRoomServiceTest.pendingCase(String)」守住「房间协作与权限」的可执行规格，尤其防止 「ORDER-」、「LOG-」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity pendingCase(String id) {
        return FulfillmentCaseEntity.imported(
                id,
                "ORDER-" + id,
                null,
                "LOG-" + id,
                "user-local",
                "merchant-local",
                "idem-" + id,
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示没有收到已签收包裹",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "OMS",
                "EXT-" + id,
                "external-adapter");
    }
}
