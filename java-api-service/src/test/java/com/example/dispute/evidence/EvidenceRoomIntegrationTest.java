/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据房间Integration，覆盖 「bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「deadlineExpiryWithOnePartySealsAndOpensHearing」、「deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceRoomIntegrationTest」。
// 类型职责：集中验证证据房间Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「deadlineExpiryWithOnePartySealsAndOpensHearing」、「deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing」、「seedEvidenceCase」、「addEvidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    EvidenceCompletionService.class,
    EvidenceDossierFreezer.class,
    NotificationService.class,
    CaseLifecycleNotificationService.class,
    CaseEventService.class,
    EvidenceRoomIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class EvidenceRoomIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_evidence_room")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「EvidenceRoomIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceRoomIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceRoomIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_evidence_room");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private EvidenceCompletionService completionService;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceDossierRepository dossierRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private EvidenceVerificationRepository verificationRepository;
    @Autowired private EvidenceDossierItemRepository dossierItemRepository;
    @Autowired private EvidencePartyCompletionRepository completionRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @MockitoBean private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @MockitoBean private AccessSessionResolver accessSessionResolver;
    @MockitoBean private SessionPermissionService sessionPermissionService;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded()」。
    // 具体功能：「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded()」：复现“核对完整业务行为（场景方法「bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」）”场景：驱动 「completionService.complete」、「caseRepository.flush」、「caseRepository.findById」、「clockRepository.findByCaseIdAndClockType」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_EARLY」、「EVIDENCE_INCLUDED」、「EVIDENCE_REJECTED」、「user-local」。
    // 上游调用：「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EARLY」、「EVIDENCE_INCLUDED」、「EVIDENCE_REJECTED」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded() {
        seedEvidenceCase("CASE_EARLY");
        addEvidence("CASE_EARLY", "EVIDENCE_INCLUDED", EvidenceVerificationStatus.VERIFIED);
        addEvidence("CASE_EARLY", "EVIDENCE_REJECTED", EvidenceVerificationStatus.REJECTED);

        completionService.complete(
                "CASE_EARLY",
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-1");
        completionService.complete(
                "CASE_EARLY",
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-complete-1");
        caseRepository.flush();

        FulfillmentCaseEntity dispute =
                caseRepository.findById("CASE_EARLY").orElseThrow();
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.HEARING_OPEN);
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EARLY",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getClockStatus())
                .isEqualTo(PhaseClockStatus.COMPLETED_EARLY);
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EARLY",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getCompletionReason())
                .isEqualTo("BOTH_PARTIES_COMPLETED");
        assertThat(
                        roomRepository
                                .findByCaseIdAndRoomType("CASE_EARLY", RoomType.EVIDENCE)
                                .orElseThrow()
                                .getRoomStatus())
                .isEqualTo(RoomStatus.SEALED);
        assertThat(
                        roomRepository
                                .findByCaseIdAndRoomType("CASE_EARLY", RoomType.HEARING)
                                .orElseThrow()
                                .getRoomStatus())
                .isEqualTo(RoomStatus.OPEN);

        EvidenceDossierEntity frozen =
                dossierRepository
                        .findByCaseIdAndDossierVersion("CASE_EARLY", 1)
                        .orElseThrow();
        assertThat(frozen.getDossierStatus()).isEqualTo("FROZEN");
        assertThat(dossierItemRepository.findAllByDossierIdOrderBySequenceNo(frozen.getId()))
                .extracting(item -> item.getEvidenceId())
                .containsExactly("EVIDENCE_INCLUDED");
        assertThat(
                        completionRepository
                                .findAllByCaseIdAndDossierVersionAndCompletionStatus(
                                        "CASE_EARLY", 1, "COMPLETED"))
                .hasSize(2);
        assertThat(eventRepository.findByCaseIdAndEventKey("CASE_EARLY", "hearing-opened:1"))
                .isPresent();
        assertThat(notificationRepository.findAllByRecipientIdOrderByCreatedAtDesc("user-local"))
                .anySatisfy(
                        notification ->
                                assertThat(notification.getDeepLink())
                                        .isEqualTo("/disputes/CASE_EARLY/hearing"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing()」。
    // 具体功能：「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing()」：复现“核对完整业务行为（场景方法「deadlineExpiryWithOnePartySealsAndOpensHearing」）”场景：驱动 「completionService.complete」、「completionService.expire」、「caseRepository.flush」、「clockRepository.findByCaseIdAndClockType」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_EXPIRED」、「EVIDENCE_EXPIRED_INITIATOR」、「user-local」、「user-complete-expiry」。
    // 上游调用：「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EXPIRED」、「EVIDENCE_EXPIRED_INITIATOR」、「user-local」、「user-complete-expiry」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void deadlineExpiryWithOnePartySealsAndOpensHearing() {
        seedEvidenceCase("CASE_EXPIRED");
        addEvidence("CASE_EXPIRED", "EVIDENCE_EXPIRED_INITIATOR", EvidenceVerificationStatus.VERIFIED);
        completionService.complete(
                "CASE_EXPIRED",
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-expiry");

        var status = completionService.expire("CASE_EXPIRED");
        caseRepository.flush();

        assertThat(status.sealed()).isTrue();
        assertThat(status.nextRoom()).isEqualTo("HEARING");
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EXPIRED",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getClockStatus())
                .isEqualTo(PhaseClockStatus.EXPIRED);
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EXPIRED",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getCompletionReason())
                .isEqualTo("DEADLINE_EXPIRED");
        assertThat(dossierRepository.findByCaseIdAndDossierVersion("CASE_EXPIRED", 1))
                .hasValueSatisfying(
                        dossier -> assertThat(dossier.getDossierStatus()).isEqualTo("FROZEN"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing()」。
    // 具体功能：「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing()」：复现“核对完整业务行为（场景方法「deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing」）”场景：驱动 「completionService.expire」、「caseRepository.flush」、「caseRepository.findById」、「roomRepository.findByCaseIdAndRoomType」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_NO_SUBMISSIONS」、「EVIDENCE」。
    // 上游调用：「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_NO_SUBMISSIONS」、「EVIDENCE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing() {
        seedEvidenceCase("CASE_NO_SUBMISSIONS");

        assertThatThrownBy(() -> completionService.expire("CASE_NO_SUBMISSIONS"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("发起争议方需先正式提交至少 1 份相关证据");
        caseRepository.flush();

        FulfillmentCaseEntity dispute =
                caseRepository.findById("CASE_NO_SUBMISSIONS").orElseThrow();
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(dispute.getCurrentRoom()).isEqualTo("EVIDENCE");
        assertThat(roomRepository.findByCaseIdAndRoomType("CASE_NO_SUBMISSIONS", RoomType.HEARING))
                .isEmpty();
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.seedEvidenceCase(String)」。
    // 具体功能：「EvidenceRoomIntegrationTest.seedEvidenceCase(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedEvidenceCase」）”组装或读取「caseRepository.saveAndFlush」、「dossierRepository.saveAndFlush」、「roomRepository.saveAndFlush」、「clockRepository.saveAndFlush」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceRoomIntegrationTest.seedEvidenceCase(String)」由本测试类中的 「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing」、「EvidenceRoomIntegrationTest.deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing」 调用。
    // 下游影响：「EvidenceRoomIntegrationTest.seedEvidenceCase(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomIntegrationTest.seedEvidenceCase(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「ORDER-」、「LOG-」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedEvidenceCase(String caseId) {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        caseId,
                        "ORDER-" + caseId,
                        null,
                        "LOG-" + caseId,
                        "user-local",
                        "merchant-local",
                        "idem-" + caseId,
                        "SIGNED_NOT_RECEIVED",
                        "包裹显示签收但用户未收到",
                        "双方进入举证阶段",
                        RiskLevel.HIGH,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "OMS",
                        "EXT-" + caseId,
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);
        EvidenceDossierEntity collecting =
                EvidenceDossierEntity.collecting(
                        "DOSSIER_COLLECTING_" + caseId, caseId, "system");
        dossierRepository.saveAndFlush(collecting);
        CaseRoomEntity evidenceRoom =
                roomRepository.saveAndFlush(
                        CaseRoomEntity.open(
                                "ROOM_EVIDENCE_" + caseId,
                                caseId,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                                "system"));
        clockRepository.saveAndFlush(
                CasePhaseClockEntity.running(
                        "CLOCK_EVIDENCE_" + caseId,
                        caseId,
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "evidence-window-" + caseId,
                        "system"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.addEvidence(String,String,EvidenceVerificationStatus)」。
    // 具体功能：「EvidenceRoomIntegrationTest.addEvidence(String,String,EvidenceVerificationStatus)」：作为测试辅助方法为“核对完整业务行为（场景方法「addEvidence」）”组装或读取「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.saveAndFlush」、「verificationRepository.saveAndFlush」、「EvidenceItemEntity.uploaded」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceRoomIntegrationTest.addEvidence(String,String,EvidenceVerificationStatus)」由本测试类中的 「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「EvidenceRoomIntegrationTest.deadlineExpiryWithOnePartySealsAndOpensHearing」 调用。
    // 下游影响：「EvidenceRoomIntegrationTest.addEvidence(String,String,EvidenceVerificationStatus)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomIntegrationTest.addEvidence(String,String,EvidenceVerificationStatus)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「LOGISTICS_PROOF」、「USER_UPLOAD」、「USER」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void addEvidence(
            String caseId, String evidenceId, EvidenceVerificationStatus status) {
        EvidenceDossierEntity collecting =
                dossierRepository.findByCaseIdAndDossierVersion(caseId, 0).orElseThrow();
        EvidenceItemEntity evidence =
                EvidenceItemEntity.uploaded(
                        evidenceId,
                        caseId,
                        collecting.getId(),
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "USER",
                        "user-local",
                        "evidence-original",
                        caseId + "/" + evidenceId + "/proof.png",
                        "hash-" + evidenceId,
                        "proof.png",
                        "image/png",
                        12,
                        "PARTIES",
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"));
        evidence.markSubmitted(
                "EVIDENCE_BATCH_" + evidenceId,
                OffsetDateTime.parse("2026-07-03T00:10:00Z"),
                "user-local");
        evidenceRepository.saveAndFlush(evidence);
        verificationRepository.saveAndFlush(
                EvidenceVerificationEntity.create(
                        "VERIFY_" + evidenceId,
                        caseId,
                        evidenceId,
                        1,
                        status,
                        "{}",
                        "{}",
                        "[]",
                        false,
                        Instant.parse("2026-07-03T00:30:00Z"),
                        "system",
                        "trace-" + evidenceId));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「FixedClockConfiguration」。
    // 类型职责：在 Spring 启动期装配Fixed时钟所需 Bean 和基础设施参数；本类型显式提供 「fixedClock」、「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.FixedClockConfiguration.fixedClock()」。
        // 具体功能：「EvidenceRoomIntegrationTest.FixedClockConfiguration.fixedClock()」：作为测试辅助方法为“核对完整业务行为（场景方法「fixedClock」）”组装或读取「Clock.fixed」、「Instant.parse」，供本测试类的场景方法复用。
        // 上游调用：「EvidenceRoomIntegrationTest.FixedClockConfiguration.fixedClock()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「EvidenceRoomIntegrationTest.FixedClockConfiguration.fixedClock()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「EvidenceRoomIntegrationTest.FixedClockConfiguration.fixedClock()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        }

        // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomIntegrationTest.FixedClockConfiguration.objectMapper()」。
        // 具体功能：「EvidenceRoomIntegrationTest.FixedClockConfiguration.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「EvidenceRoomIntegrationTest.FixedClockConfiguration.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「EvidenceRoomIntegrationTest.FixedClockConfiguration.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「EvidenceRoomIntegrationTest.FixedClockConfiguration.objectMapper()」守住「证据与版本化卷宗」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
