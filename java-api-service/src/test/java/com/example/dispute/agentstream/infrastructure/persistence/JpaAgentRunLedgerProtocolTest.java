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
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
                runs, attempts, events, eventStore, entityManager, new ObjectMapper());
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
