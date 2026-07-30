package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentRunV2RecoveryTerminalEventTest {

    private static final String ERROR_CODE =
            "AGENT_RUN_RECOVERY_ATTEMPT_LIMIT_EXHAUSTED";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void terminalizesAbortedRecoveryOnceWithAHashBoundPublicError() throws Exception {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository eventRepository =
                mock(AgentRunStreamEventRepository.class);
        PostgresAgentRunV2EventStore recoveryEventStore =
                mock(PostgresAgentRunV2EventStore.class);
        EntityManager entityManager = mock(EntityManager.class);
        JpaAgentRunLedger ledger = new JpaAgentRunLedger(
                runRepository,
                attemptRepository,
                eventRepository,
                recoveryEventStore,
                entityManager,
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV2(
                AgentRunPersistenceFixtures.logicalRun());
        run.bindV2Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        run.markV2AttemptStarted();
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                run.getId(),
                AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1"),
                null,
                false,
                0,
                AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordHeartbeat(new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                run.getId(),
                attempt.getId(),
                1,
                2,
                true,
                false,
                AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(2)));
        attempt.recordFailure(
                AgentRunAttemptStatus.ABORTED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        run.markV2AttemptFailed(
                AgentRunAttemptStatus.ABORTED,
                true,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(attemptRepository.findMaxAttemptNoByAgentRunId(run.getId())).thenReturn(1L);
        when(attemptRepository.findByAgentRunIdAndAttemptNoForUpdate(run.getId(), 1L))
                .thenReturn(Optional.of(attempt));
        when(eventRepository.findMaxV2Sequence(run.getId(), attempt.getId()))
                .thenReturn(2L);
        when(eventRepository.findV2Event(run.getId(), attempt.getId(), 2L))
                .thenReturn(Optional.of(event(
                        run.getId(),
                        attempt.getId(),
                        2L,
                        StreamEventType.ATTEMPT_ABORTED)));
        when(recoveryEventStore.appendRecoveryErrorInCurrentTransaction(any()))
                .thenReturn(new AppendReceipt(true, 3));

        ledger.terminalizeV2RecoveryCandidate(
                run.getId(),
                attempt.getId(),
                1,
                ERROR_CODE,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        ledger.terminalizeV2RecoveryCandidate(
                run.getId(),
                attempt.getId(),
                1,
                ERROR_CODE,
                AgentRunPersistenceFixtures.COMPLETED_AT);

        assertThat(run.getRunStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorCode()).isEqualTo(ERROR_CODE);
        assertThat(run.getErrorRetryable()).isFalse();
        assertThat(run.getCompletedAt().toInstant())
                .isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);
        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(attempt.getLastSequenceNo()).isEqualTo(3L);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempt.getErrorRetryable()).isTrue();
        assertThat(attempt.getTerminationCode())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name());
        assertThat(attempt.getCompletedAt().toInstant())
                .isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);

        ArgumentCaptor<AgentStreamEvent> eventCaptor =
                ArgumentCaptor.forClass(AgentStreamEvent.class);
        verify(recoveryEventStore, times(1))
                .appendRecoveryErrorInCurrentTransaction(eventCaptor.capture());
        AgentStreamEvent event = eventCaptor.getValue();
        assertThat(event.runId()).isEqualTo(run.getId());
        assertThat(event.attemptId()).isEqualTo(attempt.getId());
        assertThat(event.sequenceNo()).isEqualTo(3L);
        assertThat(event.eventType()).isEqualTo(StreamEventType.ERROR);
        assertThat(event.audience()).isEqualTo(Audience.USER);
        assertThat(event.occurredAt()).isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);
        assertThat(event.payload().errorCode()).isEqualTo(ERROR_CODE);
        assertThat(event.payload().retryable()).isFalse();
        String canonical = ContractJson.canonicalString(MAPPER.valueToTree(event));
        assertThat(canonical)
                .doesNotContain("AgentRun V2 recovery candidate cannot continue");
        assertThat(ContractJson.sha256Hex(MAPPER.readTree(canonical))).hasSize(64);
        verify(eventRepository, never()).saveAndFlush(any());
        InOrder recoveryOrder = inOrder(entityManager, recoveryEventStore);
        recoveryOrder.verify(entityManager).flush();
        recoveryOrder.verify(recoveryEventStore).appendRecoveryErrorInCurrentTransaction(event);
        verify(eventRepository, times(1))
                .findMaxV2Sequence(run.getId(), attempt.getId());
    }

    @Test
    void rejectsACancelledPredecessorWithoutADurableAttemptAbortedFrame() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository eventRepository =
                mock(AgentRunStreamEventRepository.class);
        PostgresAgentRunV2EventStore recoveryEventStore =
                mock(PostgresAgentRunV2EventStore.class);
        JpaAgentRunLedger ledger = new JpaAgentRunLedger(
                runRepository,
                attemptRepository,
                eventRepository,
                recoveryEventStore,
                mock(EntityManager.class),
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV2(
                AgentRunPersistenceFixtures.logicalRun());
        run.bindV2Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                run.getId(),
                AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1"),
                null,
                false,
                0,
                AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordFailure(
                AgentRunAttemptStatus.CANCELLED,
                "ACTIVITY_CANCELLED",
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(attemptRepository.findMaxAttemptNoByAgentRunId(run.getId())).thenReturn(1L);
        when(attemptRepository.findByAgentRunIdAndAttemptNoForUpdate(run.getId(), 1L))
                .thenReturn(Optional.of(attempt));
        when(eventRepository.findMaxV2Sequence(run.getId(), attempt.getId()))
                .thenReturn(0L);
        when(eventRepository.findV2Event(run.getId(), attempt.getId(), 0L))
                .thenReturn(Optional.of(event(
                        run.getId(),
                        attempt.getId(),
                        0L,
                        StreamEventType.ATTEMPT_STARTED)));

        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must end with attempt_aborted");

        assertThat(run.getRunStatus()).isEqualTo("PENDING");
        assertThat(run.getErrorCode()).isNull();
        verify(eventRepository, never()).saveAndFlush(any());
        verify(recoveryEventStore, never()).appendRecoveryErrorInCurrentTransaction(any());
    }

    @Test
    void rejectsAnExistingGlobalTerminalInsteadOfAppendingASecondOne() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository eventRepository =
                mock(AgentRunStreamEventRepository.class);
        PostgresAgentRunV2EventStore recoveryEventStore =
                mock(PostgresAgentRunV2EventStore.class);
        JpaAgentRunLedger ledger = new JpaAgentRunLedger(
                runRepository,
                attemptRepository,
                eventRepository,
                recoveryEventStore,
                mock(EntityManager.class),
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV2(
                AgentRunPersistenceFixtures.logicalRun());
        run.bindV2Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                run.getId(),
                AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1"),
                null,
                false,
                0,
                AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordHeartbeat(new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                run.getId(),
                attempt.getId(),
                1,
                1,
                false,
                false,
                AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(2)));
        attempt.recordFailure(
                AgentRunAttemptStatus.ABORTED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(attemptRepository.findMaxAttemptNoByAgentRunId(run.getId())).thenReturn(1L);
        when(attemptRepository.findByAgentRunIdAndAttemptNoForUpdate(run.getId(), 1L))
                .thenReturn(Optional.of(attempt));
        when(eventRepository.findMaxV2Sequence(run.getId(), attempt.getId()))
                .thenReturn(1L);
        when(eventRepository.findV2Event(run.getId(), attempt.getId(), 1L))
                .thenReturn(Optional.of(event(
                        run.getId(),
                        attempt.getId(),
                        1L,
                        StreamEventType.ERROR)));

        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has a global terminal");

        assertThat(run.getRunStatus()).isEqualTo("PENDING");
        assertThat(run.getErrorCode()).isNull();
        verify(eventRepository, never()).saveAndFlush(any());
        verify(recoveryEventStore, never()).appendRecoveryErrorInCurrentTransaction(any());
    }

    private static AgentRunStreamEventEntity event(
            String runId,
            String attemptId,
            long sequenceNo,
            StreamEventType eventType) {
        AgentStreamEvent.Payload payload = switch (eventType) {
            case ATTEMPT_ABORTED -> new AgentStreamEvent.Payload(
                    null, null, null, null, "PROVIDER_TIMEOUT", null,
                    null, null, null, null);
            case ERROR -> new AgentStreamEvent.Payload(
                    null, null, null, null, null, null,
                    null, null, "PREEXISTING_GLOBAL_ERROR", false);
            default -> new AgentStreamEvent.Payload(
                    null, null, null, null, null, null,
                    null, null, null, null);
        };
        AgentStreamEvent event = new AgentStreamEvent(
                "agent-stream.v2",
                runId,
                attemptId,
                sequenceNo,
                eventType,
                Audience.USER,
                AgentRunPersistenceFixtures.COMPLETED_AT,
                payload);
        var json = MAPPER.valueToTree(event);
        return AgentRunStreamEventEntity.createV2Prelude(
                "EVENT_" + sequenceNo,
                runId,
                attemptId,
                sequenceNo,
                eventType.wireValue(),
                Audience.USER,
                ContractJson.canonicalString(json),
                ContractJson.sha256Hex(json),
                event.occurredAt());
    }
}
