package com.example.dispute.executor.domain.ledger;

import java.time.Instant;
import java.util.Objects;

/** Append-only observation; AMBIGUOUS and RECONCILING are deliberately nonterminal. */
public record OutcomeAttemptObservation(
        String observationId,
        String observationHash,
        String operationId,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        String requestHash,
        int attemptSequence,
        ObservationType observationType,
        String externalInvocationId,
        String observationRef,
        String observationPayloadHash,
        boolean effectMayHaveOccurred,
        boolean retryPermitted,
        Instant observedAt) {

    public static final String SCHEMA_VERSION = "outcome-operation-attempt-observation.v1";

    public OutcomeAttemptObservation {
        observationId = OutcomeLedgerValues.identifier(observationId, "observationId", 64);
        observationHash = OutcomeLedgerValues.sha256(observationHash, "observationHash");
        operationId = OutcomeLedgerValues.identifier(operationId, "operationId", 64);
        tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
        caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
        outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
        fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
        requestHash = OutcomeLedgerValues.sha256(requestHash, "requestHash");
        attemptSequence = OutcomeLedgerValues.positive(attemptSequence, "attemptSequence");
        observationType = Objects.requireNonNull(observationType, "observationType");
        if (externalInvocationId != null) {
            externalInvocationId = OutcomeLedgerValues.identifier(
                    externalInvocationId, "externalInvocationId", 256);
        }
        observationRef = OutcomeLedgerValues.immutableRef(observationRef, "observationRef");
        observationPayloadHash = OutcomeLedgerValues.sha256(
                observationPayloadHash, "observationPayloadHash");
        observedAt = OutcomeLedgerValues.instant(observedAt, "observedAt");
        observationType.requireFlags(effectMayHaveOccurred, retryPermitted);
    }

    public void requireExactReplay(OutcomeAttemptObservation candidate) {
        if (!equals(Objects.requireNonNull(candidate, "candidate"))) {
            throw new OutcomeLedgerRejectedException(
                    "OUTCOME_ATTEMPT_CONFLICT", "attempt observation identity was reused with different content");
        }
    }

    public enum ObservationType {
        INVOCATION_DISPATCHED,
        PRE_EFFECT_RETRYABLE_FAILURE,
        AMBIGUOUS,
        RECONCILING,
        NO_EFFECT_CONFIRMED;

        private void requireFlags(boolean effectMayHaveOccurred, boolean retryPermitted) {
            boolean valid = switch (this) {
                case AMBIGUOUS, RECONCILING -> effectMayHaveOccurred && !retryPermitted;
                case INVOCATION_DISPATCHED -> !retryPermitted;
                case PRE_EFFECT_RETRYABLE_FAILURE -> !effectMayHaveOccurred && retryPermitted;
                case NO_EFFECT_CONFIRMED -> !effectMayHaveOccurred;
            };
            if (!valid) {
                throw new IllegalArgumentException("unsafe retry/effect flags for " + this);
            }
        }
    }
}
