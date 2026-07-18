package com.example.dispute.agentstream.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeup;
import com.example.dispute.agentstream.infrastructure.delivery.RedisAgentRunStreamWakeupPublisher;
import com.example.dispute.agentstream.infrastructure.delivery.WakeupPublishingAgentRunV2StreamStore;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    PostgresAgentRunV2EventStore.class,
    WakeupPublishingAgentRunV2StreamStore.class,
    RedisAgentRunStreamWakeupPublisher.class,
    JpaAgentRunLedger.class,
    AgentRunLiveWakeupIntegrationTest.InfrastructureConfig.class
})
class AgentRunLiveWakeupIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "agent_run_live_wakeup")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                            DockerImageName.parse("public.ecr.aws/docker/library/redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AgentRunLiveWakeupIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRunLedger ledger;
    @Autowired private AgentRunV2StreamStore streamStore;
    @Autowired private PostgresAgentRunV2EventStore eventStore;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void publishesOnlyAWakeupAfterCommitAndRecoversFromRedisLossByReplay() throws Exception {
        insertCase();
        AgentRunLedger.LogicalRun logical =
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun());
        AgentRunLedger.Attempt attempt =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_WAKEUP_1"),
                        AgentRunPersistenceFixtures.STARTED_AT);

        LinkedBlockingQueue<AgentRunStreamWakeup> wakeups = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnectionFactory);
        listener.addMessageListener(
                (message, pattern) -> {
                    try {
                        wakeups.add(
                                objectMapper.readValue(
                                        message.getBody(), AgentRunStreamWakeup.class));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                },
                new ChannelTopic(RedisAgentRunStreamWakeupPublisher.CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        try {
            AgentRunV2StreamStore.AppendReceipt first =
                    streamStore.append(event(attempt.attemptId(), 0, "first"));
            AgentRunStreamWakeup wakeup = wakeups.poll(5, TimeUnit.SECONDS);

            assertThat(first.inserted()).isTrue();
            assertThat(wakeup)
                    .isEqualTo(
                            new AgentRunStreamWakeup(
                                    AgentRunStreamWakeup.SCHEMA_VERSION,
                                    logical.agentRunId(),
                                    attempt.attemptId(),
                                    0));
        } finally {
            listener.stop();
        }

        REDIS.stop();
        AgentRunV2StreamStore.AppendReceipt duringRedisFailure =
                streamStore.append(event(attempt.attemptId(), 1, "still durable"));

        assertThat(duringRedisFailure.inserted()).isTrue();
        assertThat(duringRedisFailure.durableHighWatermark()).isEqualTo(1);
        assertThat(eventStore.replay(logical.agentRunId(), attempt.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(0L, 1L);
    }

    private AgentStreamEvent event(String attemptId, long sequence, String delta) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                AgentRunPersistenceFixtures.RUN_ID,
                attemptId,
                sequence,
                sequence == 0
                        ? StreamEventType.ATTEMPT_STARTED
                        : StreamEventType.VISIBLE_DELTA,
                Audience.USER,
                Instant.parse("2026-07-19T01:00:00Z").plusMillis(sequence),
                new Payload(
                        "answer",
                        sequence == 0 ? null : "text",
                        sequence == 0 ? null : delta,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private void insertCase() {
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (?, 'user-persistence', 'merchant-persistence', ?,
                          'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-persistence',
                          'MERCHANT', 'merchant-persistence', 'MEDIUM',
                          'AgentRun wakeup', 'AgentRun wakeup fixture',
                          'EVIDENCE', 'test', 'test')
                """,
                AgentRunPersistenceFixtures.CASE_ID,
                "idem-stream-wakeup");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRESQL.getHost()
                + ':'
                + POSTGRESQL.getMappedPort(5432)
                + "/agent_run_live_wakeup";
    }

    static class InfrastructureConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration standalone =
                    new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
            LettuceClientConfiguration client =
                    LettuceClientConfiguration.builder()
                            .commandTimeout(Duration.ofMillis(500))
                            .shutdownTimeout(Duration.ZERO)
                            .build();
            return new LettuceConnectionFactory(standalone, client);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }
}
