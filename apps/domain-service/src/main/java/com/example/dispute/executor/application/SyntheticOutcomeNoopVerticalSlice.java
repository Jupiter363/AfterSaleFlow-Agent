package com.example.dispute.executor.application;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.outcome.application.SyntheticOutcomeProjection;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionCommand;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivityImpl;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSyntheticNoopReceipt;
import java.util.List;

/** Unregistered vertical slice. Its only durable fact is a nonterminal engineering observation. */
public final class SyntheticOutcomeNoopVerticalSlice {

    private final SyntheticNoopToolActivityImpl activity;
    private final SyntheticOutcomeLedgerAdapter ledgerAdapter;

    public SyntheticOutcomeNoopVerticalSlice(
            SyntheticNoopToolActivityImpl activity, OutcomeOperationLedger ledger) {
        if (activity == null || ledger == null) {
            throw new IllegalArgumentException("activity and ledger are required");
        }
        this.activity = activity;
        this.ledgerAdapter = new SyntheticOutcomeLedgerAdapter(ledger);
    }

    public Result observe(
            OutcomeOperationCommand command,
            SyntheticNoopExecutionCommand signedFixture,
            Binding binding) {
        SyntheticNoopToolActivityImpl.VerifiedExecution verified =
                activity.verifyAndExecute(command, signedFixture);
        OutcomeOperation reservation =
                ledgerAdapter.reserve(verified, binding);
        OutcomeAttemptObservation observation =
                ledgerAdapter.appendNoEffect(verified, binding);
        OutcomeClosureReadiness readiness = ledgerAdapter.closureReadiness(binding);
        requireClosureBlocked(readiness);
        return new Result(
                command,
                verified.wireReceipt(),
                reservation,
                observation,
                readiness,
                SyntheticOutcomeProjection.from(verified.internalReceipt()),
                List.of(Step.OPERATION_RESERVED, Step.SYNTHETIC_NOOP_OBSERVED),
                false,
                false);
    }

    public AmbiguousResult observeAmbiguous(
            OutcomeOperationCommand command,
            SyntheticNoopExecutionCommand signedFixture,
            OutcomeExecutionAttemptObservation ambiguous,
            Binding binding) {
        SyntheticNoopToolActivityImpl.VerifiedAmbiguousAttempt verified =
                activity.verifyAmbiguousAttempt(command, signedFixture, ambiguous);
        ledgerAdapter.reserve(verified, binding);
        OutcomeAttemptObservation observation =
                ledgerAdapter.appendAmbiguous(verified, binding);
        OutcomeClosureReadiness readiness = ledgerAdapter.closureReadiness(binding);
        requireClosureBlocked(readiness);
        return new AmbiguousResult(
                command,
                ambiguous,
                observation,
                readiness,
                List.of(
                        Step.OPERATION_RESERVED,
                        Step.AMBIGUOUS_OBSERVED,
                        Step.RECONCILIATION_REQUIRED),
                true,
                false,
                false);
    }

    private static void requireClosureBlocked(OutcomeClosureReadiness readiness) {
        if (readiness.closureReady()) {
            throw new IllegalStateException(
                    "synthetic no-op observation must never make closure ready");
        }
    }

    public enum Step {
        OPERATION_RESERVED,
        SYNTHETIC_NOOP_OBSERVED,
        AMBIGUOUS_OBSERVED,
        RECONCILIATION_REQUIRED
    }

    public record Binding(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            String reviewPacketId,
            int reviewPacketVersion,
            String reviewPacketHash,
            String reviewPacketActionHash,
            String approvalRecordId,
            String decisionPolicyVersion) {
        public Binding {
            if (expectation == null
                    || reviewPacketId == null
                    || !reviewPacketId.startsWith("OUTCOME_SYNTHETIC_")
                    || reviewPacketVersion < 1
                    || reviewPacketHash == null
                    || !reviewPacketHash.matches("[0-9a-f]{64}")
                    || reviewPacketActionHash == null
                    || reviewPacketActionHash.isBlank()
                    || approvalRecordId == null
                    || !approvalRecordId.startsWith("OUTCOME_SYNTHETIC_")
                    || decisionPolicyVersion == null
                    || !decisionPolicyVersion.startsWith("synthetic-")) {
                throw new IllegalArgumentException("invalid synthetic ledger binding");
            }
        }
    }

    public record Result(
            OutcomeOperationCommand command,
            OutcomeSyntheticNoopReceipt syntheticReceipt,
            OutcomeOperation reservation,
            OutcomeAttemptObservation observation,
            OutcomeClosureReadiness closureReadiness,
            SyntheticOutcomeProjection projection,
            List<Step> orderedSteps,
            boolean terminalReceiptCreated,
            boolean formalSinkInvoked) {
        public Result {
            orderedSteps = List.copyOf(orderedSteps);
            if (terminalReceiptCreated || formalSinkInvoked) {
                throw new IllegalArgumentException("synthetic result cannot contain formal effects");
            }
        }
    }

    public record AmbiguousResult(
            OutcomeOperationCommand command,
            OutcomeExecutionAttemptObservation wireObservation,
            OutcomeAttemptObservation ledgerObservation,
            OutcomeClosureReadiness closureReadiness,
            List<Step> orderedSteps,
            boolean reconciliationRequired,
            boolean blindRetryAllowed,
            boolean terminalReceiptCreated) {
        public AmbiguousResult {
            orderedSteps = List.copyOf(orderedSteps);
            if (!reconciliationRequired || blindRetryAllowed || terminalReceiptCreated) {
                throw new IllegalArgumentException("ambiguous result must fail closed");
            }
        }
    }
}
