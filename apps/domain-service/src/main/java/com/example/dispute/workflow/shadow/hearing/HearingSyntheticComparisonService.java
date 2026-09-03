package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Event;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Outcome;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ParityComparison;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.Verdict;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService.AdmissionReceipt;
import java.util.Objects;

/** Idempotently records one hash-bound, comparison-only Hearing observation. */
public final class HearingSyntheticComparisonService {

    private final HearingShadowParityService parityService;
    private final HearingSyntheticComparisonLedger ledger;
    private final HearingReliabilityObservationSink observations;

    public HearingSyntheticComparisonService(
            HearingShadowParityService parityService,
            HearingSyntheticComparisonLedger ledger,
            HearingReliabilityObservationSink observations) {
        this.parityService = Objects.requireNonNull(parityService, "parityService must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
    }

    public ParityComparison compareAndRecord(
            AdmissionReceipt admission, ParitySnapshot legacy, ParitySnapshot shadow) {
        ParityComparison candidate = parityService.compare(admission, legacy, shadow);
        ParityComparison committed = Objects.requireNonNull(
                ledger.appendOrLoad(candidate), "comparison ledger returned no row");
        if (!candidate.comparisonKeyHash().equals(committed.comparisonKeyHash())
                || !candidate.comparisonHash().equals(committed.comparisonHash())
                || !candidate.equals(committed)) {
            observations.record(Event.PARITY_COMPARISON, Outcome.HARD_FAILURE);
            throw new IllegalStateException(
                    "comparison key is already bound to different synthetic evidence");
        }
        observations.record(Event.PARITY_COMPARISON, outcome(committed.verdict()));
        return committed;
    }

    private static Outcome outcome(Verdict verdict) {
        return switch (verdict) {
            case MATCH -> Outcome.MATCH;
            case DIFFERENT -> Outcome.DIFFERENT;
            case HARD_FAILURE -> Outcome.HARD_FAILURE;
        };
    }
}
