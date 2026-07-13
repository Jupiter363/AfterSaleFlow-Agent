/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据提交，覆盖 「submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「submitsHearingSupplementEvidenceToTheHearingRoom」、「deletesOnlyPendingEvidenceOwnedByCurrentActor」、「refusesToDeleteSubmittedEvidence」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceSubmissionCommand;
import com.example.dispute.evidence.application.EvidenceSubmissionService;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceSubmissionBatchEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceSubmissionBatchRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
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

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceSubmissionServiceTest」。
// 类型职责：集中验证证据提交的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「submitsHearingSupplementEvidenceToTheHearingRoom」、「deletesOnlyPendingEvidenceOwnedByCurrentActor」、「refusesToDeleteSubmittedEvidence」、「evidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceSubmissionServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceSubmissionBatchRepository batchRepository;
    @Mock private RoomMessageService roomMessageService;
    @Mock private AuditRecorder auditRecorder;

    private EvidenceSubmissionService service;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.setUp()」。
    // 具体功能：「EvidenceSubmissionServiceTest.setUp()」：在每个测试场景运行前创建「Clock.fixed」、「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceSubmissionServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceSubmissionServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceSubmissionServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-06T08:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new EvidenceSubmissionService(
                        caseRepository,
                        evidenceRepository,
                        batchRepository,
                        roomMessageService,
                        new ObjectMapper(),
                        auditRecorder,
                        Clock.fixed(
                                Instant.parse("2026-07-06T08:00:00Z"),
                                ZoneOffset.UTC));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk()」。
    // 具体功能：「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk()」：复现“核对完整业务行为（场景方法「submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」）”场景：驱动 「caseRepository.findByIdForUpdate」、「batchRepository.findByCaseIdAndIdempotencyKey」、「evidenceRepository.findAllById」、「batchRepository.save」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_ONE」、「EVIDENCE_TWO」、「submit-1」、「TRACE_1」。
    // 上游调用：「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_ONE」、「EVIDENCE_TWO」、「submit-1」、「TRACE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity one = evidence("EVIDENCE_ONE");
        EvidenceItemEntity two = evidence("EVIDENCE_TWO");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(batchRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "submit-1"))
                .thenReturn(Optional.empty());
        when(evidenceRepository.findAllById(List.of("EVIDENCE_ONE", "EVIDENCE_TWO")))
                .thenReturn(List.of(one, two));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMessageService.post(eq(dispute.getId()), eq(RoomType.EVIDENCE), any(), any(), any(), eq("TRACE_1")))
                .thenReturn(
                        new RoomMessageView(
                                "MESSAGE_BATCH",
                                dispute.getId(),
                                "ROOM_EVIDENCE",
                                4,
                                "USER",
                                "user-local",
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                "submitted",
                                List.of("EVIDENCE_ONE", "EVIDENCE_TWO"),
                                null,
                                null,
                                Instant.parse("2026-07-06T08:00:00Z")));

        var result =
                service.submit(
                        dispute.getId(),
                        new EvidenceSubmissionCommand(
                                List.of("EVIDENCE_ONE", "EVIDENCE_TWO"),
                                "本批为物流签收争议材料"),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "submit-1",
                        "TRACE_1");

        assertThat(result.batchId()).startsWith("EVIDENCE_BATCH_");
        assertThat(result.evidenceIds()).containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
        assertThat(result.roomMessage().attachmentRefs())
                .containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
        assertThat(one.getSubmissionStatus().name()).isEqualTo("SUBMITTED");
        assertThat(two.getSubmissionStatus().name()).isEqualTo("SUBMITTED");
        assertThat(one.getVisibility()).isEqualTo("PARTIES");
        assertThat(two.getVisibility()).isEqualTo("PARTIES");

        ArgumentCaptor<RoomMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(RoomMessageCommand.class);
        verify(roomMessageService)
                .post(
                        eq(dispute.getId()),
                        eq(RoomType.EVIDENCE),
                        commandCaptor.capture(),
                        any(),
                        eq("evidence-batch-message:submit-1"),
                        eq("TRACE_1"));
        assertThat(commandCaptor.getValue().messageType())
                .isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
        assertThat(commandCaptor.getValue().attachmentRefs())
                .containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom()」。
    // 具体功能：「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom()」：复现“核对完整业务行为（场景方法「submitsHearingSupplementEvidenceToTheHearingRoom」）”场景：驱动 「caseRepository.findByIdForUpdate」、「batchRepository.findByCaseIdAndIdempotencyKey」、「evidenceRepository.findAllById」、「batchRepository.save」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_HEARING_SUPPLEMENT」、「submit-hearing-1」、「TRACE_HEARING」、「MESSAGE_HEARING_BATCH」。
    // 上游调用：「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_HEARING_SUPPLEMENT」、「submit-hearing-1」、「TRACE_HEARING」、「MESSAGE_HEARING_BATCH」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void submitsHearingSupplementEvidenceToTheHearingRoom() {
        FulfillmentCaseEntity dispute = hearingCase();
        EvidenceItemEntity item = evidence("EVIDENCE_HEARING_SUPPLEMENT");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(batchRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "submit-hearing-1"))
                .thenReturn(Optional.empty());
        when(evidenceRepository.findAllById(List.of("EVIDENCE_HEARING_SUPPLEMENT")))
                .thenReturn(List.of(item));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMessageService.post(
                        eq(dispute.getId()),
                        eq(RoomType.HEARING),
                        any(),
                        any(),
                        any(),
                        eq("TRACE_HEARING")))
                .thenReturn(
                        new RoomMessageView(
                                "MESSAGE_HEARING_BATCH",
                                dispute.getId(),
                                "ROOM_HEARING",
                                8,
                                "USER",
                                "user-local",
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                "submitted",
                                List.of("EVIDENCE_HEARING_SUPPLEMENT"),
                                null,
                                1,
                                Instant.parse("2026-07-06T08:00:00Z")));

        var result =
                service.submit(
                        dispute.getId(),
                        new EvidenceSubmissionCommand(
                                List.of("EVIDENCE_HEARING_SUPPLEMENT"),
                                "庭审补充证据"),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "submit-hearing-1",
                        "TRACE_HEARING");

        assertThat(result.roomMessage().roomId()).isEqualTo("ROOM_HEARING");
        assertThat(item.getSubmissionStatus().name()).isEqualTo("SUBMITTED");

        ArgumentCaptor<RoomMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(RoomMessageCommand.class);
        verify(roomMessageService)
                .post(
                        eq(dispute.getId()),
                        eq(RoomType.HEARING),
                        commandCaptor.capture(),
                        any(),
                        eq("evidence-batch-message:submit-hearing-1"),
                        eq("TRACE_HEARING"));
        assertThat(commandCaptor.getValue().messageType())
                .isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
        assertThat(commandCaptor.getValue().attachmentRefs())
                .containsExactly("EVIDENCE_HEARING_SUPPLEMENT");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor()」。
    // 具体功能：「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor()」：复现“核对完整业务行为（场景方法「deletesOnlyPendingEvidenceOwnedByCurrentActor」）”场景：驱动 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「service.deletePending」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_PENDING」、「user-local」。
    // 上游调用：「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_PENDING」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void deletesOnlyPendingEvidenceOwnedByCurrentActor() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity pending = evidence("EVIDENCE_PENDING");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.deletePending(
                dispute.getId(),
                pending.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(pending.getDeletedAt()).isNotNull();
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence()」。
    // 具体功能：「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence()」：复现“核对完整业务行为（场景方法「refusesToDeleteSubmittedEvidence」）”场景：驱动 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「service.deletePending」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_SUBMITTED」、「BATCH_1」、「2026-07-06T08:00:00Z」、「user-local」。
    // 上游调用：「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_SUBMITTED」、「BATCH_1」、「2026-07-06T08:00:00Z」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void refusesToDeleteSubmittedEvidence() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity submitted = evidence("EVIDENCE_SUBMITTED");
        submitted.markSubmitted(
                "BATCH_1",
                OffsetDateTime.parse("2026-07-06T08:00:00Z"),
                "user-local");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(
                        () ->
                                service.deletePending(
                                        dispute.getId(),
                                        submitted.getId(),
                                        new AuthenticatedActor("user-local", ActorRole.USER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitted evidence cannot be deleted");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.evidence(String)」。
    // 具体功能：「EvidenceSubmissionServiceTest.evidence(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidence」）”组装或读取「EvidenceItemEntity.uploaded」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceSubmissionServiceTest.evidence(String)」由本测试类中的 「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」、「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor」、「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence」 调用。
    // 下游影响：「EvidenceSubmissionServiceTest.evidence(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceSubmissionServiceTest.evidence(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EVIDENCE_ROOM」、「DOSSIER_1」、「DOCUMENT」、「USER_UPLOAD」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity evidence(String id) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_EVIDENCE_ROOM",
                "DOSSIER_1",
                "DOCUMENT",
                "USER_UPLOAD",
                "USER",
                "user-local",
                "evidence-original",
                "case/" + id,
                "hash-" + id,
                id + ".md",
                "text/markdown",
                128,
                "PRIVATE",
                OffsetDateTime.parse("2026-07-06T07:30:00Z"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.evidenceCase()」。
    // 具体功能：「EvidenceSubmissionServiceTest.evidenceCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceSubmissionServiceTest.evidenceCase()」由本测试类中的 「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor」、「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence」 调用。
    // 下游影响：「EvidenceSubmissionServiceTest.evidenceCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceSubmissionServiceTest.evidenceCase()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EVIDENCE_ROOM」、「ORDER-EVIDENCE」、「LOG-EVIDENCE」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_ROOM",
                "ORDER-EVIDENCE",
                null,
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "证据室已开放",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-06T10:00:00Z"),
                "OMS",
                "EXT-EVIDENCE",
                "external-adapter");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSubmissionServiceTest.hearingCase()」。
    // 具体功能：「EvidenceSubmissionServiceTest.hearingCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「hearingCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceSubmissionServiceTest.hearingCase()」由本测试类中的 「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」 调用。
    // 下游影响：「EvidenceSubmissionServiceTest.hearingCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceSubmissionServiceTest.hearingCase()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EVIDENCE_ROOM」、「ORDER-EVIDENCE」、「LOG-EVIDENCE」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity hearingCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_ROOM",
                "ORDER-EVIDENCE",
                null,
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence-hearing",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "案件已经进入庭审阶段",
                RiskLevel.HIGH,
                CaseStatus.HEARING_OPEN,
                "HEARING",
                OffsetDateTime.parse("2026-07-06T10:00:00Z"),
                "OMS",
                "EXT-EVIDENCE-HEARING",
                "external-adapter");
    }
}
