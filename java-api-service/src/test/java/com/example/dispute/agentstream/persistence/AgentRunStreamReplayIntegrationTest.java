package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
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
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun("ATTEMPT_STREAM_1"));
        AgentRunLedger.Attempt first =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_STREAM_1"),
                        AgentRunPersistenceFixtures.STARTED_AT);

        List<AgentStreamEvent> firstBatch =
                List.of(
                        event(first.attemptId(), 0, StreamEventType.ATTEMPT_STARTED, null),
                        event(first.attemptId(), 1, StreamEventType.VISIBLE_DELTA, "alpha"),
                        event(first.attemptId(), 2, StreamEventType.VISIBLE_DELTA, " beta"));

        BatchAppendReceipt receipt = eventStore.appendBatch(firstBatch);

        assertThat(receipt.insertedCount()).isEqualTo(3);
        assertThat(receipt.inserted()).containsExactly(true, true, true);
        assertThat(receipt.durableHighWatermark()).isEqualTo(2);
        assertThat(attemptProgress(first.attemptId()))
                .isEqualTo(new AttemptProgress(2, true, false));
        assertThat(eventStore.replay(logical.agentRunId(), first.attemptId(), 0, 100))
                .extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(1L, 2L);

        BatchAppendReceipt duplicateBatch = eventStore.appendBatch(firstBatch);
        assertThat(duplicateBatch.inserted()).containsExactly(false, false, false);
        assertThat(duplicateBatch.insertedCount()).isZero();
        assertThat(duplicateBatch.durableHighWatermark()).isEqualTo(2);
        AgentStreamEvent newSuffix =
                event(first.attemptId(), 3, StreamEventType.VISIBLE_DELTA, " gamma");
        BatchAppendReceipt duplicatePrefixAndNewSuffix =
                eventStore.appendBatch(List.of(firstBatch.get(1), firstBatch.get(2), newSuffix));
        assertThat(duplicatePrefixAndNewSuffix.inserted()).containsExactly(false, false, true);
        assertThat(duplicatePrefixAndNewSuffix.insertedCount()).isEqualTo(1);
        assertThat(duplicatePrefixAndNewSuffix.durableHighWatermark()).isEqualTo(3);
        assertThatThrownBy(
                        () ->
                                eventStore.appendBatch(
                                        List.of(firstBatch.get(2), firstBatch.get(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");

        assertThat(eventStore.append(firstBatch.get(1)))
                .satisfies(
                        duplicate -> {
                            assertThat(duplicate.inserted()).isFalse();
                            assertThat(duplicate.durableHighWatermark()).isEqualTo(3);
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
                .isEqualTo(3);

        ledger.recordAttemptFailure(
                logical.agentRunId(),
                first.attemptId(),
                1,
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        jdbc.update(
                """
                update agent_run_attempt
                   set last_sequence_no = 0,
                       public_output_emitted = false
                 where id = ?
                """,
                first.attemptId());
        AttemptProgress terminalProgress = new AttemptProgress(3, true, false);
        AgentStreamEvent lateVisibleDelta =
                event(first.attemptId(), 4, StreamEventType.VISIBLE_DELTA, "too late");
        for (AgentRunAttemptStatus nonRunningStatus :
                List.of(
                        AgentRunAttemptStatus.PENDING,
                        AgentRunAttemptStatus.RESULT_READY,
                        AgentRunAttemptStatus.COMPLETED,
                        AgentRunAttemptStatus.FAILED,
                        AgentRunAttemptStatus.ABORTED,
                        AgentRunAttemptStatus.CANCELLED)) {
            jdbc.update(
                    "update agent_run_attempt set attempt_status = ? where id = ?",
                    nonRunningStatus.name(),
                    first.attemptId());

            assertThat(eventStore.append(newSuffix))
                    .satisfies(
                            replay -> {
                                assertThat(replay.inserted()).isFalse();
                                assertThat(replay.durableHighWatermark()).isEqualTo(3);
                            });
            assertThat(attemptProgress(first.attemptId())).isEqualTo(terminalProgress);
            assertThatThrownBy(() -> eventStore.append(lateVisibleDelta))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("require a RUNNING attempt")
                    .hasMessageContaining(nonRunningStatus.name());
            assertThat(eventStore.durableHighWatermark(logical.agentRunId(), first.attemptId()))
                    .isEqualTo(3);
            assertThat(attemptProgress(first.attemptId())).isEqualTo(terminalProgress);
        }
        assertThatThrownBy(() -> eventStore.append(event(
                        first.attemptId(),
                        3,
                        StreamEventType.VISIBLE_DELTA,
                        "conflicting terminal replay")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload hash");
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                          from agent_run_stream_event
                         where agent_run_id = ?
                           and agent_run_attempt_id = ?
                           and sequence_no = 4
                           and stream_protocol = 'agent-stream.v2'
                        """,
                        Long.class,
                        logical.agentRunId(),
                        first.attemptId()))
                .isZero();
        jdbc.update(
                "update agent_run_attempt set attempt_status = 'FAILED' where id = ?",
                first.attemptId());
        assertThatThrownBy(() -> eventStore.appendOrLoadReconciledFinal(
                        new AgentRunReconciledFinalStore.Request(
                                logical.agentRunId(),
                                first.attemptId(),
                                Audience.USER,
                                "urn:after-sale-flow:graph-result:" + "d".repeat(64),
                                "d".repeat(64))))
                .isInstanceOf(AgentRunReconciledFinalStore.ConflictException.class)
                .hasMessageContaining("terminal attempt status FAILED");
        AgentRunLedger.Attempt second =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_STREAM_2"),
                        AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(1));
        assertThat(second.previousAttemptId()).isEqualTo(first.attemptId());
        assertThat(second.resetRequired()).isTrue();
        assertThat(second.publicSequenceOffset()).isEqualTo(1);
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
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(1, true, false));
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
                .hasSize(4)
                .allMatch(event -> event.attemptId().equals(first.attemptId()));

        AgentRunReconciledFinalStore.Request finalRequest =
                new AgentRunReconciledFinalStore.Request(
                        logical.agentRunId(),
                        second.attemptId(),
                        Audience.USER,
                        "urn:after-sale-flow:graph-result:" + "f".repeat(64),
                        "f".repeat(64));
        AtomicReference<AgentRunReconciledFinalStore.Receipt> insertedFinalReference =
                new AtomicReference<>();
        outerTransaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "update fulfillment_dispute_case set title = ? where id = ?",
                            "reconciled final outer transaction must roll back",
                            AgentRunPersistenceFixtures.CASE_ID);
                    insertedFinalReference.set(
                            eventStore.appendOrLoadReconciledFinal(finalRequest));
                    status.setRollbackOnly();
                });
        AgentRunReconciledFinalStore.Receipt insertedFinal = insertedFinalReference.get();
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(2, true, true));
        jdbc.update(
                """
                update agent_run_attempt
                   set last_sequence_no = 0,
                       public_output_emitted = false,
                       final_frame_observed = false
                 where id = ?
                """,
                second.attemptId());
        AgentRunReconciledFinalStore.Receipt cachedFinal =
                eventStore.appendOrLoadReconciledFinal(finalRequest);

        assertThat(insertedFinal).isNotNull();
        assertThat(insertedFinal.inserted()).isTrue();
        assertThat(insertedFinal.durableHighWatermark()).isEqualTo(2);
        assertThat(insertedFinal.publicOutputEmitted()).isTrue();
        assertThat(cachedFinal.inserted()).isFalse();
        assertThat(cachedFinal.finalEvent()).isEqualTo(insertedFinal.finalEvent());
        assertThat(cachedFinal.finalEvent().occurredAt())
                .isEqualTo(insertedFinal.finalEvent().occurredAt());
        assertThat(cachedFinal.durableHighWatermark()).isEqualTo(2);
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(2, true, true));
        assertThat(
                        jdbc.queryForObject(
                                "select title from fulfillment_dispute_case where id = ?",
                                String.class,
                                AgentRunPersistenceFixtures.CASE_ID))
                .isEqualTo(committedTitle);
        assertThatThrownBy(() -> eventStore.appendOrLoadReconciledFinal(
                        new AgentRunReconciledFinalStore.Request(
                                logical.agentRunId(),
                                second.attemptId(),
                                Audience.USER,
                                "urn:after-sale-flow:graph-result:" + "e".repeat(64),
                                "e".repeat(64))))
                .isInstanceOf(AgentRunReconciledFinalStore.ConflictException.class)
                .hasMessageContaining("differs");
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::eventType, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_STARTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 1L),
                        org.assertj.core.groups.Tuple.tuple(StreamEventType.FINAL, 2L));
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100).getLast())
                .isEqualTo(insertedFinal.finalEvent());
        assertThatThrownBy(() -> eventStore.append(event(
                        second.attemptId(),
                        3,
                        StreamEventType.VISIBLE_DELTA,
                        "late")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    private AttemptProgress attemptProgress(String attemptId) {
        return jdbc.queryForObject(
                """
                select last_sequence_no, public_output_emitted, final_frame_observed
                  from agent_run_attempt
                 where id = ?
                """,
                (resultSet, rowNumber) -> new AttemptProgress(
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getBoolean("public_output_emitted"),
                        resultSet.getBoolean("final_frame_observed")),
                attemptId);
    }

    private record AttemptProgress(
            long lastSequenceNo,
            boolean publicOutputEmitted,
            boolean finalFrameObserved) {}

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
