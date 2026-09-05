package com.example.dispute.agentstream.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder.Command;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Real entity transitions and strict predecessor parsing; persistence collaborators are mocked. */
class JpaAgentRunV4FinalizationFailureTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void v4RejectionAppendsOneAdjacentV4ErrorAndReplaysWithoutAnotherWrite() throws Exception {
        Fixture f = new Fixture();
        when(f.v4.appendOrLoadExactTerminalInCurrentTransaction(any()))
                .thenReturn(receipt(true)).thenReturn(receipt(false));
        var inserted = f.ledger.recordFinalizationFailure(f.command);
        var replay = f.ledger.recordFinalizationFailure(f.command);
        assertThat(inserted.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(f.run.getRunStatus()).isEqualTo("ABORTED");
        assertThat(f.run.getFinalizationStatus()).isEqualTo("UNCOMMITTED");
        assertThat(f.run.getStopReason()).isEqualTo("FINALIZATION_REJECTED");
        assertThat(f.attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(f.attempt.getLastSequenceNo()).isEqualTo(1);
        assertThat(f.attempt.getResultJson()).isEqualTo(f.originalResult);
        assertThat(f.run.getFinalResultHash()).isEqualTo(f.command.resultHash());
        var capture = ArgumentCaptor.forClass(PostgresAgentRunV4EventWriter.EventWriteCommand.class);
        verify(f.v4, times(2)).appendOrLoadExactTerminalInCurrentTransaction(capture.capture());
        assertThat(capture.getAllValues().get(0)).isEqualTo(capture.getAllValues().get(1));
        assertThat(capture.getValue().sequenceNo()).isEqualTo(1);
        assertThat(capture.getValue().eventType()).isEqualTo(AgentStreamEventV4.EventType.ERROR);
        assertThat(capture.getValue().payload()).isEqualTo(
                AgentStreamEventV4.Payload.errorPayload(f.command.safeErrorCode(), false));
        verifyNoInteractions(f.v3);
        assertThatThrownBy(() -> f.run.recordV3FinalizationFailure(f.command.attemptId(),
                f.command.resultHash(), AgentRunAttemptStatus.ABORTED, f.command.safeErrorCode(),
                AgentRunPersistenceFixtures.COMPLETED_AT)).hasMessageContaining("agent-stream.v3");
    }

    @Test
    void rejectsMissingOrTamperedFinalBeforeAnyTerminalWrite() throws Exception {
        Fixture missing = new Fixture();
        when(missing.events.findV4Event(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> missing.ledger.recordFinalizationFailure(missing.command))
                .hasMessageContaining("exact hidden FINAL");
        verifyNoInteractions(missing.v4, missing.v3);
        for (String field : List.of("payloadHash", "eventType", "streamProtocol")) {
            Fixture f = new Fixture();
            ReflectionTestUtils.setField(f.finalRow, field, "tampered");
            assertThatThrownBy(() -> f.ledger.recordFinalizationFailure(f.command))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.run.getRunStatus()).isEqualTo("RESULT_READY");
            assertThat(f.attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.RESULT_READY);
            verifyNoInteractions(f.v4, f.v3);
        }
    }

    @Test
    void rejectsWrongResultCommandSequenceCommittedAndChangedReplay() throws Exception {
        List<UnaryOperator<Command>> changes = List.of(
                c -> new Command(c.agentRunId(), c.logicalRunId(), c.attemptId(), c.attemptNo(),
                        "foreign-command", c.commandRequestHash(), c.resultHash(), c.finalSequenceNo(), true, c.safeErrorCode()),
                c -> new Command(c.agentRunId(), c.logicalRunId(), c.attemptId(), c.attemptNo(),
                        c.commandId(), "b".repeat(64), c.resultHash(), c.finalSequenceNo(), true, c.safeErrorCode()),
                c -> new Command(c.agentRunId(), c.logicalRunId(), c.attemptId(), c.attemptNo(),
                        c.commandId(), c.commandRequestHash(), "c".repeat(64), c.finalSequenceNo(), true, c.safeErrorCode()),
                c -> new Command(c.agentRunId(), c.logicalRunId(), c.attemptId(), c.attemptNo(),
                        c.commandId(), c.commandRequestHash(), c.resultHash(), 3, true, c.safeErrorCode()));
        for (var change : changes) {
            Fixture f = new Fixture();
            assertThatThrownBy(() -> f.ledger.recordFinalizationFailure(change.apply(f.command)))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(f.v4, f.v3);
        }
        Fixture committed = new Fixture();
        ReflectionTestUtils.setField(committed.run, "finalizationStatus", "COMMITTED");
        assertThatThrownBy(() -> committed.ledger.recordFinalizationFailure(committed.command))
                .hasMessageContaining("committed final");
        verifyNoInteractions(committed.v4, committed.v3);
        Fixture replay = new Fixture();
        when(replay.v4.appendOrLoadExactTerminalInCurrentTransaction(any())).thenReturn(receipt(true));
        replay.ledger.recordFinalizationFailure(replay.command);
        var c = replay.command;
        assertThatThrownBy(() -> replay.ledger.recordFinalizationFailure(new Command(c.agentRunId(), c.logicalRunId(),
                c.attemptId(), c.attemptNo(), c.commandId(), c.commandRequestHash(), c.resultHash(),
                c.finalSequenceNo(), true, "DIFFERENT_FAILURE"))).hasMessageContaining("errorCode");
        verify(replay.v4, times(1)).appendOrLoadExactTerminalInCurrentTransaction(any());
    }

    private static PostgresAgentRunV4EventWriter.TerminalWriteReceipt receipt(boolean inserted) {
        return new PostgresAgentRunV4EventWriter.TerminalWriteReceipt("event", inserted, "{}", "a".repeat(64), 1);
    }

    private static final class Fixture {
        final AgentRunRepository runs = mock(AgentRunRepository.class);
        final AgentRunAttemptRepository attempts = mock(AgentRunAttemptRepository.class);
        final AgentRunStreamEventRepository events = mock(AgentRunStreamEventRepository.class);
        final PostgresAgentRunV2EventStore v3 = mock(PostgresAgentRunV2EventStore.class);
        final PostgresAgentRunV4EventWriter v4 = mock(PostgresAgentRunV4EventWriter.class);
        final AgentRunEntity run = AgentRunEntity.logicalV4(AgentRunPersistenceFixtures.logicalRunV4());
        final AgentRunAttemptEntity attempt;
        final AgentRunStreamEventEntity finalRow;
        final Command command;
        final String originalResult;
        final JpaAgentRunLedger ledger;

        Fixture() throws Exception {
            var request = AgentRunPersistenceFixtures.parallelIntakeRequest();
            var result = AgentRunPersistenceFixtures.parallelIntakeResult(0);
            run.bindV4Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
            run.markV4AttemptStarted();
            attempt = AgentRunAttemptEntity.startV4(request.agentRunId(),
                    AgentRunPersistenceFixtures.parallelIntakeAllocation(), AgentRunPersistenceFixtures.STARTED_AT);
            originalResult = MAPPER.writeValueAsString(result);
            attempt.recordV4ResultReady(result, originalResult, -1);
            run.markV4ResultReady(request.attemptId(), result.resultHash(), result.completedAt());
            command = new Command(request.agentRunId(), request.agentRunId(), request.attemptId(), 1,
                    request.command().commandId(), request.command().requestHash(), result.resultHash(), 0,
                    true, "AGENT_RUN_FINALIZATION_REJECTED");
            var event = new AgentStreamEventV4("agent-stream.v4", request.agentRunId(), request.attemptId(),
                    0, AgentStreamEventV4.EventType.FINAL, Audience.USER, result.completedAt(),
                    AgentStreamEventV4.Payload.finalPayload("parallel-final-receipt", result.resultHash()));
            var json = MAPPER.valueToTree(event);
            finalRow = AgentRunStreamEventEntity.createV2("final", request.agentRunId(), request.attemptId(), 0,
                    "final", Audience.USER, MAPPER.writeValueAsString(json), ContractJson.sha256Hex(json));
            ReflectionTestUtils.setField(finalRow, "streamProtocol", "agent-stream.v4");
            when(runs.findByIdForUpdate(request.agentRunId())).thenReturn(Optional.of(run));
            when(attempts.findByIdForUpdate(request.attemptId())).thenReturn(Optional.of(attempt));
            when(events.findV4Event(request.agentRunId(), request.attemptId(), 0)).thenReturn(Optional.of(finalRow));
            ledger = new JpaAgentRunLedger(runs, attempts, events, v3, v4, mock(EntityManager.class), MAPPER);
        }
    }
}
