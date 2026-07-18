package com.example.dispute.agentstream.infrastructure.delivery;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifest;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Commits PostgreSQL first, then emits a best-effort Redis wake-up. */
@Component
public class WakeupPublishingAgentRunV2StreamStore implements AgentRunV2StreamStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(WakeupPublishingAgentRunV2StreamStore.class);

    private final PostgresAgentRunV2EventStore eventStore;
    private final AgentRunStreamWakeupPublisher wakeupPublisher;

    public WakeupPublishingAgentRunV2StreamStore(
            PostgresAgentRunV2EventStore eventStore,
            AgentRunStreamWakeupPublisher wakeupPublisher) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.wakeupPublisher =
                Objects.requireNonNull(wakeupPublisher, "wakeupPublisher must not be null");
    }

    @Override
    public AppendReceipt append(AgentStreamEvent event) {
        AppendReceipt receipt = eventStore.append(event);
        publishBestEffort(event.runId(), event.attemptId(), receipt.durableHighWatermark());
        return receipt;
    }

    public PostgresAgentRunV2EventStore.BatchAppendReceipt appendBatch(
            List<AgentStreamEvent> events) {
        PostgresAgentRunV2EventStore.BatchAppendReceipt receipt = eventStore.appendBatch(events);
        AgentStreamEvent first = events.getFirst();
        publishBestEffort(first.runId(), first.attemptId(), receipt.durableHighWatermark());
        return receipt;
    }

    public List<AgentStreamEvent> replay(
            String runId, String attemptId, long afterSequence, int limit) {
        return eventStore.replay(runId, attemptId, afterSequence, limit);
    }

    public long durableHighWatermark(String runId, String attemptId) {
        return eventStore.durableHighWatermark(runId, attemptId);
    }

    public Optional<AgentRunStreamRetentionManifest> retentionManifest(
            String runId, String attemptId) {
        return eventStore.retentionManifest(runId, attemptId);
    }

    private void publishBestEffort(String runId, String attemptId, long highWatermark) {
        try {
            wakeupPublisher.publish(
                    new AgentRunStreamWakeup(
                            AgentRunStreamWakeup.SCHEMA_VERSION,
                            runId,
                            attemptId,
                            highWatermark));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Redis agent stream wakeup failed after durable append for run {} attempt {} at {}",
                    runId,
                    attemptId,
                    highWatermark,
                    failure);
        }
    }
}
