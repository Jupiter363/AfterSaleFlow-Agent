package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;

/** Durable, hash-bound append port for attempt-scoped public stream events. */
public interface AgentRunV2StreamStore {

    AppendReceipt append(AgentStreamEvent event);

    record AppendReceipt(boolean inserted, long durableHighWatermark) {
        public AppendReceipt {
            if (durableHighWatermark < 0) {
                throw new IllegalArgumentException(
                        "durableHighWatermark must not be negative");
            }
        }
    }
}
