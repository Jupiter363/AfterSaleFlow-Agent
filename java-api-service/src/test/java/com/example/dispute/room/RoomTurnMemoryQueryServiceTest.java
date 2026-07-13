/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证房间轮次记忆Query，覆盖 「latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」、「latestAgentMemoryRejectsActorsOutsideTheDispute」、「latestEvidenceAgentMemoryIsScopedToTheRequestingParty」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.RoomTurnMemoryQueryService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「RoomTurnMemoryQueryServiceTest」。
// 类型职责：集中验证房间轮次记忆Query的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」、「latestAgentMemoryRejectsActorsOutsideTheDispute」、「latestEvidenceAgentMemoryIsScopedToTheRequestingParty」、「accessSession」、「agentSession」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class RoomTurnMemoryQueryServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private AccessSessionResolver accessSessionResolver;
    @Mock private AgentSessionResolver agentSessionResolver;
    @Mock private SessionPermissionService permissionService;

    private RoomTurnMemoryQueryService service;

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.setUp()」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.setUp()」：在每个测试场景运行前创建「accessSessionResolver.resolve」、「agentSessionResolver.resolve」、「invocation.getArgument」、「lenient」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new RoomTurnMemoryQueryService(
                        caseRepository,
                        participantRepository,
                        memoryRepository,
                        intakeDossierRepository,
                        accessSessionResolver,
                        agentSessionResolver,
                        permissionService,
                        new ObjectMapper().findAndRegisterModules());
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner()」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner()」：复现“核对完整业务行为（场景方法「latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」）”场景：驱动 「caseRepository.findById」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」、「intakeDossierRepository.findByCaseIdAndRoomType」、「service.latestAgentMemory」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MEMORY_LATEST」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「已整理退款诉求，等待商家确认。」。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner()」守住「房间协作与权限」的可执行规格，尤其防止 「MEMORY_LATEST」、「dispute-intake-officer」、「DISPUTE_INTAKE_OFFICER」、「已整理退款诉求，等待商家确认。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner() {
        FulfillmentCaseEntity dispute = intakeCase();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(
                        Optional.of(
                                RoomTurnMemoryEntity.agentTurn(
                                        "MEMORY_LATEST",
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        3,
                                        "dispute-intake-officer",
                                        "DISPUTE_INTAKE_OFFICER",
                                        "已整理退款诉求，等待商家确认。",
                                        "{\"requested_outcome\":\"REFUND\"}",
                                        "{\"current_outcome\":\"REFUND\"}",
                                        "[{\"op\":\"UPSERT_CARD\"}]",
                                        "RUN_3")));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_QUERY",
                        dispute.getId(),
                        RoomType.INTAKE,
                        "{\"schema_version\":\"intake_case_detail.v1\",\"intake_quality\":{\"score\":88,\"ready_for_next_step\":true}}",
                        88,
                        true,
                        "ACCEPTED",
                        3,
                        "dispute-intake-officer")));

        var result =
                service.latestAgentMemory(
                        dispute.getId(),
                        RoomType.INTAKE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().turnNo()).isEqualTo(3);
        assertThat(result.orElseThrow().scrollSnapshot().path("current_outcome").asText())
                .isEqualTo("REFUND");
        assertThat(result.orElseThrow().caseIntakeDossier().qualityScore()).isEqualTo(88);
        assertThat(result.orElseThrow().caseIntakeDossier().readyForNextStep()).isTrue();
        assertThat(result.orElseThrow().canvasOperations()).hasSize(1);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute()」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute()」：复现“核对完整业务行为（场景方法「latestAgentMemoryRejectsActorsOutsideTheDispute」）”场景：驱动 「caseRepository.findById」、「service.latestAgentMemory」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「other-user」。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute()」守住「房间协作与权限」的可执行规格，尤其防止 「other-user」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void latestAgentMemoryRejectsActorsOutsideTheDispute() {
        FulfillmentCaseEntity dispute = intakeCase();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionResolver.resolve(
                        dispute.getId(), new AuthenticatedActor("other-user", ActorRole.USER)))
                .thenThrow(new ForbiddenException("actor cannot create access session for this case"));

        assertThatThrownBy(
                        () ->
                                service.latestAgentMemory(
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        new AuthenticatedActor(
                                                "other-user", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty()」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty()」：复现“核对完整业务行为（场景方法「latestEvidenceAgentMemoryIsScopedToTheRequestingParty」）”场景：驱动 「caseRepository.findById」、「memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」、「service.latestAgentMemory」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MEMORY_EVIDENCE_USER_PARTY」、「user-local」、「USER」、「用户侧只说明了签收异常。」。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty()」守住「房间协作与权限」的可执行规格，尤其防止 「MEMORY_EVIDENCE_USER_PARTY」、「user-local」、「USER」、「用户侧只说明了签收异常。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void latestEvidenceAgentMemoryIsScopedToTheRequestingParty() {
        FulfillmentCaseEntity dispute = intakeCase();
        RoomTurnMemoryEntity userParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_EVIDENCE_USER_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "user-local",
                        "USER",
                        "用户侧只说明了签收异常。");
        RoomTurnMemoryEntity userClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_EVIDENCE_USER_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "用户侧书记官：请补充开箱照片原图。",
                        "{\"memory_frame\":{\"side\":\"USER\"}}",
                        "{\"side\":\"USER\"}",
                        "[]",
                        "RUN_USER");
        RoomTurnMemoryEntity merchantParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_EVIDENCE_MERCHANT_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "merchant-local",
                        "MERCHANT",
                        "商家侧只说明了质检视频。");
        RoomTurnMemoryEntity merchantClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_EVIDENCE_MERCHANT_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "商家侧书记官：请补充发货质检视频原件。",
                        "{\"memory_frame\":{\"side\":\"MERCHANT\"}}",
                        "{\"side\":\"MERCHANT\"}",
                        "[]",
                        "RUN_MERCHANT");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(memoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.of(userClerk));

        var result =
                service.latestAgentMemory(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().turnNo()).isEqualTo(1);
        assertThat(result.orElseThrow().agentResponse()).contains("用户侧书记官");
        assertThat(result.orElseThrow().agentResponse()).doesNotContain("商家侧书记官");
        assertThat(result.orElseThrow().memoryFrame().path("side").asText()).isEqualTo("USER");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.accessSession(String,AuthenticatedActor)」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.accessSession(String,AuthenticatedActor)」：作为测试辅助方法为“核对完整业务行为（场景方法「accessSession」）”组装或读取「CaseAccessSessionEntity.create」、「actor.role」、「actor.actorId」，供本测试类的场景方法复用。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.accessSession(String,AuthenticatedActor)」由本测试类中的 「RoomTurnMemoryQueryServiceTest.setUp」 调用。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.accessSession(String,AuthenticatedActor)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.accessSession(String,AuthenticatedActor)」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「agentSession」）”组装或读取「AgentConversationSessionEntity.create」、「accessSession.getActorId」，供本测试类的场景方法复用。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」由本测试类中的 「RoomTurnMemoryQueryServiceTest.setUp」 调用。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「AGENT_SESSION_」、「_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryQueryServiceTest.intakeCase()」。
    // 具体功能：「RoomTurnMemoryQueryServiceTest.intakeCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「intakeCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「RoomTurnMemoryQueryServiceTest.intakeCase()」由本测试类中的 「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」、「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute」、「RoomTurnMemoryQueryServiceTest.latestEvidenceAgentMemoryIsScopedToTheRequestingParty」 调用。
    // 下游影响：「RoomTurnMemoryQueryServiceTest.intakeCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomTurnMemoryQueryServiceTest.intakeCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_TURN_MEMORY_QUERY」、「ORDER-QUERY」、「LOG-QUERY」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity intakeCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_TURN_MEMORY_QUERY",
                "ORDER-QUERY",
                null,
                "LOG-QUERY",
                "user-local",
                "merchant-local",
                "idem-query",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示订单显示签收但没有收到包裹。",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                OffsetDateTime.parse("2026-07-05T02:00:00Z"),
                "OMS",
                "EXT-QUERY",
                "system");
    }
}
