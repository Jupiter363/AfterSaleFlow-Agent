package com.example.dispute.workflow.observability.hearing;

/** Closed telemetry surface: identifiers and user-controlled labels cannot enter metrics. */
@FunctionalInterface
public interface HearingReliabilityObservationSink {

    void record(Event event, Outcome outcome);

    static HearingReliabilityObservationSink noop() {
        return (event, outcome) -> {};
    }

    enum Event {
        DEADLINE_SCHEDULER,
        HANDOFF_SCHEDULER,
        PARITY_COMPARISON,
        RECONCILIATION,
        ROLLBACK,
        RELIABILITY_REPLAY
    }

    enum Outcome {
        EXECUTED,
        MATCH,
        DIFFERENT,
        HARD_FAILURE,
        NO_CANDIDATE,
        RECOVERED,
        REJECTED_STALE,
        HALTED,
        DISABLED,
        FAILED
    }
}
