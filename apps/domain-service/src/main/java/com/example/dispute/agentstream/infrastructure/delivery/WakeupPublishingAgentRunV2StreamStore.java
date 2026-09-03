package com.example.dispute.agentstream.infrastructure.delivery;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityReport;
import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamArchiveStore;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifest;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Commits PostgreSQL first, then emits a best-effort Redis wake-up. */
@Component
public class WakeupPublishingAgentRunV2StreamStore
        implements AgentRunV2StreamStore, AgentRunReconciledFinalStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(WakeupPublishingAgentRunV2StreamStore.class);

    private final PostgresAgentRunV2EventStore eventStore;
    private final AgentRunStreamWakeupPublisher wakeupPublisher;
    private AgentRunStreamArchiveStore archiveStore;

    public WakeupPublishingAgentRunV2StreamStore(
            PostgresAgentRunV2EventStore eventStore,
            AgentRunStreamWakeupPublisher wakeupPublisher) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.wakeupPublisher =
                Objects.requireNonNull(wakeupPublisher, "wakeupPublisher must not be null");
    }

    @Autowired(required = false)
    void setArchiveStore(AgentRunStreamArchiveStore archiveStore) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
    }

    @Override
    public AppendReceipt append(AgentStreamEvent event) {
        AppendReceipt receipt = eventStore.append(event);
        publishBestEffort(event.runId(), event.attemptId(), receipt.durableHighWatermark());
        return receipt;
    }

    @Override
    public boolean markPublicOutputStarted(String runId, String attemptId) {
        return eventStore.markPublicOutputStarted(runId, attemptId);
    }

    @Override
    public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
        BatchAppendReceipt receipt = eventStore.appendBatch(events);
        AgentStreamEvent first = events.getFirst();
        publishBestEffort(first.runId(), first.attemptId(), receipt.durableHighWatermark());
        return receipt;
    }

    @Override
    public AgentRunReconciledFinalStore.Receipt appendOrLoad(
            AgentRunReconciledFinalStore.Request request) {
        AgentRunReconciledFinalStore.Receipt receipt =
                eventStore.appendOrLoadReconciledFinal(request);
        if (receipt.inserted()) {
            publishBestEffort(
                    request.logicalRunId(),
                    request.attemptId(),
                    receipt.durableHighWatermark());
        }
        return receipt;
    }

    @Override
    public List<AgentStreamEvent> replay(
            String runId, String attemptId, long afterSequence, int limit) {
        return eventStore.replay(runId, attemptId, afterSequence, limit);
    }

    @Override
    public long durableHighWatermark(String runId, String attemptId) {
        return eventStore.durableHighWatermark(runId, attemptId);
    }

    @Override
    public CompatibilityReport validateCompatibility(
            String streamProtocol, String runId, String attemptId) {
        return eventStore.validateCompatibility(streamProtocol, runId, attemptId);
    }

    @Override
    public Optional<AgentRunStreamRetentionManifest> retentionManifest(
            String runId, String attemptId) {
        return archiveStore == null
                ? eventStore.retentionManifest(runId, attemptId)
                : archiveStore.retentionManifest(runId, attemptId);
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
