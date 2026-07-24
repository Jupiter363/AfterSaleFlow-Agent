package com.example.dispute.workflow.activity.tool;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSyntheticNoopReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;

/** Pure bridge between the shared Outcome protocol and the isolated signed fixture contract. */
public final class SyntheticOutcomeProtocolAdapter {

    private SyntheticOutcomeProtocolAdapter() {}

    public static SyntheticNoopExecutionCommand bind(
            OutcomeOperationCommand command, SyntheticNoopExecutionCommand signedFixture) {
        if (command == null || signedFixture == null) {
            throw new IllegalArgumentException("command and signedFixture are required");
        }
        if (command.runtimeMode()
                        != OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW
                || command.syntheticNoopMarker()
                        != OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1
                || command.effectClass() != OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT
                || !command.syntheticOnly()
                || command.compensable()) {
            throw new IllegalArgumentException("operation command is not synthetic no-op only");
        }
        if (!command.workflowId().startsWith("OUTCOME_SYNTHETIC_")
                || !command.caseId().startsWith("OUTCOME_SYNTHETIC_")
                || !signedFixture.workflowId()
                        .equals("outcome-synthetic/" + command.workflowId())
                || !signedFixture.operationId().equals(command.operationId())
                || !signedFixture.packetRef().equals(command.approvedActionSnapshotRef())
                || !signedFixture.packetHash().equals(command.approvedActionSnapshotHash())
                || !signedFixture.requestHash().equals(command.requestHash())
                || signedFixture.epoch() != command.epoch()
                || signedFixture.revision() != command.revision()
                || signedFixture.fence() != command.fence()
                || signedFixture.issuedAt().isAfter(command.deadlineAt())
                || command.operationSequence() < 1
                || command.operationSequence() > 64) {
            throw new IllegalArgumentException(
                    "signed fixture does not match the synthetic operation command");
        }
        return signedFixture;
    }

    public static OutcomeSyntheticNoopReceipt toWire(
            OutcomeOperationCommand command, SyntheticNoopExecutionReceipt receipt) {
        if (command == null || receipt == null) {
            throw new IllegalArgumentException("command and receipt are required");
        }
        if (!receipt.operationId().equals(command.operationId())
                || !receipt.requestHash().equals(command.requestHash())
                || receipt.epoch() != command.epoch()
                || receipt.revision() != command.revision()
                || receipt.fence() != command.fence()
                || !receipt.workflowId().equals("outcome-synthetic/" + command.workflowId())) {
            throw new IllegalArgumentException("synthetic receipt does not match its command");
        }
        return new OutcomeSyntheticNoopReceipt(
                OutcomeSyntheticNoopReceipt.SCHEMA_VERSION,
                OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1,
                OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
                OutcomeSyntheticNoopReceipt.TrafficSource.SIGNED_SYNTHETIC,
                OutcomeSyntheticNoopReceipt.OutputSink.ISOLATED_COMPARISON_LEDGER,
                receipt.fixtureId(),
                receipt.workflowId(),
                receipt.operationId(),
                receipt.packetRef(),
                receipt.packetHash(),
                receipt.requestHash(),
                receipt.epoch(),
                receipt.revision(),
                receipt.fence(),
                receipt.syntheticOnly(),
                receipt.containsRealCaseOrPartyData(),
                receipt.toolInvoked(),
                receipt.externalEffectCreated(),
                receipt.formalBusinessWriteCreated(),
                receipt.projectionOnly(),
                receipt.issuedAt(),
                OutcomeSyntheticNoopReceipt.Signer.JAVA_CONTROL_PLANE,
                OutcomeSyntheticNoopReceipt.SignatureAlgorithm.ES256,
                receipt.signingKeyId(),
                receipt.receiptHash(),
                receipt.signature());
    }

    public static void requireAmbiguousMatch(
            OutcomeOperationCommand command,
            OutcomeExecutionAttemptObservation observation) {
        if (command == null || observation == null
                || !command.workflowId().equals(observation.workflowId())
                || !command.caseId().equals(observation.caseId())
                || !command.operationId().equals(observation.operationId())
                || !command.operationKeyHash().equals(observation.operationKeyHash())
                || !command.requestHash().equals(observation.requestHash())
                || !command.externalIdempotencyKeyHash()
                        .equals(observation.externalIdempotencyKeyHash())
                || command.attemptNo() != observation.attemptNo()
                || command.operationSequence() != observation.operationSequence()
                || command.requiredForClosure() != observation.requiredForClosure()
                || command.compensable() != observation.compensable()
                || command.epoch() != observation.epoch()
                || command.fence() != observation.fence()
                || observation.sourceRevision() != command.revision()
                || observation.revision() != command.revision() + 1
                || observation.committedEventSequence()
                        <= command.committedEventSequence()
                || observation.observedAt().isBefore(observation.possibleDispatchAt())
                || !observation.closureBlocked()
                || !observation.blindRetryBlocked()
                || !observation.compensationBlocked()) {
            throw new IllegalArgumentException(
                    "ambiguous observation does not match its operation command");
        }
    }
}
