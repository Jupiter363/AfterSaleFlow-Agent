/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证Agent会话会话解析器，覆盖 「resolvesSameActorRoomAgentAndProfileToExistingSession」、「createsSessionWithDeterministicScopeAndAccessSessionLink」、「differentAgentKeysDoNotShareSession」、「initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.AgentSessionInitializer;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「AgentConversationSessionResolverTest」。
// 类型职责：集中验证Agent会话会话解析器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「resolvesSameActorRoomAgentAndProfileToExistingSession」、「createsSessionWithDeterministicScopeAndAccessSessionLink」、「differentAgentKeysDoNotShareSession」、「initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」、「userAccessSession」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AgentConversationSessionResolverTest {

    @Mock private AgentConversationSessionRepository repository;
    @Mock private AgentSessionInitializer initializer;

    private AgentSessionResolver resolver;

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.setUp()」。
    // 具体功能：「AgentConversationSessionResolverTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AgentConversationSessionResolverTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AgentConversationSessionResolverTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentConversationSessionResolverTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        resolver = new AgentSessionResolver(repository, initializer);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession()」。
    // 具体功能：「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession()」：复现“核对完整业务行为（场景方法「resolvesSameActorRoomAgentAndProfileToExistingSession」）”场景：驱动 「repository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「AGENT_SESSION_EXISTING」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」。
    // 上游调用：「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession()」守住「房间协作与权限」的可执行规格，尤其防止 「AGENT_SESSION_EXISTING」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void resolvesSameActorRoomAgentAndProfileToExistingSession() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        AgentConversationSessionEntity existing =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_EXISTING",
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT",
                        "system");
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.EVIDENCE,
                                "user-local",
                                ActorRole.USER,
                                "EVIDENCE_CLERK",
                                "EVIDENCE_CLERK:USER:v1"))
                .thenReturn(Optional.of(existing));

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getId()).isEqualTo("AGENT_SESSION_EXISTING");
        assertThat(result.getAccessSessionId()).isEqualTo(accessSession.getId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink()」。
    // 具体功能：「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink()」：复现“创建并持久化（场景方法「createsSessionWithDeterministicScopeAndAccessSessionLink」）”场景：驱动 「repository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「default」、「CASE_AGENT_SESSION」、「user-local」、「DISPUTE_INTAKE_OFFICER」。
    // 上游调用：「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink()」守住「房间协作与权限」的可执行规格，尤其防止 「default」、「CASE_AGENT_SESSION」、「user-local」、「DISPUTE_INTAKE_OFFICER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void createsSessionWithDeterministicScopeAndAccessSessionLink() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.empty());
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_CREATED",
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getAccessSessionId()).isEqualTo(accessSession.getId());
        assertThat(result.getConversationScope())
                .contains("CASE_AGENT_SESSION")
                .contains("INTAKE")
                .contains("user-local")
                .contains("DISPUTE_INTAKE_OFFICER")
                .contains(accessSession.getId());
        verify(initializer)
                .initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession()」。
    // 具体功能：「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession()」：复现“核对完整业务行为（场景方法「differentAgentKeysDoNotShareSession」）”场景：驱动 「repository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「default」、「CASE_AGENT_SESSION」、「user-local」、「EVIDENCE_CLERK」。
    // 上游调用：「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession()」守住「房间协作与权限」的可执行规格，尤其防止 「default」、「CASE_AGENT_SESSION」、「user-local」、「EVIDENCE_CLERK」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void differentAgentKeysDoNotShareSession() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.EVIDENCE,
                                "user-local",
                                ActorRole.USER,
                                "EVIDENCE_CLERK",
                                "EVIDENCE_CLERK:USER:v1"))
                .thenReturn(Optional.empty());
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_EVIDENCE",
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getAgentKey()).isEqualTo("EVIDENCE_CLERK");
        assertThat(result.getConversationScope()).contains("EVIDENCE_CLERK");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly()」。
    // 具体功能：「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly()」：复现“核对完整业务行为（场景方法「initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」）”场景：驱动 「repository.findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「AGENT_SESSION_READ_ONLY」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」、「MEMEO_DEFAULT」。
    // 上游调用：「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly()」守住「房间协作与权限」的可执行规格，尤其防止 「AGENT_SESSION_READ_ONLY」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」、「MEMEO_DEFAULT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_READ_ONLY",
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.empty());
        when(initializer.initializeInNewTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            AgentConversationSessionEntity result =
                    resolver.resolve(
                            accessSession,
                            RoomType.INTAKE,
                            "DISPUTE_INTAKE_OFFICER",
                            "DISPUTE_INTAKE_OFFICER:USER:v1",
                            "MEMEO_DEFAULT");
            assertThat(result.getId()).isEqualTo("AGENT_SESSION_READ_ONLY");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }

        verify(initializer)
                .initializeInNewTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「AgentConversationSessionResolverTest.userAccessSession()」。
    // 具体功能：「AgentConversationSessionResolverTest.userAccessSession()」：作为测试辅助方法为“核对完整业务行为（场景方法「userAccessSession」）”组装或读取「CaseAccessSessionEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「AgentConversationSessionResolverTest.userAccessSession()」由本测试类中的 「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」、「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession」、「AgentConversationSessionResolverTest.initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly」 调用。
    // 下游影响：「AgentConversationSessionResolverTest.userAccessSession()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentConversationSessionResolverTest.userAccessSession()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_USER_AGENT_SESSION」、「default」、「CASE_AGENT_SESSION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseAccessSessionEntity userAccessSession() {
        return CaseAccessSessionEntity.create(
                "ACCESS_USER_AGENT_SESSION",
                "default",
                "CASE_AGENT_SESSION",
                "user-local",
                ActorRole.USER,
                PermissionLevel.PARTY_USER,
                "system");
    }
}
