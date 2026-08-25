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

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
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
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class AgentRunV2RecoveryTerminalEventTest {

    private static final String ERROR_CODE =
            "AGENT_RUN_RECOVERY_ATTEMPT_LIMIT_EXHAUSTED";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void activityFailureAppendsOneSanitizedErrorAndReplaysTheDurableResult()
            throws Exception {
        ActivityFailureHarness harness = activityFailureHarness(true, 107);
        when(harness.eventRepository().findMaxV2Sequence(
                        harness.run().getId(), harness.attempt().getId()))
                .thenReturn(107L, 108L);
        when(harness.eventRepository().findV2Event(
                        harness.run().getId(), harness.attempt().getId(), 108L))
                .thenReturn(Optional.of(event(
                        harness.run().getId(),
                        harness.attempt().getId(),
                        108L,
                        StreamEventType.ERROR,
                        Audience.USER,
                        "GRAPH_GATEWAY_NOT_READY",
                        false)));
        when(harness.recoveryEventStore().appendRecoveryErrorInCurrentTransaction(any()))
                .thenReturn(new AppendReceipt(true, 108));
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(), 107, true, AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        ExecuteAgentRunResult first = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        long durableAttemptVersion = harness.attempt().getAttemptVersion();
        ExecuteAgentRunResult replayed = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, first);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durableFailureResultSequence");

        assertThat(first.lastSequenceNo()).isEqualTo(108);
        assertThat(replayed).isEqualTo(first);
        assertThat(harness.attempt().getAttemptVersion()).isEqualTo(durableAttemptVersion);
        assertThat(harness.attempt().getLastSequenceNo()).isEqualTo(108);
        assertThat(harness.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(harness.run().getRunStatus()).isEqualTo("ABORTED");
        assertThat(harness.run().getFinalizationStatus()).isEqualTo("UNCOMMITTED");
        harness.attempt().requireDurableFailureResult(first);
        ArgumentCaptor<AgentStreamEvent> eventCaptor =
                ArgumentCaptor.forClass(AgentStreamEvent.class);
        verify(harness.recoveryEventStore(), times(1))
                .appendRecoveryErrorInCurrentTransaction(eventCaptor.capture());
        AgentStreamEvent terminal = eventCaptor.getValue();
        assertThat(terminal.sequenceNo()).isEqualTo(108);
        assertThat(terminal.eventType()).isEqualTo(StreamEventType.ERROR);
        assertThat(terminal.audience()).isEqualTo(Audience.USER);
        assertThat(terminal.payload().errorCode()).isEqualTo("GRAPH_GATEWAY_NOT_READY");
        assertThat(terminal.payload().retryable()).isFalse();
        assertThat(ContractJson.canonicalString(MAPPER.valueToTree(terminal)))
                .doesNotContain("response lost")
                .doesNotContain("provider")
                .doesNotContain("checkpoint");
        verify(harness.eventRepository(), times(2))
                .findMaxV2Sequence(harness.run().getId(), harness.attempt().getId());
        InOrder order = inOrder(harness.entityManager(), harness.recoveryEventStore());
        order.verify(harness.entityManager()).flush();
        order.verify(harness.recoveryEventStore())
                .appendRecoveryErrorInCurrentTransaction(terminal);
    }

    @Test
    void activityFailureAdoptsMatchingExistingErrorAndReplaysTheDurableResult() {
        ActivityFailureHarness harness = activityFailureHarness(
                true,
                90,
                Audience.MERCHANT,
                StreamEventType.ERROR,
                "GRAPH_GATEWAY_NOT_READY",
                false);
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(),
                90,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        ExecuteAgentRunResult first = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        long durableAttemptVersion = harness.attempt().getAttemptVersion();
        ExecuteAgentRunResult replayed = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        ExecuteAgentRunResult storedMinusOne = activityFailureResult(
                harness.attempt().getId(),
                89,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, storedMinusOne))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durableFailureResultSequence");

        assertThat(first).isEqualTo(source);
        assertThat(replayed).isEqualTo(first);
        assertThat(harness.attempt().getAttemptVersion()).isEqualTo(durableAttemptVersion);
        assertThat(harness.attempt().getLastSequenceNo()).isEqualTo(90);
        assertThat(harness.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.ABORTED);
        harness.attempt().requireDurableFailureResult(first);
        verify(harness.eventRepository(), times(2))
                .findMaxV2Sequence(harness.run().getId(), harness.attempt().getId());
        verify(harness.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
        verify(harness.entityManager(), never()).flush();
    }

    @Test
    void activityFailureAdoptsMatchingErrorOneSequenceAfterTheActivityHeartbeat() {
        ActivityFailureHarness harness = activityFailureHarness(
                true,
                13,
                Audience.SYSTEM,
                StreamEventType.ERROR,
                "GRAPH_GATEWAY_NOT_READY",
                false);
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(),
                12,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        ExecuteAgentRunResult first = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        ExecuteAgentRunResult replayed = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, first);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durableFailureResultSequence");

        assertThat(first.lastSequenceNo()).isEqualTo(13);
        assertThat(replayed).isEqualTo(first);
        assertThat(harness.attempt().getLastSequenceNo()).isEqualTo(13);
        assertThat(harness.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.ABORTED);
        harness.attempt().requireDurableFailureResult(first);
        verify(harness.eventRepository(), times(2))
                .findMaxV2Sequence(harness.run().getId(), harness.attempt().getId());
        verify(harness.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
        verify(harness.entityManager(), never()).flush();
    }

    @Test
    void failureResultProjectsExactParentTerminalAuthorityAndKeepsRetryableRunOpen() {
        ActivityFailureHarness terminal = activityFailureHarness(
                false,
                1,
                Audience.USER,
                StreamEventType.ERROR,
                "GRAPH_GATEWAY_NOT_READY",
                false);
        ExecuteAgentRunResult terminalSource = activityFailureResult(
                terminal.attempt().getId(),
                1,
                false,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        ExecuteAgentRunResult terminalFirst = terminal.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, terminalSource);
        String terminalResultJson = terminal.attempt().getResultJson();
        long terminalAttemptVersion = terminal.attempt().getAttemptVersion();
        ExecuteAgentRunResult terminalReplay = terminal.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, terminalSource);

        assertThat(terminalFirst).isEqualTo(terminalSource);
        assertThat(terminalReplay).isEqualTo(terminalFirst);
        assertThat(terminal.run().getRunStatus()).isEqualTo("FAILED");
        assertThat(terminal.run().getErrorCode()).isEqualTo(terminalFirst.errorCode());
        assertThat(terminal.run().getErrorRetryable()).isFalse();
        assertThat(terminal.run().getStopReason()).isEqualTo("STREAM_FAILED");
        assertThat(terminal.run().getCompletedAt().toInstant())
                .isEqualTo(terminalFirst.completedAt());
        assertThat(terminal.run().getFinalizationStatus()).isEqualTo("UNCOMMITTED");
        assertThat(terminal.run().getResultReadyAttemptId()).isNull();
        assertThat(terminal.run().getCommittedAttemptId()).isNull();
        assertThat(terminal.run().getFinalResultHash()).isNull();
        assertThat(terminal.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(terminal.attempt().getErrorCode()).isEqualTo(terminalFirst.errorCode());
        assertThat(terminal.attempt().getErrorRetryable()).isFalse();
        assertThat(terminal.attempt().getTerminationCode())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN.name());
        assertThat(terminal.attempt().getCompletedAt().toInstant())
                .isEqualTo(terminalFirst.completedAt());
        assertThat(terminal.attempt().getResultJson()).isEqualTo(terminalResultJson);
        assertThat(terminal.attempt().getAttemptVersion()).isEqualTo(terminalAttemptVersion);

        assertParentFailureReplayDriftRejected(
                terminal,
                terminalSource,
                "errorCode",
                "FOREIGN_TERMINAL_ERROR",
                terminalFirst.errorCode(),
                terminalResultJson,
                terminalAttemptVersion);
        assertParentFailureReplayDriftRejected(
                terminal,
                terminalSource,
                "errorRetryable",
                true,
                false,
                terminalResultJson,
                terminalAttemptVersion);
        assertParentFailureReplayDriftRejected(
                terminal,
                terminalSource,
                "stopReason",
                "FOREIGN_TERMINAL_STOP",
                "STREAM_FAILED",
                terminalResultJson,
                terminalAttemptVersion);
        assertParentFailureReplayDriftRejected(
                terminal,
                terminalSource,
                "completedAt",
                terminal.run().getCompletedAt().plusSeconds(1),
                terminal.run().getCompletedAt(),
                terminalResultJson,
                terminalAttemptVersion);
        verify(terminal.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());

        ActivityFailureHarness retryable = activityFailureHarness(false);
        ExecuteAgentRunResult retryableSource = activityFailureResult(
                retryable.attempt().getId(),
                0,
                false,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);

        ExecuteAgentRunResult retryableFirst = retryable.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, retryableSource);
        String retryableResultJson = retryable.attempt().getResultJson();
        long retryableAttemptVersion = retryable.attempt().getAttemptVersion();
        ExecuteAgentRunResult retryableReplay = retryable.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, retryableSource);

        assertThat(retryableFirst).isEqualTo(retryableSource);
        assertThat(retryableReplay).isEqualTo(retryableFirst);
        assertThat(retryable.run().getRunStatus()).isEqualTo("PENDING");
        assertThat(retryable.run().getErrorCode()).isNull();
        assertThat(retryable.run().getErrorRetryable()).isNull();
        assertThat(retryable.run().getStopReason()).isNull();
        assertThat(retryable.run().getCompletedAt()).isNull();
        assertThat(retryable.run().getFinalizationStatus()).isEqualTo("UNCOMMITTED");
        assertThat(retryable.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(retryable.attempt().getErrorCode()).isEqualTo(retryableFirst.errorCode());
        assertThat(retryable.attempt().getErrorRetryable()).isTrue();
        assertThat(retryable.attempt().getTerminationCode())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name());
        assertThat(retryable.attempt().getResultJson()).isEqualTo(retryableResultJson);
        assertThat(retryable.attempt().getAttemptVersion()).isEqualTo(retryableAttemptVersion);
        assertThatThrownBy(() -> retryable.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, retryableSource))
                .isInstanceOf(IllegalStateException.class);
        assertThat(retryable.attempt().getResultJson()).isEqualTo(retryableResultJson);
        assertThat(retryable.attempt().getAttemptVersion()).isEqualTo(retryableAttemptVersion);
        verify(retryable.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
    }

    @Test
    void durableFailureReplayRejectsRunAttemptAndSuppliedStatusDriftWithoutAppend() {
        ActivityFailureHarness harness = activityFailureHarness(
                true,
                90,
                Audience.MERCHANT,
                StreamEventType.ERROR,
                "GRAPH_GATEWAY_NOT_READY",
                false);
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(),
                90,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        ExecuteAgentRunResult durable = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        String durableJson = harness.attempt().getResultJson();
        assertThat(harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, source))
                .isEqualTo(durable);

        ExecuteAgentRunResult arbitrarySequence = activityFailureResult(
                harness.attempt().getId(),
                89,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, arbitrarySequence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durableFailureResultSequence");

        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.FAILED, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durableFailureAttemptStatus");
        assertThat(harness.run().getRunStatus()).isEqualTo("ABORTED");
        assertThat(harness.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.ABORTED);

        ReflectionTestUtils.setField(harness.run(), "runStatus", "RUNNING");
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v3FailureRunStatus");
        assertThat(harness.run().getRunStatus()).isEqualTo("RUNNING");
        ReflectionTestUtils.setField(harness.run(), "runStatus", "ABORTED");

        ReflectionTestUtils.setField(
                harness.attempt(), "attemptStatus", AgentRunAttemptStatus.FAILED);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, source))
                .isInstanceOf(IllegalStateException.class);
        assertThat(harness.run().getRunStatus()).isEqualTo("ABORTED");
        assertThat(harness.attempt().getAttemptStatus())
                .isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(harness.attempt().getLastSequenceNo()).isEqualTo(90);
        assertThat(harness.attempt().getResultJson()).isEqualTo(durableJson);
        verify(harness.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
        verify(harness.entityManager(), never()).flush();
    }

    @Test
    void durableFailureReplayRejectsIndependentEntityAndPayloadTerminalDrift() {
        ReplayTerminalDrift entityProtocol = replayTerminalDrift();
        AgentRunStreamEventEntity entityProtocolEvent = matchingFailureError(
                entityProtocol.harness());
        ReflectionTestUtils.setField(
                entityProtocolEvent, "streamProtocol", "agent-stream.v1");
        assertReplayTerminalDriftFails(entityProtocol, entityProtocolEvent);

        List<PayloadDrift> payloadDrifts = List.of(
                new PayloadDrift(
                        "payload protocol",
                        json -> json.put("schema_version", "agent-stream.v1")),
                new PayloadDrift(
                        "payload run",
                        json -> json.put("run_id", "AGENT_RUN_OTHER")),
                new PayloadDrift(
                        "payload attempt",
                        json -> json.put("attempt_id", "ATTEMPT_V2_OTHER")),
                new PayloadDrift(
                        "payload sequence",
                        json -> json.put("sequence", 89)),
                new PayloadDrift(
                        "payload audience",
                        json -> json.put("audience", "USER")),
                new PayloadDrift(
                        "payload event type",
                        json -> json.put("event_type", "final")),
                new PayloadDrift(
                        "payload error code",
                        json -> ((ObjectNode) json.get("payload"))
                                .put("error_code", "GRAPH_STREAM_PROTOCOL_REJECTED")),
                new PayloadDrift(
                        "payload retryability",
                        json -> ((ObjectNode) json.get("payload"))
                                .put("retryable", true)));

        for (PayloadDrift payloadDrift : payloadDrifts) {
            ReplayTerminalDrift drift = replayTerminalDrift();
            AgentRunStreamEventEntity persisted = matchingFailureError(drift.harness());
            ObjectNode payload = (ObjectNode) MAPPER.valueToTree(eventValue(
                    drift.harness().run().getId(),
                    drift.harness().attempt().getId(),
                    90,
                    StreamEventType.ERROR,
                    Audience.MERCHANT,
                    "GRAPH_GATEWAY_NOT_READY",
                    false));
            payloadDrift.mutation().accept(payload);
            ReflectionTestUtils.setField(
                    persisted, "payloadJson", ContractJson.canonicalString(payload));
            ReflectionTestUtils.setField(
                    persisted, "payloadHash", ContractJson.sha256Hex(payload));
            assertReplayTerminalDriftFails(drift, persisted, payloadDrift.name());
        }
    }

    @Test
    void activityFailureRejectsMismatchedExistingErrorsAndFinalBeforeStateMutation() {
        List<ExistingTerminalCase> cases = List.of(
                new ExistingTerminalCase(
                        "wrong run",
                        "AGENT_RUN_OTHER",
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        90,
                        StreamEventType.ERROR,
                        Audience.MERCHANT,
                        "GRAPH_GATEWAY_NOT_READY",
                        false),
                new ExistingTerminalCase(
                        "wrong attempt",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_OTHER",
                        90,
                        StreamEventType.ERROR,
                        Audience.MERCHANT,
                        "GRAPH_GATEWAY_NOT_READY",
                        false),
                new ExistingTerminalCase(
                        "wrong sequence",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        89,
                        StreamEventType.ERROR,
                        Audience.MERCHANT,
                        "GRAPH_GATEWAY_NOT_READY",
                        false),
                new ExistingTerminalCase(
                        "wrong audience",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        90,
                        StreamEventType.ERROR,
                        Audience.USER,
                        "GRAPH_GATEWAY_NOT_READY",
                        false),
                new ExistingTerminalCase(
                        "wrong error code",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        90,
                        StreamEventType.ERROR,
                        Audience.MERCHANT,
                        "GRAPH_STREAM_PROTOCOL_REJECTED",
                        false),
                new ExistingTerminalCase(
                        "wrong retryability",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        90,
                        StreamEventType.ERROR,
                        Audience.MERCHANT,
                        "GRAPH_GATEWAY_NOT_READY",
                        true),
                new ExistingTerminalCase(
                        "final",
                        AgentRunPersistenceFixtures.RUN_ID,
                        "ATTEMPT_V2_ACTIVITY_FAILURE",
                        90,
                        StreamEventType.FINAL,
                        Audience.MERCHANT,
                        null,
                        false));

        for (ExistingTerminalCase terminalCase : cases) {
            ActivityFailureHarness harness = activityFailureHarness(
                    true,
                    90,
                    Audience.MERCHANT,
                    StreamEventType.ERROR,
                    "GRAPH_GATEWAY_NOT_READY",
                    false);
            when(harness.eventRepository().findV2Event(
                            harness.run().getId(), harness.attempt().getId(), 90))
                    .thenReturn(Optional.of(event(
                            terminalCase.runId(),
                            terminalCase.attemptId(),
                            terminalCase.sequenceNo(),
                            terminalCase.eventType(),
                            terminalCase.audience(),
                            terminalCase.errorCode(),
                            terminalCase.retryable())));
            ExecuteAgentRunResult source = activityFailureResult(
                    harness.attempt().getId(),
                    90,
                    true,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

            assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                            AgentRunAttemptStatus.ABORTED, source))
                    .as(terminalCase.name())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(harness.run().getRunStatus()).as(terminalCase.name())
                    .isEqualTo("RUNNING");
            assertThat(harness.attempt().getAttemptStatus()).as(terminalCase.name())
                    .isEqualTo(AgentRunAttemptStatus.RUNNING);
            assertThat(harness.attempt().getLastSequenceNo()).as(terminalCase.name())
                    .isEqualTo(90);
            assertThat(harness.attempt().getResultJson()).as(terminalCase.name()).isNull();
            verify(harness.recoveryEventStore(), never())
                    .appendRecoveryErrorInCurrentTransaction(any());
            verify(harness.entityManager(), never()).flush();
        }
    }

    @Test
    void missingDurableFailureResultCannotAdvanceAFormerlyTerminalAttempt() {
        for (AgentRunAttemptStatus status : new AgentRunAttemptStatus[] {
                AgentRunAttemptStatus.FAILED, AgentRunAttemptStatus.ABORTED
        }) {
            boolean publicOutput = status == AgentRunAttemptStatus.ABORTED;
            ActivityFailureHarness harness = activityFailureHarness(publicOutput);
            harness.attempt().recordFailure(
                    status,
                    "GRAPH_GATEWAY_NOT_READY",
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    AgentRunPersistenceFixtures.COMPLETED_AT);
            ExecuteAgentRunResult source = activityFailureResult(
                    harness.attempt().getId(),
                    0,
                    publicOutput,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

            assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(status, source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("running attempt without a durable result");
            assertThat(harness.attempt().getLastSequenceNo()).isZero();
            assertThat(harness.attempt().getResultJson()).isNull();
            verify(harness.recoveryEventStore(), never())
                    .appendRecoveryErrorInCurrentTransaction(any());
        }
    }

    @Test
    void appendFailureMarksTheAtomicLedgerTransactionForRollback() {
        ActivityFailureHarness harness = activityFailureHarness(false);
        IllegalStateException appendFailure = new IllegalStateException("append failed");
        when(harness.recoveryEventStore().appendRecoveryErrorInCurrentTransaction(any()))
                .thenThrow(appendFailure);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        ProxyFactory proxyFactory = new ProxyFactory(harness.ledger());
        proxyFactory.addAdvice(interceptor);
        AgentRunLedger transactionalLedger = (AgentRunLedger) proxyFactory.getProxy();
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(), 0, false, AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        assertThatThrownBy(() -> transactionalLedger.recordAttemptFailureResult(
                        AgentRunAttemptStatus.FAILED, source))
                .isSameAs(appendFailure);

        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verify(harness.recoveryEventStore(), times(1))
                .appendRecoveryErrorInCurrentTransaction(any());
    }

    @Test
    void createNextAttemptKeepsItsSourceSequenceAndAppendsNoGlobalTerminal() {
        ActivityFailureHarness harness = activityFailureHarness(false);
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(),
                0,
                false,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);

        ExecuteAgentRunResult durable = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, source);

        assertThat(durable).isEqualTo(source);
        assertThat(harness.attempt().getLastSequenceNo()).isZero();
        assertThat(harness.attempt().getTerminationCode()).isEqualTo("CREATE_NEXT_ATTEMPT");
        verify(harness.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
        verify(harness.eventRepository(), never()).findMaxV2Sequence(any(), any());
    }

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
                mock(PostgresAgentRunV4EventWriter.class),
                entityManager,
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV3(
                AgentRunPersistenceFixtures.logicalRunV3());
        run.bindV3Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
        run.markV3AttemptStarted();
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
        run.markV3AttemptFailed(
                AgentRunAttemptStatus.ABORTED,
                true,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(attemptRepository.findMaxAttemptNoByAgentRunId(run.getId())).thenReturn(1L);
        when(attemptRepository.findByAgentRunIdAndAttemptNoForUpdate(run.getId(), 1L))
                .thenReturn(Optional.of(attempt));
        when(eventRepository.findMaxV2Sequence(run.getId(), attempt.getId()))
                .thenReturn(2L, 3L);
        when(eventRepository.findV2Event(run.getId(), attempt.getId(), 2L))
                .thenReturn(Optional.of(event(
                        run.getId(),
                        attempt.getId(),
                        2L,
                        StreamEventType.ATTEMPT_ABORTED)));
        AgentRunStreamEventEntity persistedTerminal = event(
                run.getId(),
                attempt.getId(),
                3L,
                StreamEventType.ERROR,
                Audience.USER,
                ERROR_CODE,
                false);
        when(eventRepository.findV2Event(run.getId(), attempt.getId(), 3L))
                .thenReturn(Optional.of(persistedTerminal));
        when(recoveryEventStore.appendRecoveryErrorInCurrentTransaction(any()))
                .thenReturn(new AppendReceipt(true, 3));

        ledger.terminalizeV2RecoveryCandidate(
                run.getId(),
                attempt.getId(),
                1,
                ERROR_CODE,
                AgentRunPersistenceFixtures.COMPLETED_AT);

        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        "ATTEMPT_V2_FOREIGN",
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        2,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        "FOREIGN_TERMINAL_ERROR",
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "runStatus",
                AgentRunAttemptStatus.ABORTED.name(),
                AgentRunAttemptStatus.FAILED.name());
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "errorCode",
                "FOREIGN_TERMINAL_ERROR",
                ERROR_CODE);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "errorRetryable",
                true,
                false);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "errorMessage",
                "FOREIGN_TERMINAL_MESSAGE",
                AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "stopReason",
                "FOREIGN_TERMINAL_STOP",
                AgentRunEntity.V3_LOGICAL_FAILURE_STOP_REASON);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "completedAt",
                run.getCompletedAt().plusSeconds(1),
                run.getCompletedAt());
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "finalizationStatus",
                "COMMITTED",
                "UNCOMMITTED");
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "resultReadyAttemptId",
                attempt.getId(),
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "committedAttemptId",
                attempt.getId(),
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "finalResultHash",
                "a".repeat(64),
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "committedManifestId",
                "MANIFEST_FOREIGN",
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "committedManifestHash",
                "b".repeat(64),
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "finalStreamSequenceNo",
                3L,
                null);
        assertRecoveryReplayRunDriftRejected(
                ledger,
                run,
                attempt,
                "finalizedAt",
                run.getCompletedAt(),
                null);
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "attemptStatus",
                AgentRunAttemptStatus.RESULT_READY,
                AgentRunAttemptStatus.ABORTED);
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "finalFrameObserved",
                true,
                false);
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "lastSequenceNo",
                4L,
                3L);
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "errorRetryable",
                false,
                true);
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "terminationCode",
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN.name(),
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name());
        assertRecoveryReplayAttemptDriftRejected(
                ledger,
                run,
                attempt,
                "completedAt",
                attempt.getCompletedAt().plusSeconds(1),
                attempt.getCompletedAt());

        String terminalPayloadHash = persistedTerminal.getPayloadHash();
        ReflectionTestUtils.setField(persistedTerminal, "payloadHash", "f".repeat(64));
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(persistedTerminal, "payloadHash", terminalPayloadHash);
        ReflectionTestUtils.setField(
                persistedTerminal, "eventType", StreamEventType.VISIBLE_DELTA.wireValue());
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(
                persistedTerminal, "eventType", StreamEventType.ERROR.wireValue());
        ReflectionTestUtils.setField(
                persistedTerminal,
                "createdAt",
                persistedTerminal.getCreatedAt().plusSeconds(1));
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        1,
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(
                persistedTerminal,
                "createdAt",
                AgentRunPersistenceFixtures.COMPLETED_AT.atOffset(java.time.ZoneOffset.UTC));
        var durableRunUpdatedAt = run.getUpdatedAt();
        long durableAttemptVersion = attempt.getAttemptVersion();
        ledger.terminalizeV2RecoveryCandidate(
                run.getId(),
                attempt.getId(),
                1,
                ERROR_CODE,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        assertThat(run.getUpdatedAt()).isEqualTo(durableRunUpdatedAt);
        assertThat(attempt.getAttemptVersion()).isEqualTo(durableAttemptVersion);

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
                mock(PostgresAgentRunV4EventWriter.class),
                mock(EntityManager.class),
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV3(
                AgentRunPersistenceFixtures.logicalRunV3());
        run.bindV3Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
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
                mock(PostgresAgentRunV4EventWriter.class),
                mock(EntityManager.class),
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV3(
                AgentRunPersistenceFixtures.logicalRunV3());
        run.bindV3Audience("USER", "[\"USER\"]", "[\"user-persistence\"]");
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
        return event(
                runId,
                attemptId,
                sequenceNo,
                eventType,
                Audience.USER,
                "PREEXISTING_GLOBAL_ERROR",
                false);
    }

    private static AgentRunStreamEventEntity event(
            String runId,
            String attemptId,
            long sequenceNo,
            StreamEventType eventType,
            Audience audience,
            String errorCode,
            boolean retryable) {
        AgentStreamEvent event = eventValue(
                runId,
                attemptId,
                sequenceNo,
                eventType,
                audience,
                errorCode,
                retryable);
        var json = MAPPER.valueToTree(event);
        return AgentRunStreamEventEntity.createV2Prelude(
                "EVENT_" + sequenceNo,
                runId,
                attemptId,
                sequenceNo,
                eventType.wireValue(),
                audience,
                ContractJson.canonicalString(json),
                ContractJson.sha256Hex(json),
                event.occurredAt());
    }

    private static AgentStreamEvent eventValue(
            String runId,
            String attemptId,
            long sequenceNo,
            StreamEventType eventType,
            Audience audience,
            String errorCode,
            boolean retryable) {
        AgentStreamEvent.Payload payload = switch (eventType) {
            case VISIBLE_DELTA -> new AgentStreamEvent.Payload(
                    "intake_turn", "room_utterance", "retained-prefix", null,
                    null, null, null, null, null, null);
            case ATTEMPT_ABORTED -> new AgentStreamEvent.Payload(
                    null, null, null, null, "PROVIDER_TIMEOUT", null,
                    null, null, null, null);
            case ERROR -> new AgentStreamEvent.Payload(
                    null, null, null, null, null, null,
                    null, null, errorCode, retryable);
            default -> new AgentStreamEvent.Payload(
                    null, null, null, null, null, null,
                    null, null, null, null);
        };
        return new AgentStreamEvent(
                "agent-stream.v3",
                runId,
                attemptId,
                sequenceNo,
                eventType,
                audience,
                AgentRunPersistenceFixtures.COMPLETED_AT,
                payload);
    }

    private static ReplayTerminalDrift replayTerminalDrift() {
        ActivityFailureHarness harness = activityFailureHarness(
                true,
                90,
                Audience.MERCHANT,
                StreamEventType.ERROR,
                "GRAPH_GATEWAY_NOT_READY",
                false);
        ExecuteAgentRunResult source = activityFailureResult(
                harness.attempt().getId(),
                90,
                true,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        ExecuteAgentRunResult durable = harness.ledger().recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, source);
        return new ReplayTerminalDrift(
                harness, source, durable, harness.attempt().getResultJson());
    }

    private static void assertRecoveryReplayRunDriftRejected(
            JpaAgentRunLedger ledger,
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            String field,
            Object driftedValue,
            Object durableValue) {
        ReflectionTestUtils.setField(run, field, driftedValue);
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        attempt.getAttemptNo(),
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .as(field)
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(run, field, durableValue);
    }

    private static void assertRecoveryReplayAttemptDriftRejected(
            JpaAgentRunLedger ledger,
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            String field,
            Object driftedValue,
            Object durableValue) {
        ReflectionTestUtils.setField(attempt, field, driftedValue);
        assertThatThrownBy(() -> ledger.terminalizeV2RecoveryCandidate(
                        run.getId(),
                        attempt.getId(),
                        attempt.getAttemptNo(),
                        ERROR_CODE,
                        AgentRunPersistenceFixtures.COMPLETED_AT))
                .as(field)
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(attempt, field, durableValue);
    }

    private static AgentRunStreamEventEntity matchingFailureError(
            ActivityFailureHarness harness) {
        return event(
                harness.run().getId(),
                harness.attempt().getId(),
                90,
                StreamEventType.ERROR,
                Audience.MERCHANT,
                "GRAPH_GATEWAY_NOT_READY",
                false);
    }

    private static void assertReplayTerminalDriftFails(
            ReplayTerminalDrift drift, AgentRunStreamEventEntity persisted) {
        assertReplayTerminalDriftFails(drift, persisted, "entity protocol");
    }

    private static void assertReplayTerminalDriftFails(
            ReplayTerminalDrift drift,
            AgentRunStreamEventEntity persisted,
            String description) {
        ActivityFailureHarness harness = drift.harness();
        when(harness.eventRepository().findV2Event(
                        harness.run().getId(), harness.attempt().getId(), 90))
                .thenReturn(Optional.of(persisted));

        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.ABORTED, drift.source()))
                .as(description)
                .isInstanceOf(IllegalStateException.class);
        assertThat(harness.run().getRunStatus()).as(description).isEqualTo("ABORTED");
        assertThat(harness.attempt().getAttemptStatus()).as(description)
                .isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(harness.attempt().getLastSequenceNo()).as(description).isEqualTo(90);
        assertThat(harness.attempt().getResultJson()).as(description)
                .isEqualTo(drift.durableJson());
        verify(harness.recoveryEventStore(), never())
                .appendRecoveryErrorInCurrentTransaction(any());
        verify(harness.entityManager(), never()).flush();
    }

    private static void assertParentFailureReplayDriftRejected(
            ActivityFailureHarness harness,
            ExecuteAgentRunResult source,
            String field,
            Object driftedValue,
            Object durableValue,
            String durableResultJson,
            long durableAttemptVersion) {
        ReflectionTestUtils.setField(harness.run(), field, driftedValue);
        assertThatThrownBy(() -> harness.ledger().recordAttemptFailureResult(
                        AgentRunAttemptStatus.FAILED, source))
                .as(field)
                .isInstanceOf(IllegalStateException.class);
        assertThat(harness.attempt().getResultJson()).as(field).isEqualTo(durableResultJson);
        assertThat(harness.attempt().getAttemptVersion()).as(field)
                .isEqualTo(durableAttemptVersion);
        ReflectionTestUtils.setField(harness.run(), field, durableValue);
    }

    private static ActivityFailureHarness activityFailureHarness(boolean publicOutput) {
        return activityFailureHarness(publicOutput, 0);
    }

    private static ActivityFailureHarness activityFailureHarness(
            boolean publicOutput, long highWatermark) {
        return activityFailureHarness(
                publicOutput,
                highWatermark,
                Audience.USER,
                highWatermark == 0
                        ? StreamEventType.ATTEMPT_STARTED
                        : StreamEventType.VISIBLE_DELTA,
                null,
                false);
    }

    private static ActivityFailureHarness activityFailureHarness(
            boolean publicOutput,
            long highWatermark,
            Audience audience,
            StreamEventType highWatermarkEventType,
            String errorCode,
            boolean retryable) {
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
                mock(PostgresAgentRunV4EventWriter.class),
                entityManager,
                MAPPER);
        AgentRunEntity run = AgentRunEntity.logicalV3(
                AgentRunPersistenceFixtures.logicalRunV3());
        run.bindV3Audience(
                audience.name(),
                "[\"" + audience.name() + "\"]",
                audience == Audience.MERCHANT
                        ? "[\"merchant-persistence\"]"
                        : "[\"user-persistence\"]");
        run.markV3AttemptStarted();
        String attemptId = "ATTEMPT_V2_ACTIVITY_FAILURE";
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                run.getId(),
                AgentRunPersistenceFixtures.allocation(1, attemptId),
                null,
                false,
                0,
                AgentRunPersistenceFixtures.STARTED_AT);
        if (publicOutput || highWatermark > 0) {
            attempt.recordHeartbeat(new AgentRunAttemptHeartbeat(
                    AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                    run.getId(),
                    attemptId,
                    1,
                    highWatermark,
                    publicOutput,
                    false,
                    AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(1)));
        }
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(attemptRepository.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        when(eventRepository.findMaxV2Sequence(run.getId(), attemptId))
                .thenReturn(highWatermark);
        when(eventRepository.findV2Event(run.getId(), attemptId, highWatermark))
                .thenReturn(Optional.of(event(
                        run.getId(),
                        attemptId,
                        highWatermark,
                        highWatermarkEventType,
                        audience,
                        errorCode,
                        retryable)));
        return new ActivityFailureHarness(
                ledger,
                run,
                attempt,
                eventRepository,
                recoveryEventStore,
                entityManager);
    }

    private static ExecuteAgentRunResult activityFailureResult(
            String attemptId,
            long lastSequenceNo,
            boolean publicOutput,
            AgentRunRecoveryAction recoveryAction) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                AgentRunPersistenceFixtures.RUN_ID,
                AgentRunPersistenceFixtures.RUN_ID,
                attemptId,
                1,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                lastSequenceNo,
                publicOutput,
                "GRAPH_GATEWAY_NOT_READY",
                recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                recoveryAction,
                AgentRunPersistenceFixtures.COMPLETED_AT);
    }

    private record ActivityFailureHarness(
            JpaAgentRunLedger ledger,
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            AgentRunStreamEventRepository eventRepository,
            PostgresAgentRunV2EventStore recoveryEventStore,
            EntityManager entityManager) {}

    private record ExistingTerminalCase(
            String name,
            String runId,
            String attemptId,
            long sequenceNo,
            StreamEventType eventType,
            Audience audience,
            String errorCode,
            boolean retryable) {}

    private record ReplayTerminalDrift(
            ActivityFailureHarness harness,
            ExecuteAgentRunResult source,
            ExecuteAgentRunResult durable,
            String durableJson) {}

    private record PayloadDrift(String name, Consumer<ObjectNode> mutation) {}
}
