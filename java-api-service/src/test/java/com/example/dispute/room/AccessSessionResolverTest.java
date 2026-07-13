/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证访问会话解析器，覆盖 「resolvesUserOwnerToPartyUserAccessSession」、「initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」、「rejectsPartyActorOutsideCaseParticipants」、「resolvesReviewerToReviewerAllWithoutCaseParticipation」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionInitializer;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「AccessSessionResolverTest」。
// 类型职责：集中验证访问会话解析器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「resolvesUserOwnerToPartyUserAccessSession」、「initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」、「rejectsPartyActorOutsideCaseParticipants」、「resolvesReviewerToReviewerAllWithoutCaseParticipation」、「dispute」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AccessSessionResolverTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private CaseAccessSessionRepository accessSessionRepository;
    @Mock private AccessSessionInitializer accessSessionInitializer;

    private AccessSessionResolver resolver;

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.setUp()」。
    // 具体功能：「AccessSessionResolverTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AccessSessionResolverTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AccessSessionResolverTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AccessSessionResolverTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        resolver =
                new AccessSessionResolver(
                        caseRepository,
                        participantRepository,
                        accessSessionRepository,
                        accessSessionInitializer);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession()」。
    // 具体功能：「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession()」：复现“核对完整业务行为（场景方法「resolvesUserOwnerToPartyUserAccessSession」）”场景：驱动 「caseRepository.findById」、「accessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「default」、「user-local」、「ACCESS_CREATED_USER」。
    // 上游调用：「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession()」守住「房间协作与权限」的可执行规格，尤其防止 「default」、「user-local」、「ACCESS_CREATED_USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void resolvesUserOwnerToPartyUserAccessSession() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                "user-local",
                                ActorRole.USER,
                                PermissionLevel.PARTY_USER))
                .thenReturn(Optional.empty());
        CaseAccessSessionEntity created =
                CaseAccessSessionEntity.create(
                        "ACCESS_CREATED_USER",
                        "default",
                        dispute.getId(),
                        "user-local",
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "user-local");
        when(accessSessionInitializer.initializeInCurrentTransaction(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        PermissionLevel.PARTY_USER))
                .thenReturn(created);

        CaseAccessSessionEntity session =
                resolver.resolve(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(session.getActorId()).isEqualTo("user-local");
        assertThat(session.getPermissionLevel()).isEqualTo(PermissionLevel.PARTY_USER);
        verify(accessSessionInitializer)
                .initializeInCurrentTransaction(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        PermissionLevel.PARTY_USER);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly()」。
    // 具体功能：「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly()」：复现“核对完整业务行为（场景方法「initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」）”场景：驱动 「caseRepository.findById」、「accessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「ACCESS_CREATED_READ_ONLY」、「default」。
    // 上游调用：「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「ACCESS_CREATED_READ_ONLY」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly() {
        FulfillmentCaseEntity dispute = dispute();
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity created =
                CaseAccessSessionEntity.create(
                        "ACCESS_CREATED_READ_ONLY",
                        "default",
                        dispute.getId(),
                        actor.actorId(),
                        actor.role(),
                        PermissionLevel.PARTY_USER,
                        actor.actorId());
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                actor.actorId(),
                                actor.role(),
                                PermissionLevel.PARTY_USER))
                .thenReturn(Optional.empty());
        when(accessSessionInitializer.initializeInNewTransaction(
                        dispute.getId(), actor, PermissionLevel.PARTY_USER))
                .thenReturn(created);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(resolver.resolve(dispute.getId(), actor).getId())
                    .isEqualTo("ACCESS_CREATED_READ_ONLY");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }

        verify(accessSessionInitializer)
                .initializeInNewTransaction(
                        dispute.getId(), actor, PermissionLevel.PARTY_USER);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants()」。
    // 具体功能：「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants()」：复现“拒绝非法输入或越权操作（场景方法「rejectsPartyActorOutsideCaseParticipants」）”场景：驱动 「caseRepository.findById」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「other-user」。
    // 上游调用：「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants()」守住「房间协作与权限」的可执行规格，尤其防止 「other-user」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsPartyActorOutsideCaseParticipants() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "other-user", ActorRole.USER))
                .thenReturn(false);

        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        dispute.getId(),
                                        new AuthenticatedActor("other-user", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("access session");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation()」。
    // 具体功能：「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation()」：复现“核对完整业务行为（场景方法「resolvesReviewerToReviewerAllWithoutCaseParticipation」）”场景：驱动 「caseRepository.findById」、「accessSessionRepository.findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「default」、「reviewer-local」、「ACCESS_EXISTING_REVIEWER」。
    // 上游调用：「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation()」守住「房间协作与权限」的可执行规格，尤其防止 「default」、「reviewer-local」、「ACCESS_EXISTING_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void resolvesReviewerToReviewerAllWithoutCaseParticipation() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                "reviewer-local",
                                ActorRole.PLATFORM_REVIEWER,
                                PermissionLevel.REVIEWER_ALL))
                .thenReturn(
                        Optional.of(
                                CaseAccessSessionEntity.create(
                                        "ACCESS_EXISTING_REVIEWER",
                                        "default",
                                        dispute.getId(),
                                        "reviewer-local",
                                        ActorRole.PLATFORM_REVIEWER,
                                        PermissionLevel.REVIEWER_ALL,
                                        "reviewer-local")));

        CaseAccessSessionEntity session =
                resolver.resolve(
                        dispute.getId(),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER));

        assertThat(session.getId()).isEqualTo("ACCESS_EXISTING_REVIEWER");
        assertThat(session.getPermissionLevel()).isEqualTo(PermissionLevel.REVIEWER_ALL);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AccessSessionResolverTest.dispute()」。
    // 具体功能：「AccessSessionResolverTest.dispute()」：作为测试辅助方法为“核对完整业务行为（场景方法「dispute」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「AccessSessionResolverTest.dispute()」由本测试类中的 「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」、「AccessSessionResolverTest.initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly」、「AccessSessionResolverTest.rejectsPartyActorOutsideCaseParticipants」、「AccessSessionResolverTest.resolvesReviewerToReviewerAllWithoutCaseParticipation」 调用。
    // 下游影响：「AccessSessionResolverTest.dispute()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AccessSessionResolverTest.dispute()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_ACCESS_SESSION」、「ORDER-ACCESS」、「LOG-ACCESS」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity dispute() {
        return FulfillmentCaseEntity.imported(
                "CASE_ACCESS_SESSION",
                "ORDER-ACCESS",
                null,
                "LOG-ACCESS",
                "user-local",
                "merchant-local",
                "idem-access",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-ACCESS",
                "external-adapter");
    }
}
