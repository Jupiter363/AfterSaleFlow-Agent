package com.example.dispute.executor.application;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivityImpl;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.time.Instant;

/** Synthetic-only ledger bridge. It can reserve and observe but cannot write a terminal receipt. */
public final class SyntheticOutcomeLedgerAdapter {

    private final OutcomeOperationLedger ledger;

    SyntheticOutcomeLedgerAdapter(OutcomeOperationLedger ledger) {
        if (ledger == null) {
            throw new IllegalArgumentException("ledger is required");
        }
        this.ledger = ledger;
    }

    OutcomeOperation reserve(
            SyntheticNoopToolActivityImpl.VerifiedExecution verified,
            SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        return reserve(verified.command(), binding, verified.signedFixture().issuedAt());
    }

    OutcomeOperation reserve(
            SyntheticNoopToolActivityImpl.VerifiedAmbiguousAttempt verified,
            SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        return reserve(verified.command(), binding, verified.signedFixture().issuedAt());
    }

    private OutcomeOperation reserve(
            com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand command,
            SyntheticOutcomeNoopVerticalSlice.Binding binding,
            Instant reservedAt) {
        requireBinding(command, binding);
        if (reservedAt == null || reservedAt.isAfter(command.deadlineAt())) {
            throw new IllegalArgumentException("reservation time exceeds the command deadline");
        }
        OutcomeOperation operation =
                new OutcomeOperation(
                        command.operationId(),
                        binding.expectation().projectionId(),
                        binding.expectation().tenantSurrogate(),
                        command.caseId(),
                        command.epoch(),
                        command.fence(),
                        binding.expectation().processRevision(),
                        binding.expectation().outcomeRevision(),
                        OutcomeOperation.OperationKind.OPERATION,
                        command.operationSequence(),
                        command.operationKeyHash(),
                        command.requestHash(),
                        binding.reviewPacketId(),
                        binding.reviewPacketVersion(),
                        binding.reviewPacketHash(),
                        binding.reviewPacketActionHash(),
                        binding.approvalRecordId(),
                        command.approvalReceiptHash(),
                        command.requestHash(),
                        binding.decisionPolicyVersion(),
                        null,
                        command.approvedActionSnapshotHash(),
                        "SYNTHETIC_NOOP_ONLY",
                        command.toolCapabilityVersion(),
                        OutcomeOperation.RetryClass.NON_RETRYABLE,
                        command.externalIdempotencyKeyHash(),
                        command.requiredForClosure(),
                        false,
                        reservedAt);
        return ledger.reserve(operation, null);
    }

    OutcomeAttemptObservation appendNoEffect(
            SyntheticNoopToolActivityImpl.VerifiedExecution verified,
            SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        var command = verified.command();
        var receipt = verified.internalReceipt();
        requireBinding(command, binding);
        if (!command.operationId().equals(receipt.operationId())
                || !command.requestHash().equals(receipt.requestHash())
                || command.epoch() != receipt.epoch()
                || command.revision() != receipt.revision()
                || command.fence() != receipt.fence()
                || receipt.toolInvoked()
                || receipt.externalEffectCreated()
                || receipt.formalBusinessWriteCreated()) {
            throw new IllegalArgumentException("no-effect receipt does not match the reservation");
        }
        return ledger.appendAttempt(
                new OutcomeAttemptObservation(
                        observationId("NOOP", receipt.receiptHash()),
                        receipt.receiptHash(),
                        command.operationId(),
                        binding.expectation().tenantSurrogate(),
                        command.caseId(),
                        command.epoch(),
                        command.fence(),
                        command.requestHash(),
                        attemptSequence(command),
                        OutcomeAttemptObservation.ObservationType.NO_EFFECT_CONFIRMED,
                        null,
                        "urn:outcome:synthetic:noop:"
                                + receipt.receiptHash().substring(0, 24),
                        receipt.receiptHash(),
                        false,
                        false,
                        receipt.issuedAt()));
    }

    OutcomeAttemptObservation appendAmbiguous(
            SyntheticNoopToolActivityImpl.VerifiedAmbiguousAttempt verified,
            SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        var command = verified.command();
        var observation = verified.observation();
        requireBinding(command, binding);
        return ledger.appendAttempt(
                new OutcomeAttemptObservation(
                        observation.observationId(),
                        observation.observationHash(),
                        observation.operationId(),
                        binding.expectation().tenantSurrogate(),
                        observation.caseId(),
                        observation.epoch(),
                        observation.fence(),
                        observation.requestHash(),
                        attemptSequence(command),
                        OutcomeAttemptObservation.ObservationType.AMBIGUOUS,
                        null,
                        "urn:outcome:synthetic:ambiguous:"
                                + observation.observationId(),
                        observation.observationHash(),
                        true,
                        false,
                        observation.observedAt()));
    }

    OutcomeClosureReadiness closureReadiness(SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        OutcomeClosureReadiness readiness = ledger.closureReadiness(binding.expectation());
        if (!readiness.projectionId().equals(binding.expectation().projectionId())
                || !readiness.tenantSurrogate().equals(binding.expectation().tenantSurrogate())
                || !readiness.caseId().equals(binding.expectation().caseId())
                || readiness.outcomeEpoch() != binding.expectation().outcomeEpoch()
                || readiness.fencingToken() != binding.expectation().fencingToken()) {
            throw new IllegalStateException("ledger returned readiness for another projection");
        }
        return readiness;
    }

    private static void requireBinding(
            com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand command,
            SyntheticOutcomeNoopVerticalSlice.Binding binding) {
        if (command == null || binding == null
                || command.runtimeMode()
                        != OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW
                || command.syntheticNoopMarker()
                        != OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1
                || command.effectClass() != OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT
                || !command.syntheticOnly()
                || command.compensable()
                || command.operationSequence() < 1
                || command.operationSequence() > 64
                || !command.caseId().startsWith("OUTCOME_SYNTHETIC_")
                || !binding.expectation().tenantSurrogate().startsWith("OUTCOME_SYNTHETIC_")
                || !binding.expectation().caseId().equals(command.caseId())
                || binding.expectation().outcomeEpoch() != command.epoch()
                || binding.expectation().fencingToken() != command.fence()
                || binding.expectation().processRevision() != command.sourceRevision()
                || binding.expectation().outcomeRevision() != command.revision()
                || !binding.reviewPacketActionHash()
                        .equals(command.approvedActionSnapshotHash())) {
            throw new IllegalArgumentException("ledger binding is stale or not synthetic-only");
        }
    }

    private static int attemptSequence(
            com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand command) {
        if (command.attemptNo() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("attemptNo exceeds the ledger range");
        }
        return Math.toIntExact(command.attemptNo());
    }

    private static String observationId(String prefix, String hash) {
        return prefix + "_" + hash.substring(0, 32);
    }

}
