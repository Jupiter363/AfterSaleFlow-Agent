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
import static org.mockito.Mockito.times;
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
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
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
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.ingress.rooms.TargetRoomCommandIngress;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore.Provenance;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore.Route;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

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
    @Mock private RoomEpochAllocator roomEpochAllocator;

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
                        roomEpochAllocator,
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
        ArgumentCaptor<TransitionRoomEpoch> transition =
                ArgumentCaptor.forClass(TransitionRoomEpoch.class);
        verify(roomEpochAllocator).transition(transition.capture());
        assertThat(transition.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(transition.getValue().expectedRoomType())
                .isEqualTo(ContractTypes.RoomType.EVIDENCE);
        assertThat(transition.getValue().nextRoomType())
                .isEqualTo(ContractTypes.RoomType.HEARING);
        assertThat(transition.getValue().macroPhase()).isEqualTo(CaseStatus.HEARING_OPEN.name());
        assertThat(transition.getValue().roomPhase()).isEqualTo(RoomStatus.OPEN.name());
        assertThat(transition.getValue().projectedDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T04:00:00Z"));
        assertThat(transition.getValue().occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T01:00:00Z"));
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
        verify(evidenceWindowCoordinator)
                .signalPartyCompletedAfterCommit(dispute.getId(), "USER");
        verify(roomEpochAllocator, never()).transition(any());
  }

    @Test
    void delayedTargetCompletionReplayDoesNotRematerializeAtTheNewRevision() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity existing =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_TARGET_EXISTING", dispute.getId(), 1, ActorRole.USER,
                        "user-local", "target-original", Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "target-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(existing));
        CaseRoomEpochRepository epochs = org.mockito.Mockito.mock(CaseRoomEpochRepository.class);
        CaseRoomEpochEntity epoch = org.mockito.Mockito.mock(CaseRoomEpochEntity.class);
        when(epochs.findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE, EpochLifecycleStatus.ACTIVE))
                .thenReturn(Optional.of(epoch));
        when(epoch.getWriterMode()).thenReturn(ContractTypes.WriterMode.TEMPORAL);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);
        org.mockito.Mockito.lenient().when(epoch.getTenantSurrogate()).thenReturn("tenant-e2e");
        org.mockito.Mockito.lenient().when(epoch.getRoomEpoch()).thenReturn(4L);
        org.mockito.Mockito.lenient().when(epoch.getFencingToken()).thenReturn(9L);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetRoomCommandIngress> ingress = org.mockito.Mockito.mock(ObjectProvider.class);
        CaseCommandService commands = org.mockito.Mockito.mock(CaseCommandService.class);
        TargetEvidenceCompletionCommandMaterialStore materialStore =
                org.mockito.Mockito.mock(TargetEvidenceCompletionCommandMaterialStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetEvidenceCompletionCommandMaterialStore> materialProvider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(materialProvider.getIfAvailable()).thenReturn(materialStore);
        when(materialStore.readProvenance(any())).thenReturn(Optional.of(Provenance.IN_FLIGHT));
        EvidenceCompletionService targetService = new EvidenceCompletionService(
                caseRepository, completionRepository, evidenceRepository, roomRepository, clockRepository,
                dossierFreezer, evidenceWindowCoordinator, intakeProgressService, intakeMatrixLifecycleService,
                caseEventService, notificationService, lifecycleNotifications, hearingFlowRuntimeService,
                roomEpochAllocator, new DisputeProperties(Duration.ofHours(2), Duration.ofHours(3),
                        Duration.ofMinutes(20), Duration.ofSeconds(15), true),
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC),
                epochs, ingress, commands, new ObjectMapper(), materialProvider);

        var result = targetService.complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER), "target-retry");

        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        verify(ingress, never()).getIfAvailable();
        verify(commands, never()).accept(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeTargetEpochDispatchesAnExistingLegacyCompletionWithAbsentProvenanceExactlyOnce() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity existing = EvidencePartyCompletionEntity.completed(
                "EVIDENCE_COMPLETE_TARGET_LEGACY", dispute.getId(), 1, ActorRole.USER,
                "user-local", "target-legacy-original", Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "target-legacy-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(existing));
        TargetFixture target = targetFixture(EpochLifecycleStatus.ACTIVE);
        when(target.materialStore().readProvenance(any())).thenReturn(Optional.empty());
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(evidenceRoom));
        CaseTimelineEventEntity event = org.mockito.Mockito.mock(CaseTimelineEventEntity.class);
        when(event.getId()).thenReturn("EVENT_TARGET_LEGACY");
        when(caseEventService.recordLifecycleEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(event);

        var result = target.service().complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                "target-legacy-retry");

        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        ArgumentCaptor<Route> route = ArgumentCaptor.forClass(Route.class);
        verify(target.materialStore()).readProvenance(route.capture());
        assertThat(route.getValue()).isEqualTo(new Route(
                "tenant-e2e", dispute.getId(),
                "evidence-complete:EVIDENCE_COMPLETE_TARGET_LEGACY", 4, 9,
                "EVIDENCE_COMPLETE_TARGET_LEGACY"));
        verify(target.ingress(), times(1)).materialize(
                any(), any(), any(), any(), any());
        verify(target.commands(), times(1)).accept(
                any(), any(), any(), any(), any(), any(), any());
        verify(evidenceWindowCoordinator, never())
                .signalPartyCompletedAfterCommit(any(), any());
    }

    @Test
    void activeTargetAppliedReplayDoesNotRedispatchOrEnterLegacyFlow() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity existing = EvidencePartyCompletionEntity.completed(
                "EVIDENCE_COMPLETE_TARGET_APPLIED", dispute.getId(), 1, ActorRole.USER,
                "user-local", "target-applied-original", Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "target-applied-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(existing));
        TargetFixture target = targetFixture(EpochLifecycleStatus.ACTIVE);
        when(target.materialStore().readProvenance(any()))
                .thenReturn(Optional.of(Provenance.APPLIED_EXACT));

        var result = target.service().complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                "target-applied-retry");

        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        verify(target.ingressProvider(), never()).getIfAvailable();
        verify(target.commands(), never()).accept(any(), any(), any(), any(), any(), any(), any());
        verify(evidenceWindowCoordinator, never())
                .signalPartyCompletedAfterCommit(any(), any());
    }

    @Test
    void activeTemporalProvisioningEpochFailsBeforeAnyCompletionWriteOrDispatch() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "target-provisioning"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.empty());
        TargetFixture target = targetFixture(EpochLifecycleStatus.ACTIVE);
        when(target.epoch().getProvisioningStatus())
                .thenReturn(EpochProvisioningStatus.PROVISIONING);

        assertThatThrownBy(() -> target.service().complete(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                        "target-provisioning"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not exact target authority");

        verify(completionRepository, never()).save(any());
        verify(evidenceWindowCoordinator, never()).signalPartyCompletedAfterCommit(any(), any());
        verify(dossierFreezer, never()).freeze(any(), any(Integer.class), any());
        verify(target.ingressProvider(), never()).getIfAvailable();
        verify(target.commands(), never()).accept(any(), any(), any(), any(), any(), any(), any());
        verify(target.epochs(), never())
                .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE,
                        EpochLifecycleStatus.TERMINAL);
    }

    @Test
    void activeLegacyEpochIsNotHijackedByAnOlderTerminalTargetEpoch() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "active-legacy"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.empty());
        when(completionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of());
        TargetFixture target = targetFixture(EpochLifecycleStatus.ACTIVE);
        when(target.epoch().getWriterMode()).thenReturn(ContractTypes.WriterMode.LEGACY);
        CaseRoomEpochEntity olderTarget = org.mockito.Mockito.mock(CaseRoomEpochEntity.class);
        org.mockito.Mockito.lenient().when(target.epochs()
                        .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                                dispute.getId(), ContractTypes.RoomType.EVIDENCE,
                                EpochLifecycleStatus.TERMINAL))
                .thenReturn(Optional.of(olderTarget));

        var result = target.service().complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                "active-legacy");

        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        verify(completionRepository, times(1)).save(any());
        verify(evidenceWindowCoordinator, times(1))
                .signalPartyCompletedAfterCommit(dispute.getId(), "USER");
        verify(target.epochs(), never())
                .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE,
                        EpochLifecycleStatus.TERMINAL);
        verify(target.materialStore(), never()).readProvenance(any());
        verify(target.ingressProvider(), never()).getIfAvailable();
    }

    @Test
    void activeShadowEpochLeavesLegacyAsTheBusinessWriter() {
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "active-shadow"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.empty());
        when(completionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of());
        TargetFixture target = targetFixture(EpochLifecycleStatus.ACTIVE);
        when(target.epoch().getWriterMode()).thenReturn(ContractTypes.WriterMode.SHADOW);

        var result = target.service().complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                "active-shadow");

        assertThat(result.nextRoom()).isEqualTo("EVIDENCE");
        verify(completionRepository, times(1)).save(any());
        verify(evidenceWindowCoordinator, times(1))
                .signalPartyCompletedAfterCommit(dispute.getId(), "USER");
        verify(target.materialStore(), never()).readProvenance(any());
        verify(target.ingressProvider(), never()).getIfAvailable();
    }

    @Test
    void terminalTargetReplayReturnsTheCurrentHearingProjectionWithoutLegacySideEffects() {
        OffsetDateTime hearingDeadline = OffsetDateTime.parse("2026-07-03T04:00:00Z");
        dispute.openHearing(hearingDeadline, "system");
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity userCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_TARGET_TERMINAL_USER", dispute.getId(), 1,
                        ActorRole.USER, "user-local", "target-terminal-original",
                        Instant.parse("2026-07-03T00:30:00Z"));
        EvidencePartyCompletionEntity merchantCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_TARGET_TERMINAL_MERCHANT", dispute.getId(), 1,
                        ActorRole.MERCHANT, "merchant-local", "target-terminal-merchant",
                        Instant.parse("2026-07-03T00:40:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "target-terminal-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(userCompletion));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of(userCompletion, merchantCompletion));
        CaseRoomEpochRepository epochs = org.mockito.Mockito.mock(CaseRoomEpochRepository.class);
        CaseRoomEpochEntity terminalEpoch = org.mockito.Mockito.mock(CaseRoomEpochEntity.class);
        when(epochs.findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE, EpochLifecycleStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(epochs.findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE, EpochLifecycleStatus.TERMINAL))
                .thenReturn(Optional.of(terminalEpoch));
        when(terminalEpoch.getWriterMode()).thenReturn(ContractTypes.WriterMode.TEMPORAL);
        when(terminalEpoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(terminalEpoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);
        when(terminalEpoch.getTenantSurrogate()).thenReturn("tenant-e2e");
        when(terminalEpoch.getRoomEpoch()).thenReturn(4L);
        when(terminalEpoch.getFencingToken()).thenReturn(9L);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetRoomCommandIngress> ingress = org.mockito.Mockito.mock(ObjectProvider.class);
        CaseCommandService commands = org.mockito.Mockito.mock(CaseCommandService.class);
        TargetEvidenceCompletionCommandMaterialStore materialStore =
                org.mockito.Mockito.mock(TargetEvidenceCompletionCommandMaterialStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetEvidenceCompletionCommandMaterialStore> materialProvider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(materialProvider.getIfAvailable()).thenReturn(materialStore);
        when(materialStore.readProvenance(any())).thenReturn(Optional.of(Provenance.APPLIED_EXACT));
        EvidenceCompletionService targetService = new EvidenceCompletionService(
                caseRepository, completionRepository, evidenceRepository, roomRepository, clockRepository,
                dossierFreezer, evidenceWindowCoordinator, intakeProgressService, intakeMatrixLifecycleService,
                caseEventService, notificationService, lifecycleNotifications, hearingFlowRuntimeService,
                roomEpochAllocator, new DisputeProperties(Duration.ofHours(2), Duration.ofHours(3),
                        Duration.ofMinutes(20), Duration.ofSeconds(15), true),
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC),
                epochs, ingress, commands, new ObjectMapper(), materialProvider);

        var result = targetService.complete(
                dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                "target-terminal-retry");

        assertThat(result.allPartiesCompleted()).isTrue();
        assertThat(result.nextRoom()).isEqualTo("HEARING");
        assertThat(result.nextDeadlineAt()).isEqualTo(hearingDeadline);
        verify(completionRepository, never()).save(any());
        verify(evidenceWindowCoordinator, never())
                .signalPartyCompletedAfterCommit(dispute.getId(), "USER");
        verify(dossierFreezer, never()).freeze(dispute.getId(), 1, "user-local");
        verify(roomEpochAllocator, never()).transition(any());
        verify(ingress, never()).getIfAvailable();
        verify(commands, never()).accept(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalTargetRejectsAnExistingLegacyCompletionWithoutAppliedProvenance() {
        dispute.openHearing(OffsetDateTime.parse("2026-07-03T04:00:00Z"), "system");
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity existing = EvidencePartyCompletionEntity.completed(
                "EVIDENCE_COMPLETE_TARGET_TERMINAL_LEGACY", dispute.getId(), 1, ActorRole.USER,
                "user-local", "terminal-legacy-original", Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "terminal-legacy-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.of(existing));
        TargetFixture target = targetFixture(EpochLifecycleStatus.TERMINAL);
        when(target.materialStore().readProvenance(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> target.service().complete(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                        "terminal-legacy-retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no durable target provenance");

        verify(completionRepository, never()).save(any());
        verify(evidenceWindowCoordinator, never())
                .signalPartyCompletedAfterCommit(any(), any());
        verify(dossierFreezer, never()).freeze(any(), any(Integer.class), any());
        verify(roomEpochAllocator, never()).transition(any());
    }

    @Test
    void terminalTargetRejectsANewCompletionBeforeItCanBePersisted() {
        dispute.openHearing(OffsetDateTime.parse("2026-07-03T04:00:00Z"), "system");
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(completionRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "terminal-created"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "user-local"))
                .thenReturn(Optional.empty());
        TargetFixture target = targetFixture(EpochLifecycleStatus.TERMINAL);

        assertThatThrownBy(() -> target.service().complete(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER),
                        "terminal-created"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden after the target epoch is terminal");

        verify(completionRepository, never()).save(any());
        verify(target.materialStore(), never()).readProvenance(any());
        verify(evidenceWindowCoordinator, never())
                .signalPartyCompletedAfterCommit(any(), any());
        verify(dossierFreezer, never()).freeze(any(), any(Integer.class), any());
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
        verify(roomEpochAllocator, never()).transition(any());
    }

    @Test
    void hearingReplayDoesNotResealRoomsOrTransitionTheEpochAgain() {
        dispute.openHearing(
                OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                "system");
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(), "user-local", com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        EvidencePartyCompletionEntity userCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_REPLAY_USER",
                        dispute.getId(),
                        1,
                        ActorRole.USER,
                        "user-local",
                        "user-complete-replay",
                        Instant.parse("2026-07-03T00:30:00Z"));
        EvidencePartyCompletionEntity merchantCompletion =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_REPLAY_MERCHANT",
                        dispute.getId(),
                        1,
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "merchant-complete-replay",
                        Instant.parse("2026-07-03T00:40:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-complete-replay"))
                .thenReturn(Optional.of(merchantCompletion));
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantId(
                        dispute.getId(), 1, "merchant-local"))
                .thenReturn(Optional.of(merchantCompletion));
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of(userCompletion, merchantCompletion));

        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-complete-replay");

        assertThat(result.allPartiesCompleted()).isTrue();
        assertThat(result.nextRoom()).isEqualTo("HEARING");
        assertThat(result.nextDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T04:00:00Z"));
        verify(roomRepository, never()).save(any());
        verify(clockRepository, never()).save(any());
        verify(caseRepository, never()).save(any());
        verify(roomEpochAllocator, never()).transition(any());
        verify(hearingFlowRuntimeService, never()).startAfterEvidenceSealed(any());
    }

    @Test
    void deadlineExpiryTransitionsOpenEvidenceExactlyOnce() {
        arrangeExpiryTransition(CaseStatus.EVIDENCE_OPEN);

        var result = service.expire(dispute.getId());

        assertThat(result.sealed()).isTrue();
        assertThat(result.nextRoom()).isEqualTo("HEARING");
        assertExpiryTransition();
    }

    @Test
    void deadlineExpiryTransitionsSealedEvidenceExactlyOnce() {
        arrangeExpiryTransition(CaseStatus.EVIDENCE_SEALED);

        var result = service.expire(dispute.getId());

        assertThat(result.sealed()).isTrue();
        assertThat(result.nextRoom()).isEqualTo("HEARING");
        assertExpiryTransition();
    }

    @Test
    void existingHearingClockRemainsTheAuthoritativeDeadlineDuringTransition() {
        arrangeExpiryTransition(CaseStatus.EVIDENCE_OPEN);
        CaseRoomEntity hearingRoom =
                CaseRoomEntity.open(
                        "ROOM_HEARING_EXISTING",
                        dispute.getId(),
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-03T00:30:00Z"),
                        "system");
        OffsetDateTime existingDeadline =
                OffsetDateTime.parse("2026-07-03T05:30:00Z");
        CasePhaseClockEntity hearingClock =
                CasePhaseClockEntity.running(
                        "CLOCK_HEARING_EXISTING",
                        dispute.getId(),
                        hearingRoom.getId(),
                        PhaseClockType.HEARING,
                        OffsetDateTime.parse("2026-07-03T00:30:00Z"),
                        existingDeadline,
                        "hearing-window-existing",
                        "system");
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(hearingRoom));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.HEARING))
                .thenReturn(Optional.of(hearingClock));

        service.expire(dispute.getId());

        assertThat(dispute.getCurrentDeadlineAt()).isEqualTo(existingDeadline);
        ArgumentCaptor<TransitionRoomEpoch> transition =
                ArgumentCaptor.forClass(TransitionRoomEpoch.class);
        verify(roomEpochAllocator).transition(transition.capture());
        assertThat(transition.getValue().projectedDeadlineAt())
                .isEqualTo(existingDeadline);
    }

    @Test
    void deadlineExpiryReplayFromHearingDoesNotTransitionAgain() {
        dispute.openHearing(
                OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                "system");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of());

        var result = service.expire(dispute.getId());

        assertThat(result.nextRoom()).isEqualTo("HEARING");
        verify(roomEpochAllocator, never()).transition(any());
        verify(roomRepository, never()).save(any());
        verify(clockRepository, never()).save(any());
        verify(caseRepository, never()).save(any());
        verify(hearingFlowRuntimeService, never()).startAfterEvidenceSealed(any());
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

    private void arrangeExpiryTransition(CaseStatus status) {
        if (status == CaseStatus.EVIDENCE_SEALED) {
            dispute = importedEvidenceCase(status);
            evidenceRoom =
                    CaseRoomEntity.open(
                            "ROOM_EVIDENCE",
                            dispute.getId(),
                            RoomType.EVIDENCE,
                            OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                            "system");
            evidenceRoom.seal(
                    OffsetDateTime.parse("2026-07-03T00:30:00Z"),
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
        }
        when(evidenceRepository.countByCaseIdAndSubmittedByIdAndSubmissionStatusAndDeletedAtIsNull(
                        dispute.getId(),
                        "user-local",
                        com.example.dispute.evidence.domain.EvidenceSubmissionStatus.SUBMITTED))
                .thenReturn(1L);
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(evidenceRoom));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.empty());
        when(roomRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .thenReturn(Optional.of(evidenceClock));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.HEARING))
                .thenReturn(Optional.empty());
        when(clockRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(dossierFreezer.latestVersion(dispute.getId())).thenReturn(1);
        when(completionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(List.of());
    }

    private void assertExpiryTransition() {
        ArgumentCaptor<TransitionRoomEpoch> transition =
                ArgumentCaptor.forClass(TransitionRoomEpoch.class);
        verify(roomEpochAllocator).transition(transition.capture());
        assertThat(transition.getValue().expectedRoomType())
                .isEqualTo(ContractTypes.RoomType.EVIDENCE);
        assertThat(transition.getValue().nextRoomType())
                .isEqualTo(ContractTypes.RoomType.HEARING);
        assertThat(transition.getValue().nextRoomId()).isNotBlank();
        assertThat(transition.getValue().macroPhase())
                .isEqualTo(CaseStatus.HEARING_OPEN.name());
        assertThat(transition.getValue().roomPhase()).isEqualTo(RoomStatus.OPEN.name());
        assertThat(transition.getValue().projectedDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T04:00:00Z"));
        assertThat(transition.getValue().occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T01:00:00Z"));
    }

    private TargetFixture targetFixture(EpochLifecycleStatus lifecycle) {
        CaseRoomEpochRepository epochs = org.mockito.Mockito.mock(CaseRoomEpochRepository.class);
        CaseRoomEpochEntity epoch = org.mockito.Mockito.mock(CaseRoomEpochEntity.class);
        when(epochs.findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        dispute.getId(), ContractTypes.RoomType.EVIDENCE,
                        EpochLifecycleStatus.ACTIVE))
                .thenReturn(lifecycle == EpochLifecycleStatus.ACTIVE
                        ? Optional.of(epoch)
                        : Optional.empty());
        if (lifecycle == EpochLifecycleStatus.TERMINAL) {
            when(epochs.findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                            dispute.getId(), ContractTypes.RoomType.EVIDENCE,
                            EpochLifecycleStatus.TERMINAL))
                    .thenReturn(Optional.of(epoch));
        }
        when(epoch.getWriterMode()).thenReturn(ContractTypes.WriterMode.TEMPORAL);
        org.mockito.Mockito.lenient()
                .when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        org.mockito.Mockito.lenient()
                .when(epoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);
        org.mockito.Mockito.lenient().when(epoch.getTenantSurrogate()).thenReturn("tenant-e2e");
        org.mockito.Mockito.lenient().when(epoch.getRoomEpoch()).thenReturn(4L);
        org.mockito.Mockito.lenient().when(epoch.getFencingToken()).thenReturn(9L);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetRoomCommandIngress> ingressProvider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        TargetRoomCommandIngress ingress = org.mockito.Mockito.mock(TargetRoomCommandIngress.class);
        org.mockito.Mockito.lenient().when(ingressProvider.getIfAvailable()).thenReturn(ingress);
        CaseCommandService commands = org.mockito.Mockito.mock(CaseCommandService.class);
        TargetEvidenceCompletionCommandMaterialStore materialStore =
                org.mockito.Mockito.mock(TargetEvidenceCompletionCommandMaterialStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetEvidenceCompletionCommandMaterialStore> materialProvider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.lenient()
                .when(materialProvider.getIfAvailable())
                .thenReturn(materialStore);
        EvidenceCompletionService targetService = new EvidenceCompletionService(
                caseRepository, completionRepository, evidenceRepository, roomRepository, clockRepository,
                dossierFreezer, evidenceWindowCoordinator, intakeProgressService, intakeMatrixLifecycleService,
                caseEventService, notificationService, lifecycleNotifications, hearingFlowRuntimeService,
                roomEpochAllocator, new DisputeProperties(Duration.ofHours(2), Duration.ofHours(3),
                        Duration.ofMinutes(20), Duration.ofSeconds(15), true),
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC),
                epochs, ingressProvider, commands, new ObjectMapper(), materialProvider);
        return new TargetFixture(
                targetService, ingressProvider, ingress, commands, materialStore, epochs, epoch);
    }

    private record TargetFixture(
            EvidenceCompletionService service,
            ObjectProvider<TargetRoomCommandIngress> ingressProvider,
            TargetRoomCommandIngress ingress,
            CaseCommandService commands,
            TargetEvidenceCompletionCommandMaterialStore materialStore,
            CaseRoomEpochRepository epochs,
            CaseRoomEpochEntity epoch) {}

    private static FulfillmentCaseEntity importedEvidenceCase(CaseStatus status) {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_COMPLETE",
                "ORDER-1",
                null,
                "LOG-1",
                "user-local",
                "merchant-local",
                "idem-complete",
                "SIGNED_NOT_RECEIVED",
                "Evidence dispute",
                "Evidence collection in progress",
                RiskLevel.HIGH,
                status,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-COMPLETE",
                "external-adapter");
    }
}
