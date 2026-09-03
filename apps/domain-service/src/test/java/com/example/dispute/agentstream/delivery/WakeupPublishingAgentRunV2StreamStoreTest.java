package com.example.dispute.agentstream.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeup;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeupPublisher;
import com.example.dispute.agentstream.infrastructure.delivery.WakeupPublishingAgentRunV2StreamStore;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WakeupPublishingAgentRunV2StreamStoreTest {

    @Test
    void publishesOnlyAfterThePostgresAppendReturns() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentStreamEvent event = event(4);
        when(postgres.append(event)).thenReturn(new AppendReceipt(true, 4));
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        AppendReceipt receipt = store.append(event);

        AgentRunStreamWakeup expected =
                new AgentRunStreamWakeup(
                        AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", 4);
        InOrder ordered = inOrder(postgres, publisher);
        ordered.verify(postgres).append(event);
        ordered.verify(publisher).publish(expected);
        assertThat(receipt).isEqualTo(new AppendReceipt(true, 4));
    }

    @Test
    void redisFailureDoesNotRewriteTheCommittedPostgresReceipt() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentStreamEvent event = event(5);
        when(postgres.append(event)).thenReturn(new AppendReceipt(true, 5));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(publisher)
                .publish(org.mockito.ArgumentMatchers.any());
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        AppendReceipt receipt = store.append(event);

        assertThat(receipt).isEqualTo(new AppendReceipt(true, 5));
        verify(postgres).append(event);
    }

    @Test
    void publishesOneHighWatermarkOnlyAfterTheWholePostgresBatchCommits() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        List<AgentStreamEvent> events = List.of(event(6), event(7));
        BatchAppendReceipt durable =
                new BatchAppendReceipt(List.of(true, true), 7);
        when(postgres.appendBatch(events)).thenReturn(durable);
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        BatchAppendReceipt receipt = store.appendBatch(events);

        AgentRunStreamWakeup expected =
                new AgentRunStreamWakeup(
                        AgentRunStreamWakeup.SCHEMA_VERSION,
                        "RUN_1",
                        "ATTEMPT_1",
                        7);
        InOrder ordered = inOrder(postgres, publisher);
        ordered.verify(postgres).appendBatch(events);
        ordered.verify(publisher).publish(expected);
        assertThat(receipt).isEqualTo(durable);
    }

    @Test
    void publishesOnlyWhenPostgresInsertedAReconciledFinal() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentRunReconciledFinalStore.Request request = new AgentRunReconciledFinalStore.Request(
                "RUN_1", "ATTEMPT_1", Audience.USER, "urn:result:1", "a".repeat(64));
        AgentStreamEvent finalEvent = finalEvent(8);
        AgentRunReconciledFinalStore.Receipt durable =
                new AgentRunReconciledFinalStore.Receipt(finalEvent, true, 8, true);
        when(postgres.appendOrLoadReconciledFinal(request)).thenReturn(durable);
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        assertThat(store.appendOrLoad(request)).isSameAs(durable);

        InOrder ordered = inOrder(postgres, publisher);
        ordered.verify(postgres).appendOrLoadReconciledFinal(request);
        ordered.verify(publisher).publish(new AgentRunStreamWakeup(
                AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", 8));
    }

    @Test
    void cachedReconciledFinalDoesNotPublishAnotherWakeup() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentRunReconciledFinalStore.Request request = new AgentRunReconciledFinalStore.Request(
                "RUN_1", "ATTEMPT_1", Audience.USER, "urn:result:1", "a".repeat(64));
        AgentRunReconciledFinalStore.Receipt cached =
                new AgentRunReconciledFinalStore.Receipt(finalEvent(8), false, 8, true);
        when(postgres.appendOrLoadReconciledFinal(request)).thenReturn(cached);
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        assertThat(store.appendOrLoad(request)).isSameAs(cached);
        verify(postgres).appendOrLoadReconciledFinal(request);
        verifyNoInteractions(publisher);
    }

    @Test
    void redisFailureDoesNotRewriteTheInsertedReconciledFinalReceipt() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentRunReconciledFinalStore.Request request = new AgentRunReconciledFinalStore.Request(
                "RUN_1", "ATTEMPT_1", Audience.USER, "urn:result:1", "a".repeat(64));
        AgentRunReconciledFinalStore.Receipt durable =
                new AgentRunReconciledFinalStore.Receipt(finalEvent(8), true, 8, true);
        when(postgres.appendOrLoadReconciledFinal(request)).thenReturn(durable);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(publisher)
                .publish(org.mockito.ArgumentMatchers.any());
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        assertThat(store.appendOrLoad(request)).isSameAs(durable);
        verify(postgres).appendOrLoadReconciledFinal(request);
        verify(publisher).publish(new AgentRunStreamWakeup(
                AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", 8));
    }

    @Test
    void postgresReconciliationFailurePropagatesWithoutPublishingAWakeup() {
        PostgresAgentRunV2EventStore postgres = mock(PostgresAgentRunV2EventStore.class);
        AgentRunStreamWakeupPublisher publisher = mock(AgentRunStreamWakeupPublisher.class);
        AgentRunReconciledFinalStore.Request request = new AgentRunReconciledFinalStore.Request(
                "RUN_1", "ATTEMPT_1", Audience.USER, "urn:result:1", "a".repeat(64));
        AgentRunReconciledFinalStore.ConflictException failure =
                new AgentRunReconciledFinalStore.ConflictException("final conflict");
        when(postgres.appendOrLoadReconciledFinal(request)).thenThrow(failure);
        WakeupPublishingAgentRunV2StreamStore store =
                new WakeupPublishingAgentRunV2StreamStore(postgres, publisher);

        assertThatThrownBy(() -> store.appendOrLoad(request)).isSameAs(failure);
        verify(postgres).appendOrLoadReconciledFinal(request);
        verifyNoInteractions(publisher);
    }

    private AgentStreamEvent event(long sequence) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                "RUN_1",
                "ATTEMPT_1",
                sequence,
                StreamEventType.VISIBLE_DELTA,
                Audience.USER,
                Instant.parse("2026-07-19T00:00:00Z"),
                new Payload(
                        "answer",
                        "text",
                        "delta",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private AgentStreamEvent finalEvent(long sequence) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                "RUN_1",
                "ATTEMPT_1",
                sequence,
                StreamEventType.FINAL,
                Audience.USER,
                Instant.parse("2026-07-19T00:00:00Z"),
                new Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "urn:result:1",
                        "a".repeat(64),
                        null,
                        null));
    }
}
