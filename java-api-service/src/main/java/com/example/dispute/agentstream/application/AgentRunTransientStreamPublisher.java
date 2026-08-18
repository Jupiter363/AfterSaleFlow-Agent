package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import java.util.Objects;

/**
 * Ephemeral relay for v3 public frame events. These events are deliberately not used as a durable
 * cursor. A completed frame is later replayed from the durable stream store.
 */
@FunctionalInterface
public interface AgentRunTransientStreamPublisher {
    void publish(AgentStreamEvent event);

    static AgentStreamEvent requireTransientV3(AgentStreamEvent event) {
        Objects.requireNonNull(event, "event");
        if (!"agent-stream.v3".equals(event.schemaVersion())
                || !java.util.Set.of(
                                StreamEventType.PUBLIC_FRAME_START,
                                StreamEventType.PUBLIC_TEXT_DELTA,
                                StreamEventType.ACTIVE_FRAME_SNAPSHOT)
                        .contains(event.eventType())) {
            throw new IllegalArgumentException(
                    "transient relay accepts only in-flight agent-stream.v3 frame events");
        }
        Payload payload = event.payload();
        requireText(payload.frameId(), "frameId");
        requirePositive(payload.frameSequence(), "frameSequence");
        switch (event.eventType()) {
            case PUBLIC_FRAME_START -> {
                requireText(payload.frameType(), "frameType");
                if (payload.publicHeader() == null || !payload.publicHeader().isObject()) {
                    throw new IllegalArgumentException("publicHeader must be an object");
                }
            }
            case PUBLIC_TEXT_DELTA -> {
                requireNonNegative(payload.deltaIndex(), "deltaIndex");
                requireText(payload.delta(), "delta");
            }
            case ACTIVE_FRAME_SNAPSHOT -> {
                requireNonNegative(payload.deltaIndex(), "deltaIndex");
                if (payload.publicText() == null) {
                    throw new IllegalArgumentException("publicText must not be null");
                }
            }
            default -> throw new IllegalArgumentException("unsupported transient event");
        }
        return event;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    private static int requirePositive(Integer value, String field) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    static AgentRunTransientStreamPublisher noOp() {
        return event -> { };
    }
}
