package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    JpaAgentRunLedger.class,
    PostgresAgentRunV2EventStore.class,
    AgentRunAttemptRepositoryIntegrationTest.JsonTestConfig.class
})
class AgentRunAttemptRepositoryIntegrationTest {

    private static final AgentPlatformContractCodec CONTRACT_CODEC =
            new AgentPlatformContractCodec(
                    Path.of("..", "contracts", "agent-platform", "v1"));

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "agent_run_attempt")
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
                                + ':'
                                + POSTGRESQL.getMappedPort(5432)
                                + "/agent_run_attempt");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRunLedger ledger;
    @Autowired private PostgresAgentRunV2EventStore eventStore;
    @Autowired private AgentRunAttemptRepository attemptRepository;
    @Autowired private AgentRunRepository runRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void allocatesAttemptsUnderTheLogicalRunLockAndReplaysTheSameRequest() {
        insertCase();

        AgentRunEntity legacyV1 =
                AgentRunEntity.streamingPending(
                        "RUN_V1_PERSISTENCE",
                        AgentRunPersistenceFixtures.CASE_ID,
                        "ROOM_V1_PERSISTENCE",
                        "EVIDENCE_ANALYZE",
                        "/internal/agents/evidence/analyze-stream",
                        "USER",
                        "{}",
                        AgentRunPersistenceFixtures.REQUEST_HASH,
                        "[\"USER\"]",
                        "[\"user-persistence\"]",
                        "legacy-stream-key",
                        "trace-v1-persistence",
                        "request-v1-persistence",
                        "user-persistence");
        runRepository.saveAndFlush(legacyV1);
        assertThat(legacyV1.getLogicalIdempotencyKey()).isEqualTo("legacy-stream-key");
        assertThat(attemptRepository.findById("RUN_V1_PERSISTENCE"))
                .hasValueSatisfying(
                        persisted -> {
                            assertThat(persisted.getAgentRunId()).isEqualTo("RUN_V1_PERSISTENCE");
                            assertThat(persisted.getAttemptNo()).isEqualTo(1);
                        });

        AgentRunLedger.LogicalRun logical = ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRunV3());
        AgentRunLedger.LogicalRun replayedLogical =
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRunV3());
        assertThat(replayedLogical.agentRunId()).isEqualTo(logical.agentRunId());

        var firstAllocation = AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1");
        TransactionTemplate allocationTransaction =
                new TransactionTemplate(transactionManager);
        allocationTransaction.executeWithoutResult(status -> {
            ledger.startNextAttempt(
                    logical.agentRunId(),
                    firstAllocation,
                    AgentRunPersistenceFixtures.STARTED_AT);
            assertThat(attemptCount(logical.agentRunId())).isEqualTo(1);
            assertThat(preludeCount(logical.agentRunId(), "ATTEMPT_V2_1")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(attemptCount(logical.agentRunId())).isZero();
        assertThat(preludeCount(logical.agentRunId(), "ATTEMPT_V2_1")).isZero();

        AgentRunLedger.Attempt first =
                ledger.startNextAttempt(logical.agentRunId(), firstAllocation, AgentRunPersistenceFixtures.STARTED_AT);
        AgentRunLedger.Attempt replayed =
                ledger.startNextAttempt(logical.agentRunId(), firstAllocation, AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(1));
        assertThat(replayed.attemptId()).isEqualTo(first.attemptId());
        assertThat(replayed.attemptNo()).isEqualTo(1);
        assertThat(first.lineageSchemaVersion())
                .isEqualTo(AgentRunLedger.ATTEMPT_LINEAGE_SCHEMA_VERSION);
        assertThat(first.commandRequestHash()).isEqualTo(firstAllocation.binding().commandRequestHash());
        assertThat(first.logicalInputHash()).isEqualTo(logical.logicalInputHash());
        assertThat(first.previousAttemptId()).isNull();
        assertThat(first.resetRequired()).isFalse();
        assertThat(first.publicSequenceOffset()).isZero();
        assertThat(first.lastSequenceNo()).isZero();
        AgentStreamEvent firstPrelude = prelude(logical.agentRunId(), first.attemptId(), 0);
        assertThat(firstPrelude.eventType()).isEqualTo(StreamEventType.ATTEMPT_STARTED);
        assertThat(firstPrelude.occurredAt()).isEqualTo(AgentRunPersistenceFixtures.STARTED_AT);
        assertThat(firstPrelude.payload().node()).isEqualTo("evidence.graph");

        var firstRequest = AgentRunPersistenceFixtures.requestV3(1, first.attemptId());
        Instant nanosecondClock =
                AgentRunPersistenceFixtures.COMPLETED_AT.plusNanos(789);
        AgentRunExecutionGateway failingGateway =
                (request, executionMode, progressListener, cancellationToken) -> {
                    progressListener.onProgress(new AgentRunProgress(2, true, false));
                    throw AgentRunExecutionException.createNextAttempt(
                            "PROVIDER_TIMEOUT",
                            "provider timed out",
                            2,
                            true,
                            null);
                };
        ExecuteAgentRunActivityImpl activity = new ExecuteAgentRunActivityImpl(
                ledger,
                failingGateway,
                () -> new AgentRunActivityContext() {
                    @Override
                    public int temporalAttempt() {
                        return 1;
                    }

                    @Override
                    public void heartbeat(AgentRunAttemptHeartbeat details) {}
                },
                Clock.fixed(nanosecondClock, ZoneOffset.UTC),
                Duration.ofHours(1),
                Executors::newSingleThreadScheduledExecutor);

        ExecuteAgentRunResult durableFailure = activity.execute(firstRequest);

        assertThat(durableFailure.completedAt())
                .isEqualTo(nanosecondClock.truncatedTo(ChronoUnit.MICROS));
        assertThat(durableFailure.completedAt()).isNotEqualTo(nanosecondClock);
        assertThat(durableFailure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        assertThat(ledger.requireAllocatedAttempt(
                                firstRequest)
                        .durableFailureResult())
                .isEqualTo(durableFailure);

        assertThatThrownBy(() -> ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.allocationWithRetryBudget(
                                2, "ATTEMPT_V2_BUDGET_INCREASE", 3, 2, 1),
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget cannot increase");

        var secondAllocation = AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_V2_2");
        AgentRunLedger.Attempt second =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        secondAllocation,
                        AgentRunPersistenceFixtures.COMPLETED_AT);
        assertThat(second.previousAttemptId()).isEqualTo(first.attemptId());
        assertThat(second.logicalInputHash()).isEqualTo(first.logicalInputHash());
        assertThat(second.commandRequestHash()).isNotEqualTo(first.commandRequestHash());
        assertThat(second.resetRequired()).isFalse();
        assertThat(second.publicSequenceOffset()).isZero();
        assertThat(ledger.requireAllocatedAttempt(
                        AgentRunPersistenceFixtures.requestV3(
                                2, second.attemptId(), first.attemptId(), false)))
                .isEqualTo(second);
        assertThat(second.lastSequenceNo()).isZero();
        assertThat(prelude(logical.agentRunId(), second.attemptId(), 0).eventType())
                .isEqualTo(StreamEventType.ATTEMPT_STARTED);
        assertThat(preludeCount(logical.agentRunId(), second.attemptId())).isEqualTo(1);

        TransactionTemplate corruptDispatch = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> corruptDispatch.executeWithoutResult(status -> {
                    jdbc.update(
                            """
                            update agent_run_stream_event
                               set payload_hash = ?
                             where agent_run_id = ?
                               and agent_run_attempt_id = ?
                               and sequence_no = 0
                               and stream_protocol = 'agent-stream.v3'
                            """,
                            "f".repeat(64),
                            logical.agentRunId(),
                            first.attemptId());
                    ledger.requireAllocatedAttempt(firstRequest);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publicPreludeStoredHash");

        TransactionTemplate corruptReplay = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> corruptReplay.executeWithoutResult(status -> {
                    jdbc.update(
                            """
                            update agent_run_stream_event
                               set payload_hash = ?
                             where agent_run_id = ?
                               and agent_run_attempt_id = ?
                               and sequence_no = 0
                               and stream_protocol = 'agent-stream.v3'
                            """,
                            "e".repeat(64),
                            logical.agentRunId(),
                            first.attemptId());
                    ledger.startNextAttempt(
                            logical.agentRunId(),
                            firstAllocation,
                            AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(30));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publicPreludeStoredHash");

        ledger.recordResultReady(AgentRunPersistenceFixtures.result(2, second.attemptId()));
        ledger.recordResultReady(AgentRunPersistenceFixtures.result(2, second.attemptId()));

        assertThat(attemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(logical.agentRunId()))
                .extracting("attemptNo")
                .containsExactly(1L, 2L);
        assertThat(attemptRepository.findById(second.attemptId()))
                .hasValueSatisfying(
                        persisted -> {
                            assertThat(persisted.getAttemptStatus())
                                    .isEqualTo(AgentRunAttemptStatus.RESULT_READY);
                            assertThat(persisted.getResultHash())
                                    .isEqualTo(AgentRunPersistenceFixtures.RESULT_HASH);
                            assertThat(persisted.getTotalTokens()).isEqualTo(120);
                        });
        assertThat(ledger.committedReceipt(logical.agentRunId())).isEmpty();

        assertThatThrownBy(
                        () ->
                                ledger.startNextAttempt(
                                        logical.agentRunId(),
                                        AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_V2_CONFLICT"),
                                        AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attemptId");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void adoptsMatchingDurableGraphErrorAndTerminalizesTheAttemptInPostgres() {
        jdbc.update(
                "delete from agent_run where case_id = ?",
                AgentRunPersistenceFixtures.CASE_ID);
        jdbc.update(
                "delete from fulfillment_dispute_case where id = ?",
                AgentRunPersistenceFixtures.CASE_ID);
        try {
            insertCase();
            String attemptId = "ATTEMPT_V3_SCHEMA_FAILURE";
            AgentRunLedger.LogicalRun logical =
                    ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRunV3(attemptId));
            AgentRunLedger.Attempt attempt = ledger.startNextAttempt(
                    logical.agentRunId(),
                    AgentRunPersistenceFixtures.allocation(1, attemptId),
                    AgentRunPersistenceFixtures.STARTED_AT);
            Instant visibleAt = AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(1);
            Instant errorAt = visibleAt.plusSeconds(1);
            eventStore.append(new AgentStreamEvent(
                    "agent-stream.v3",
                    logical.agentRunId(),
                    attempt.attemptId(),
                    1,
                    StreamEventType.VISIBLE_DELTA,
                    Audience.USER,
                    visibleAt,
                    new AgentStreamEvent.Payload(
                            "evidence.graph",
                            "frames",
                            "可见内容",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)));
            eventStore.append(new AgentStreamEvent(
                    "agent-stream.v3",
                    logical.agentRunId(),
                    attempt.attemptId(),
                    2,
                    StreamEventType.ERROR,
                    Audience.USER,
                    errorAt,
                    new AgentStreamEvent.Payload(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "AGENT_OUTPUT_SCHEMA_INVALID",
                            false)));
            ExecuteAgentRunResult source = new ExecuteAgentRunResult(
                    ExecuteAgentRunResult.SCHEMA_VERSION,
                    logical.agentRunId(),
                    logical.agentRunId(),
                    attempt.attemptId(),
                    attempt.attemptNo(),
                    ExecuteAgentRunResult.Outcome.FAILED,
                    null,
                    null,
                    2,
                    true,
                    "AGENT_OUTPUT_SCHEMA_INVALID",
                    false,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    errorAt.plusSeconds(1));

            ExecuteAgentRunResult durable = ledger.recordAttemptFailureResult(
                    AgentRunAttemptStatus.ABORTED, source);

            assertThat(durable).isEqualTo(source);
            assertThat(jdbc.queryForMap(
                            """
                            select run_status, finalization_status, error_code,
                                   error_retryable, completed_at
                              from agent_run
                             where id = ?
                            """,
                            logical.agentRunId()))
                    .satisfies(state -> {
                        assertThat(state.get("run_status")).isEqualTo("ABORTED");
                        assertThat(state.get("finalization_status"))
                                .isEqualTo("UNCOMMITTED");
                        assertThat(state.get("error_code"))
                                .isEqualTo("AGENT_OUTPUT_SCHEMA_INVALID");
                        assertThat(state.get("error_retryable")).isEqualTo(false);
                        assertThat(state.get("completed_at")).isNotNull();
                    });
            assertThat(attemptRepository.findById(attempt.attemptId()))
                    .hasValueSatisfying(persisted -> {
                        assertThat(persisted.getAttemptStatus())
                                .isEqualTo(AgentRunAttemptStatus.ABORTED);
                        assertThat(persisted.getLastSequenceNo()).isEqualTo(2);
                        assertThat(persisted.isPublicOutputEmitted()).isTrue();
                        assertThat(persisted.getErrorCode())
                                .isEqualTo("AGENT_OUTPUT_SCHEMA_INVALID");
                        assertThat(persisted.getResultJson()).isNotNull();
                    });
        } finally {
            jdbc.update(
                    "delete from agent_run where case_id = ?",
                    AgentRunPersistenceFixtures.CASE_ID);
            jdbc.update(
                    "delete from fulfillment_dispute_case where id = ?",
                    AgentRunPersistenceFixtures.CASE_ID);
        }
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
                          'AgentRun persistence', 'AgentRun persistence fixture',
                          'EVIDENCE', 'test', 'test')
                """,
                AgentRunPersistenceFixtures.CASE_ID,
                "idem-" + AgentRunPersistenceFixtures.CASE_ID);
    }

    private long attemptCount(String runId) {
        return jdbc.queryForObject(
                "select count(*) from agent_run_attempt where agent_run_id = ?",
                Long.class,
                runId);
    }

    private long preludeCount(String runId, String attemptId) {
        return jdbc.queryForObject(
                """
                select count(*)
                  from agent_run_stream_event
                 where agent_run_id = ?
                   and agent_run_attempt_id = ?
                   and stream_protocol = 'agent-stream.v3'
                   and event_type in ('attempt_started', 'attempt_reset')
                """,
                Long.class,
                runId,
                attemptId);
    }

    private AgentStreamEvent prelude(String runId, String attemptId, long sequenceNo) {
        return jdbc.queryForObject(
                """
                select payload_json::text, payload_hash
                  from agent_run_stream_event
                 where agent_run_id = ?
                   and agent_run_attempt_id = ?
                   and stream_protocol = 'agent-stream.v3'
                   and sequence_no = ?
                """,
                (resultSet, rowNumber) -> {
                    try {
                        String encoded = resultSet.getString("payload_json");
                        var json = objectMapper.readTree(encoded);
                        assertThat(resultSet.getString("payload_hash"))
                                .isEqualTo(ContractJson.sha256Hex(json));
                        return CONTRACT_CODEC.decode(
                                "agent-stream-event.schema.json", json, AgentStreamEvent.class);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                        throw new IllegalStateException(
                                "persisted prelude fixture cannot be decoded", exception);
                    }
                },
                runId,
                attemptId,
                sequenceNo);
    }

    @TestConfiguration
    static class JsonTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
