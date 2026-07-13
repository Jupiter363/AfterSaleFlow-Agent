/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证会话解析器ConcurrencyIntegration，覆盖 「concurrentAccessSessionResolutionCreatesOneScopeRow」、「concurrentAgentSessionResolutionCreatesOneScopeRow」、「readOnlyCallerCanInitializeBothSessionLevels」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionInitializer;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionInitializer;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「SessionResolverConcurrencyIntegrationTest」。
// 类型职责：集中验证会话解析器ConcurrencyIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「clearSessionsAndCases」、「concurrentAccessSessionResolutionCreatesOneScopeRow」、「concurrentAgentSessionResolutionCreatesOneScopeRow」、「readOnlyCallerCanInitializeBothSessionLevels」、「persistCase」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    AccessSessionResolver.class,
    AccessSessionInitializer.class,
    AgentSessionResolver.class,
    AgentSessionInitializer.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionResolverConcurrencyIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "session_resolver")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「房间协作与权限」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/session_resolver");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private AccessSessionResolver accessSessionResolver;
    @Autowired private AgentSessionResolver agentSessionResolver;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseAccessSessionRepository accessSessionRepository;
    @Autowired private AgentConversationSessionRepository agentSessionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.clearSessionsAndCases()」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.clearSessionsAndCases()」：在每个测试场景运行前创建「agentSessionRepository.deleteAll」、「accessSessionRepository.deleteAll」、「caseRepository.deleteAll」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.clearSessionsAndCases()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.clearSessionsAndCases()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.clearSessionsAndCases()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void clearSessionsAndCases() {
        agentSessionRepository.deleteAll();
        accessSessionRepository.deleteAll();
        caseRepository.deleteAll();
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow()」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow()」：复现“核对完整业务行为（场景方法「concurrentAccessSessionResolutionCreatesOneScopeRow」）”场景：驱动 「accessSessionRepository.count」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_access_concurrency」、「user-access-concurrency」。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_access_concurrency」、「user-access-concurrency」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void concurrentAccessSessionResolutionCreatesOneScopeRow() throws Exception {
        String caseId = "CASE_access_concurrency";
        AuthenticatedActor actor = persistCase(caseId, "user-access-concurrency");

        List<CaseAccessSessionEntity> sessions =
                runConcurrently(12, () -> accessSessionResolver.resolve(caseId, actor));

        assertThat(sessions)
                .extracting(CaseAccessSessionEntity::getId)
                .containsOnly(sessions.getFirst().getId());
        assertThat(accessSessionRepository.count()).isEqualTo(1);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow()」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow()」：复现“核对完整业务行为（场景方法「concurrentAgentSessionResolutionCreatesOneScopeRow」）”场景：驱动 「agentSessionRepository.count」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_agent_concurrency」、「user-agent-concurrency」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_agent_concurrency」、「user-agent-concurrency」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void concurrentAgentSessionResolutionCreatesOneScopeRow() throws Exception {
        String caseId = "CASE_agent_concurrency";
        AuthenticatedActor actor = persistCase(caseId, "user-agent-concurrency");
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);

        List<AgentConversationSessionEntity> sessions =
                runConcurrently(
                        12,
                        () ->
                                agentSessionResolver.resolve(
                                        accessSession,
                                        RoomType.INTAKE,
                                        "DISPUTE_INTAKE_OFFICER",
                                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                                        "MEMEO_DEFAULT"));

        assertThat(sessions)
                .extracting(AgentConversationSessionEntity::getId)
                .containsOnly(sessions.getFirst().getId());
        assertThat(agentSessionRepository.count()).isEqualTo(1);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels()」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels()」：复现“核对完整业务行为（场景方法「readOnlyCallerCanInitializeBothSessionLevels」）”场景：驱动 「accessSessionRepository.count」、「agentSessionRepository.count」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_read_only_session」、「user-read-only-session」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_read_only_session」、「user-read-only-session」、「DISPUTE_INTAKE_OFFICER」、「DISPUTE_INTAKE_OFFICER:USER:v1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void readOnlyCallerCanInitializeBothSessionLevels() {
        String caseId = "CASE_read_only_session";
        AuthenticatedActor actor = persistCase(caseId, "user-read-only-session");
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        AgentConversationSessionEntity session =
                readOnly.execute(
                        ignored -> {
                            CaseAccessSessionEntity accessSession =
                                    accessSessionResolver.resolve(caseId, actor);
                            return agentSessionResolver.resolve(
                                    accessSession,
                                    RoomType.INTAKE,
                                    "DISPUTE_INTAKE_OFFICER",
                                    "DISPUTE_INTAKE_OFFICER:USER:v1",
                                    "MEMEO_DEFAULT");
                        });

        assertThat(session).isNotNull();
        assertThat(accessSessionRepository.count()).isEqualTo(1);
        assertThat(agentSessionRepository.count()).isEqualTo(1);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.persistCase(String,String)」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.persistCase(String,String)」：作为测试辅助方法为“持久化业务事实（场景方法「persistCase」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.persistCase(String,String)」由本测试类中的 「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow」、「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow」、「SessionResolverConcurrencyIntegrationTest.readOnlyCallerCanInitializeBothSessionLevels」 调用。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.persistCase(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.persistCase(String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「ORDER_」、「merchant-local」、「IDEM_」、「FULFILLMENT_CONFLICT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private AuthenticatedActor persistCase(String caseId, String userId) {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        userId,
                        "merchant-local",
                        "IDEM_" + caseId,
                        "FULFILLMENT_CONFLICT",
                        "Session resolver concurrency",
                        "Concurrent requests resolve the same session scope.",
                        RiskLevel.MEDIUM,
                        userId));
        return new AuthenticatedActor(userId, ActorRole.USER);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionResolverConcurrencyIntegrationTest.runConcurrently(int,Callable)」。
    // 具体功能：「SessionResolverConcurrencyIntegrationTest.runConcurrently(int,Callable)」：作为测试辅助方法为“核对完整业务行为（场景方法「runConcurrently」）”组装或读取「CountDownLatch」、「ArrayList」、「IllegalStateException」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「SessionResolverConcurrencyIntegrationTest.runConcurrently(int,Callable)」由本测试类中的 「SessionResolverConcurrencyIntegrationTest.concurrentAccessSessionResolutionCreatesOneScopeRow」、「SessionResolverConcurrencyIntegrationTest.concurrentAgentSessionResolutionCreatesOneScopeRow」 调用。
    // 下游影响：「SessionResolverConcurrencyIntegrationTest.runConcurrently(int,Callable)」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionResolverConcurrencyIntegrationTest.runConcurrently(int,Callable)」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static <T> List<T> runConcurrently(int workerCount, Callable<T> task)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workerCount; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("concurrent start timed out");
                                    }
                                    return task.call();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
