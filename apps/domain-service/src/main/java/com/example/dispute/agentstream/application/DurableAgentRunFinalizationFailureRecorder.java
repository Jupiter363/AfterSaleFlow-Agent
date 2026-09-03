package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Persists finalization rejection authority, then wakes SSE consumers from the durable source. */
@Service
public final class DurableAgentRunFinalizationFailureRecorder
        implements AgentRunFinalizationFailureRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DurableAgentRunFinalizationFailureRecorder.class);

    private final AgentRunLedger ledger;
    private final AgentRunStreamEventService streamEventService;

    public DurableAgentRunFinalizationFailureRecorder(
            AgentRunLedger ledger, AgentRunStreamEventService streamEventService) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.streamEventService = Objects.requireNonNull(streamEventService, "streamEventService");
    }

    @Override
    public Receipt record(Command command) {
        Receipt receipt = ledger.recordFinalizationFailure(command);
        try {
            streamEventService.wakeUpAfterCommit(
                    receipt.agentRunId(), receipt.attemptId(), receipt.terminalSequenceNo());
        } catch (RuntimeException wakeFailure) {
            LOGGER.warn(
                    "durable finalization error wake-up failed; replay remains authoritative"
                            + " run={} attempt={} failureType={}",
                    receipt.agentRunId(),
                    receipt.attemptId(),
                    wakeFailure.getClass().getName());
        }
        return receipt;
    }
}
