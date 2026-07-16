/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据完成确认，覆盖 「bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」、「repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」、「initiatorCannotCompleteEvidenceWithoutSubmittedEvidence」、「deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.IntakeMatrixLifecycleService;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.hearing.application.HearingFlowRuntimeService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceCompletionServiceTest」。
// 类型职责：集中验证证据完成确认的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」、「repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」、「initiatorCannotCompleteEvidenceWithoutSubmittedEvidence」、「deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceCompletionServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidencePartyCompletionRepository completionRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;
    @Mock private EvidenceDossierFreezer dossierFreezer;
    @Mock private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @Mock private IntakeProgressService intakeProgressService;
    @Mock private IntakeMatrixLifecycleService intakeMatrixLifecycleService;
    @Mock private CaseEventService caseEventService;
    @Mock private NotificationService notificationService;
    @Mock private CaseLifecycleNotificationService lifecycleNotifications;
    @Mock private HearingFlowRuntimeService hearingFlowRuntimeService;

    private EvidenceCompletionService service;
    private FulfillmentCaseEntity dispute;
    private CaseRoomEntity evidenceRoom;
    private CasePhaseClockEntity evidenceClock;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCompletionServiceTest.setUp()」。
    // 具体功能：「EvidenceCompletionServiceTest.setUp()」：在每个测试场景运行前创建「caseRepository.findByIdForUpdate」、「Clock.fixed」、「Instant.parse」、「Duration.ofHours」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceCompletionServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceCompletionServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceCompletionServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「CASE_EVIDENCE_COMPLETE」、「ORDER-1」、「LOG-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        Clock clock =
                Clock.fixed(
                        Instant.parse("2026-07-03T01:00:00Z"),
                        ZoneOffset.UTC);
        service =
                new EvidenceCompletionService(
                        caseRepository,
                        completionRepository,
                        evidenceRepository,
                        roomRepository,
                        clockRepository,
                        dossierFreezer,
                        evidenceWindowCoordinator,
                        intakeProgressService,
                        intakeMatrixLifecycleService,
                        caseEventService,
                        notificationService,
                        lifecycleNotifications,
                        hearingFlowRuntimeService,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                Duration.ofMinutes(20),
                                Duration.ofSeconds(15),
                                true),
                        clock);
        dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_EVIDENCE_COMPLETE",
                        "ORDER-1",
                        null,
                        "LOG-1",
                        "user-local",
                        "merchant-local",
                        "idem-complete",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "双方举证中",
                        RiskLevel.HIGH,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "OMS",
                        "EXT-COMPLETE",
                        "external-adapter");
        evidenceRoom =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        evidenceClock =
                CasePhaseClockEntity.running(
                        "CLOCK_EVIDENCE",
                        dispute.getId(),
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "evidence-window",
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        org.mockito.Mockito.lenient()
                .when(dossierFreezer.targetVersion(dispute.getId()))
                .thenReturn(1);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing()」。
    // 具体功能：「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing()」：复现“核对完整业务行为（场景方法「bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」）”场景：驱动 「evidenceRepository.countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」、「roomRepository.findByCaseIdAndRoomType」、「clockRepository.findByCaseIdAndClockType」、「completionRepository.save」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「user-complete-1」、「merchant-complete-1」、「COMPLETED」。
    // 上游调用：「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「USER」、「user-complete-1」、「merchant-complete-1」、「COMPLETED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(evidenceRoom));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .thenReturn(Optional.of(evidenceClock));
        when(completionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(clockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(caseRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-complete-1"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-complete-1"))
                .thenReturn(Optional.empty());
        EvidencePartyCompletionEntity userCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_USER",
                        dispute.getId(),
                        1,
                        ActorRole.USER,
                        "user-local",
                        "user-complete-1",
                        Instant.parse("2026-07-03T00:30:00Z"));
        EvidencePartyCompletionEntity merchantCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_MERCHANT",
                        dispute.getId(),
                        1,
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "merchant-complete-1",
                        Instant.parse("2026-07-03T00:40:00Z"));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(
                        List.of(userCompletion),
                        List.of(userCompletion, merchantCompletion));

        service.complete(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-1");
        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-complete-1");

        assertThat(result.allPartiesCompleted()).isTrue();
        assertThat(evidenceRoom.getRoomStatus()).isEqualTo(RoomStatus.SEALED);
        assertThat(evidenceClock.getClockStatus())
                .isEqualTo(PhaseClockStatus.COMPLETED_EARLY);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.HEARING_OPEN);
        assertThat(dispute.getCurrentRoom()).isEqualTo("HEARING");
        assertThat(dispute.getCurrentDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T04:00:00Z"));
        verify(hearingFlowRuntimeService).startAfterEvidenceSealed(dispute.getId());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation()」。
    // 具体功能：「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation()」：复现“核对完整业务行为（场景方法「repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」）”场景：驱动 「evidenceRepository.countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」、「completionRepository.findByCaseIdAndIdempotencyKey」、「completionRepository.findByCaseIdAndDossierVersionAndParticipantRole」、「completionRepository.countByCaseIdAndDossierVersionAndCompletionStatus」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「EVIDENCE_COMPLETE_EXISTING」、「user-local」、「user-complete-original」。
    // 上游调用：「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「USER」、「EVIDENCE_COMPLETE_EXISTING」、「user-local」、「user-complete-original」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void repeatedCompletionByTheSameParticipantIdUsesTheExistingPhaseConfirmation() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity existing =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_EXISTING",
                        dispute.getId(),
                        1,
                        ActorRole.MERCHANT,
                        "user-local",
                        "user-complete-original",
                        Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-complete-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(existing));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of(existing));

        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "user-complete-retry");

        assertThat(result.dossierVersion()).isEqualTo(1);
        assertThat(result.allPartiesCompleted()).isFalse();
        verify(completionRepository, never()).save(any());
    }

    @Test
    void respondentCanCompleteIndependentlyBeforeInitiatorSubmitsEvidence() {
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-complete-without-evidence"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "merchant-local"))
                .thenReturn(Optional.empty());
        when(completionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        EvidencePartyCompletionEntity merchantCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_MERCHANT",
                        dispute.getId(),
                        1,
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "merchant-complete-without-evidence",
                        Instant.parse("2026-07-03T00:40:00Z"));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of(merchantCompletion));

        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-complete-without-evidence");

        assertThat(result.completedRole()).isEqualTo(ActorRole.MERCHANT);
        assertThat(result.allPartiesCompleted()).isFalse();
        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        verify(evidenceWindowCoordinator)
                .signalPartyCompletedAfterCommit(dispute.getId(), "MERCHANT");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence()」。
    // 具体功能：「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence()」：复现“核对完整业务行为（场景方法「initiatorCannotCompleteEvidenceWithoutSubmittedEvidence」）”场景：驱动 「evidenceRepository.countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull」、「service.complete」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「user-local」、「user-complete-without-evidence」。
    // 上游调用：「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCompletionServiceTest.initiatorCannotCompleteEvidenceWithoutSubmittedEvidence()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「USER」、「user-local」、「user-complete-without-evidence」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initiatorCannotCompleteEvidenceWithoutSubmittedEvidence() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(0L);

        assertThatThrownBy(
                        () ->
                                service.complete(
                                        dispute.getId(),
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "user-complete-without-evidence"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("发起争议方需先正式提交至少 1 份相关证据");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen()」。
    // 具体功能：「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen()」：复现“核对完整业务行为（场景方法「deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen」）”场景：驱动 「service.warnDeadline」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T02:00:00Z」。
    // 上游调用：「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCompletionServiceTest.deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T02:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void deadlineWarningNotifiesBothPartiesWhileTheEvidenceWindowIsOpen() {
        service.warnDeadline(dispute.getId());

        org.mockito.Mockito.verify(lifecycleNotifications)
                .evidenceDeadlineWarning(
                        dispute,
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"));
    }
}
