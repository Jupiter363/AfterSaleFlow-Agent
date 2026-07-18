package com.example.dispute.agentstream.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeup;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeupPublisher;
import com.example.dispute.agentstream.infrastructure.delivery.WakeupPublishingAgentRunV2StreamStore;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import java.time.Instant;
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
}
