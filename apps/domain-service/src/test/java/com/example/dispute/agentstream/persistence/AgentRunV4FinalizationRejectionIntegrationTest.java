package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder.Command;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAgentRunLedger.class, PostgresAgentRunV4EventWriter.class,
        AgentRunV4FinalizationRejectionIntegrationTest.Config.class})
@Testcontainers
class AgentRunV4FinalizationRejectionIntegrationTest {
    @Container
    static final GenericContainer<?> PG = new GenericContainer<>(DockerImageName.parse(
            "public.ecr.aws/docker/library/postgres@sha256:e013e867e712fec275706a6c51c966f0bb0c93cfa8f51000f85a15f9865a28cb"))
            .withEnv("POSTGRES_USER", "v4_test").withEnv("POSTGRES_PASSWORD", "isolated_test_password")
            .withEnv("POSTGRES_DB", "v4_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/v4_test");
        r.add("spring.datasource.username", () -> "v4_test");
        r.add("spring.datasource.password", () -> "isolated_test_password");
    }
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean PostgresAgentRunV2EventStore v3() { return org.mockito.Mockito.mock(PostgresAgentRunV2EventStore.class); }
    }
    @Autowired AgentRunLedger ledger;
    @Autowired AgentRunRepository runs;
    @Autowired AgentRunAttemptRepository attempts;
    @Autowired PostgresAgentRunV4EventWriter writer;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectionRollbackThenCommitAndReplayKeepBothLedgersAndDeliveryAtomic() {
        var tx = new TransactionTemplate(manager);
        Command command = tx.execute(status -> ready());
        assertThat(command).isNotNull();
        tx.executeWithoutResult(status -> {
            assertThat(ledger.recordFinalizationFailure(command).replayed()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select run_status from agent_run where id=?", String.class, command.agentRunId()))
                .isEqualTo("RESULT_READY");
        assertThat(jdbc.queryForObject("select count(*) from agent_run_stream_event where agent_run_id=?", Long.class, command.agentRunId()))
                .isEqualTo(1);
        var inserted = ledger.recordFinalizationFailure(command);
        var replay = ledger.recordFinalizationFailure(command);
        assertThat(inserted.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForList("select event_type from agent_run_stream_event where agent_run_id=? order by sequence_no", String.class, command.agentRunId()))
                .containsExactly("final", "error");
        assertThat(jdbc.queryForObject("select run_status || '/' || finalization_status from agent_run where id=?", String.class, command.agentRunId()))
                .isEqualTo("ABORTED/UNCOMMITTED");
        assertThat(jdbc.queryForObject("select last_sequence_no from agent_run_attempt where id=?", Long.class, command.attemptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select highest_contiguous_sequence_no from agent_run_stream_delivery_high_watermark where agent_run_id=? and agent_run_attempt_id=? and stream_protocol='agent-stream.v4'", Long.class, command.agentRunId(), command.attemptId())).isEqualTo(1);
        var changed = new Command(command.agentRunId(), command.logicalRunId(), command.attemptId(), 1,
                command.commandId(), command.commandRequestHash(), command.resultHash(), 0, true, "CHANGED_REJECTION");
        assertThatThrownBy(() -> ledger.recordFinalizationFailure(changed)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.recordFinalizationFailure(command).replayed()).isTrue();
    }

    private Command ready() {
        var request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        var result = AgentRunPersistenceFixtures.parallelIntakeResult(0);
        jdbc.update("""
                insert into fulfillment_dispute_case (
                  id,user_id,merchant_id,creation_idempotency_key,case_type,case_status,initiator_role,initiator_id,
                  respondent_role,respondent_id,risk_level,title,description,current_room,created_by,updated_by)
                values (?,'user-persistence','merchant-persistence','v4-final-test','DISPUTE','EVIDENCE_OPEN',
                  'USER','user-persistence','MERCHANT','merchant-persistence','MEDIUM','V4 test','V4 test','EVIDENCE','test','test')
                """, request.command().caseId());
        var run = AgentRunEntity.logicalV4(AgentRunPersistenceFixtures.logicalRunV4());
        run.bindV4Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        run.markV4AttemptStarted();
        runs.saveAndFlush(run);
        var attempt = AgentRunAttemptEntity.startV4(request.agentRunId(), AgentRunPersistenceFixtures.parallelIntakeAllocation(), AgentRunPersistenceFixtures.STARTED_AT);
        attempts.saveAndFlush(attempt);
        writer.appendOrLoadExactTerminalInCurrentTransaction(new PostgresAgentRunV4EventWriter.EventWriteCommand(
                "v4-final-test", run.getId(), attempt.getId(), 0, AgentStreamEventV4.EventType.FINAL,
                Audience.USER, result.completedAt(), AgentStreamEventV4.Payload.finalPayload("hidden-final", result.resultHash()),
                "user-persistence", "[\"user-persistence\"]"));
        try { attempt.recordV4ResultReady(result, mapper.writeValueAsString(result), -1); }
        catch (Exception e) { throw new AssertionError(e); }
        run.markV4ResultReady(attempt.getId(), result.resultHash(), result.completedAt());
        attempts.saveAndFlush(attempt);
        runs.saveAndFlush(run);
        return new Command(run.getId(), run.getId(), attempt.getId(), 1, request.command().commandId(),
                request.command().requestHash(), result.resultHash(), 0, true, "AGENT_RUN_FINALIZATION_REJECTED");
    }
}
