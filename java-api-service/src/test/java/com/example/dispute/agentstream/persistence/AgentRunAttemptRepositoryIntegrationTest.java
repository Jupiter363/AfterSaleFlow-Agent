package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Import({JpaAgentRunLedger.class, AgentRunAttemptRepositoryIntegrationTest.JsonTestConfig.class})
class AgentRunAttemptRepositoryIntegrationTest {

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
    @Autowired private AgentRunAttemptRepository attemptRepository;
    @Autowired private AgentRunRepository runRepository;

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

        AgentRunLedger.LogicalRun logical = ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun());
        AgentRunLedger.LogicalRun replayedLogical =
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun());
        assertThat(replayedLogical.agentRunId()).isEqualTo(logical.agentRunId());

        var firstAllocation = AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1");
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

        var firstRequest = AgentRunPersistenceFixtures.request(1, first.attemptId());
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
                        AgentRunPersistenceFixtures.request(
                                2, second.attemptId(), first.attemptId(), false)))
                .isEqualTo(second);
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

    @TestConfiguration
    static class JsonTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
