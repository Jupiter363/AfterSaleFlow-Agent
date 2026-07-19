package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Testcontainers
@Import({
    PostgresAgentRunV2EventStore.class,
    com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger.class,
    AgentRunStreamReplayIntegrationTest.JsonTestConfig.class
})
class AgentRunStreamReplayIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "agent_run_stream_replay")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AgentRunStreamReplayIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRunLedger ledger;
    @Autowired private PostgresAgentRunV2EventStore eventStore;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void batchAppendAndRequiresNewPreserveReplayAcrossConflictsAttemptsAndOuterRollback() {
        insertCase();
        AgentRunLedger.LogicalRun logical =
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun());
        AgentRunLedger.Attempt first =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_STREAM_1"),
                        AgentRunPersistenceFixtures.STARTED_AT);

        List<AgentStreamEvent> firstBatch =
                List.of(
                        event(first.attemptId(), 0, StreamEventType.ATTEMPT_STARTED, null),
                        event(first.attemptId(), 1, StreamEventType.VISIBLE_DELTA, "alpha"),
                        event(first.attemptId(), 2, StreamEventType.VISIBLE_DELTA, " beta"));

        PostgresAgentRunV2EventStore.BatchAppendReceipt receipt =
                eventStore.appendBatch(firstBatch);

        assertThat(receipt.insertedCount()).isEqualTo(3);
        assertThat(receipt.durableHighWatermark()).isEqualTo(2);
        assertThat(eventStore.replay(logical.agentRunId(), first.attemptId(), 0, 100))
                .extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(1L, 2L);

        assertThat(eventStore.append(firstBatch.get(1)))
                .satisfies(
                        duplicate -> {
                            assertThat(duplicate.inserted()).isFalse();
                            assertThat(duplicate.durableHighWatermark()).isEqualTo(2);
                        });
        assertThatThrownBy(
                        () ->
                                eventStore.append(
                                        event(
                                                first.attemptId(),
                                                1,
                                                StreamEventType.VISIBLE_DELTA,
                                                "conflicting")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload hash");
        assertThat(eventStore.durableHighWatermark(logical.agentRunId(), first.attemptId()))
                .isEqualTo(2);

        ledger.recordAttemptFailure(
                logical.agentRunId(),
                first.attemptId(),
                1,
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                true,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        AgentRunLedger.Attempt second =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.request(2, "ATTEMPT_STREAM_2"),
                        AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(1));
        eventStore.append(event(second.attemptId(), 0, StreamEventType.ATTEMPT_STARTED, null));

        String committedTitle =
                jdbc.queryForObject(
                        "select title from fulfillment_dispute_case where id = ?",
                        String.class,
                        AgentRunPersistenceFixtures.CASE_ID);
        AtomicReference<AppendReceipt> appendReceipt = new AtomicReference<>();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "update fulfillment_dispute_case set title = ? where id = ?",
                            "outer transaction must roll back",
                            AgentRunPersistenceFixtures.CASE_ID);
                    appendReceipt.set(
                            eventStore.append(
                                    event(
                                            second.attemptId(),
                                            1,
                                            StreamEventType.VISIBLE_DELTA,
                                            "durable after outer rollback")));
                    status.setRollbackOnly();
                });

        assertThat(eventStore.durableHighWatermark(logical.agentRunId(), second.attemptId()))
                .isEqualTo(1);
        assertThat(appendReceipt.get())
                .isNotNull()
                .satisfies(
                        durableReceipt -> {
                            assertThat(durableReceipt.inserted()).isTrue();
                            assertThat(durableReceipt.durableHighWatermark()).isEqualTo(1);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "select title from fulfillment_dispute_case where id = ?",
                                String.class,
                                AgentRunPersistenceFixtures.CASE_ID))
                .isEqualTo(committedTitle);
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::attemptId, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(second.attemptId(), 0L),
                        org.assertj.core.groups.Tuple.tuple(second.attemptId(), 1L));
        assertThat(eventStore.replay(logical.agentRunId(), first.attemptId(), -1, 100))
                .hasSize(3)
                .allMatch(event -> event.attemptId().equals(first.attemptId()));
    }

    private AgentStreamEvent event(
            String attemptId, long sequence, StreamEventType eventType, String delta) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                AgentRunPersistenceFixtures.RUN_ID,
                attemptId,
                sequence,
                eventType,
                Audience.USER,
                Instant.parse("2026-07-19T01:00:00Z").plusMillis(sequence),
                new Payload(
                        "answer",
                        delta == null ? null : "text",
                        delta,
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
                          'AgentRun stream replay', 'AgentRun stream replay fixture',
                          'EVIDENCE', 'test', 'test')
                """,
                AgentRunPersistenceFixtures.CASE_ID,
                "idem-stream-replay");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRESQL.getHost()
                + ':'
                + POSTGRESQL.getMappedPort(5432)
                + "/agent_run_stream_replay";
    }

    static class JsonTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
