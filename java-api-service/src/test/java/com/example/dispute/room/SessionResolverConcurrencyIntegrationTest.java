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

    @BeforeEach
    void clearSessionsAndCases() {
        agentSessionRepository.deleteAll();
        accessSessionRepository.deleteAll();
        caseRepository.deleteAll();
    }

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
