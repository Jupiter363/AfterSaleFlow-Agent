package com.example.dispute.agentstream.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JpaAgentRunLedgerProtocolTest {

    @Test
    void createsAndReplaysOnlyTheCurrentV3AndParallelV4LogicalRunProtocols() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attempts = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository events = mock(AgentRunStreamEventRepository.class);
        PostgresAgentRunV2EventStore eventStore = mock(PostgresAgentRunV2EventStore.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query advisoryLock = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(advisoryLock);
        when(advisoryLock.setParameter(anyString(), any())).thenReturn(advisoryLock);
        when(advisoryLock.getSingleResult()).thenReturn(1L);

        AtomicReference<AgentRunEntity> persisted = new AtomicReference<>();
        when(runs.findByCaseIdAndLogicalIdempotencyKey(anyString(), anyString()))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(runs.existsById(anyString())).thenReturn(false);
        when(runs.saveAndFlush(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> {
                    AgentRunEntity entity = invocation.getArgument(0);
                    persisted.set(entity);
                    return entity;
                });
        JpaAgentRunLedger ledger = new JpaAgentRunLedger(
                runs,
                attempts,
                events,
                eventStore,
                mock(PostgresAgentRunV4EventWriter.class),
                entityManager,
                new ObjectMapper());
        CreateLogicalRun current = AgentRunPersistenceFixtures.logicalRunV3();

        var created = ledger.createOrLoad(current);
        var replay = ledger.createOrLoad(current);

        assertThat(created).isEqualTo(replay);
        assertThat(created.protocol()).isEqualTo(AgentRunProtocol.V3);
        assertThat(persisted.get().getProtocol()).isEqualTo(AgentRunProtocol.V3.wireValue());
        assertThat(persisted.get().getTraceId()).startsWith("agent-run-v3:");
        persisted.set(null);
        CreateLogicalRun parallel = AgentRunPersistenceFixtures.logicalRunV4();
        var createdParallel = ledger.createOrLoad(parallel);
        var replayedParallel = ledger.createOrLoad(parallel);

        assertThat(createdParallel).isEqualTo(replayedParallel);
        assertThat(createdParallel.protocol()).isEqualTo(AgentRunProtocol.V4);
        assertThat(persisted.get().getProtocol()).isEqualTo(AgentRunProtocol.V4.wireValue());
        assertThat(persisted.get().getTraceId()).startsWith("agent-run-v4:");
        verify(runs, times(2)).saveAndFlush(any(AgentRunEntity.class));

        assertThatThrownBy(() -> ledger.createOrLoad(withProtocol(current, AgentRunProtocol.V2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V3 or V4");
        assertThatThrownBy(() -> ledger.createOrLoad(withProtocol(current, AgentRunProtocol.V1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V3 or V4");
        assertThatThrownBy(() -> AgentRunEntity.logicalV3(
                        withProtocol(current, AgentRunProtocol.V2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact protocol");
    }

    @Test
    void terminalizesAndReplaysAnEmptyV4FailureAtSequenceZero() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attempts = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository events = mock(AgentRunStreamEventRepository.class);
        PostgresAgentRunV2EventStore v3Events = mock(PostgresAgentRunV2EventStore.class);
        PostgresAgentRunV4EventWriter v4Events = mock(PostgresAgentRunV4EventWriter.class);
        EntityManager entityManager = mock(EntityManager.class);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        JpaAgentRunLedger ledger = new JpaAgentRunLedger(
                runs,
                attempts,
                events,
                v3Events,
                v4Events,
                entityManager,
                mapper);
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        AgentRunEntity run = AgentRunEntity.logicalV4(
                AgentRunPersistenceFixtures.logicalRunV4());
        run.bindV4Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        run.markV4AttemptStarted();
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.startV4(
                request.agentRunId(),
                AgentRunPersistenceFixtures.parallelIntakeAllocation(),
                AgentRunPersistenceFixtures.STARTED_AT);
        when(runs.findByIdForUpdate(request.agentRunId())).thenReturn(Optional.of(run));
        when(attempts.findByIdForUpdate(request.attemptId())).thenReturn(Optional.of(attempt));
        when(attempts.save(any(AgentRunAttemptEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runs.saveAndFlush(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(v4Events.appendOrLoadExactTerminalInCurrentTransaction(any()))
                .thenReturn(new PostgresAgentRunV4EventWriter.TerminalWriteReceipt(
                        "a".repeat(64), true, "{}", "b".repeat(64), 0))
                .thenReturn(new PostgresAgentRunV4EventWriter.TerminalWriteReceipt(
                        "a".repeat(64), false, "{}", "b".repeat(64), 0));
        ExecuteAgentRunResult source = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                -1,
                false,
                "INTAKE_PARALLEL_ADMISSION_FAILED",
                false,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                AgentRunPersistenceFixtures.COMPLETED_AT);

        ExecuteAgentRunResult first = ledger.recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, source);
        ExecuteAgentRunResult replay = ledger.recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, source);

        assertThat(first.lastSequenceNo()).isZero();
        assertThat(replay).isEqualTo(first);
        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(attempt.getLastSequenceNo()).isZero();
        assertThat(run.getRunStatus()).isEqualTo("FAILED");
        assertThat(run.getCompletedAt().toInstant())
                .isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);
        ArgumentCaptor<PostgresAgentRunV4EventWriter.EventWriteCommand> terminal =
                ArgumentCaptor.forClass(
                        PostgresAgentRunV4EventWriter.EventWriteCommand.class);
        verify(v4Events, times(2))
                .appendOrLoadExactTerminalInCurrentTransaction(terminal.capture());
        assertThat(terminal.getAllValues()).allSatisfy(command -> {
            assertThat(command.sequenceNo()).isZero();
            assertThat(command.eventType()).isEqualTo(AgentStreamEventV4.EventType.ERROR);
            assertThat(command.payload().errorCode())
                    .isEqualTo("INTAKE_PARALLEL_ADMISSION_FAILED");
            assertThat(command.payload().retryable()).isFalse();
        });
        assertThat(terminal.getAllValues().get(0).eventId())
                .isEqualTo(terminal.getAllValues().get(1).eventId());
        verify(v3Events, times(0)).appendRecoveryErrorInCurrentTransaction(any());
    }

    private static CreateLogicalRun withProtocol(
            CreateLogicalRun source, AgentRunProtocol protocol) {
        return new CreateLogicalRun(
                source.agentRunId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomId(),
                source.operation(),
                source.logicalIdempotencyKey(),
                protocol,
                source.executorKind(),
                source.roomEpochId(),
                source.roomType(),
                source.roomEpoch(),
                source.processRevision(),
                source.fencingToken(),
                source.requestHash(),
                source.logicalInputHash(),
                source.attemptLimit(),
                source.deadlineAt(),
                source.createdAt());
    }
}
