package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.executor.application.SyntheticNoopExecutionAssembly;
import com.example.dispute.executor.application.SyntheticOutcomeLedgerAdapter;
import com.example.dispute.executor.application.SyntheticOutcomeNoopVerticalSlice;
import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeCompensationParent;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.outcome.application.SyntheticOutcomeProjection;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionCommand;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionReceipt;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivity;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivityImpl;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutcomeSyntheticNoopAssemblyTest {

    @Test
    void assemblyPublishesExplicitSyntheticProjectionWithoutFormalReachability() {
        SyntheticNoopExecutionAssembly assembly =
                new SyntheticNoopExecutionAssembly(activity());

        SyntheticNoopExecutionAssembly.Result result = assembly.observe(command());

        SyntheticOutcomeProjection.Execution execution = result.projection().execution();
        assertThat(execution.mode()).isEqualTo("SIMULATED");
        assertThat(execution.status()).isEqualTo("OBSERVED_NO_EFFECT");
        assertThat(execution.actions()).isEmpty();
        assertThat(execution.receipts()).hasSize(1);
        assertThat(execution.syntheticOnly()).isTrue();
        assertThat(execution.formalReceiptPresent()).isFalse();
        assertThat(result.projection().closure().status())
                .isEqualTo("NOT_CLOSURE_ELIGIBLE");
        assertThat(result.projection().closure().closedAt()).isNull();
        assertThat(result.projection().projectionOnly()).isTrue();
    }

    @Test
    void firstZeroEpochIsAcceptedButNegativeSyntheticCoordinatesAreRejected() {
        assertThat(command().epoch()).isZero();
        assertThatThrownBy(() -> command(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
    }

    @Test
    void implementationHasNoRegistryRepositoryNetworkOrEvaluationClientDependency() {
        assertNoForbiddenFieldType(SyntheticNoopToolActivityImpl.class);
        assertNoForbiddenFieldType(SyntheticNoopExecutionAssembly.class);
        assertNoForbiddenFieldType(SyntheticOutcomeNoopVerticalSlice.class);
        assertThat(SyntheticNoopToolActivity.class.getAnnotations()).isEmpty();
    }

    @Test
    void typedVerticalSliceReservesAndObservesWithoutCreatingATerminalReceipt() {
        FakeLedger ledger = new FakeLedger(false);
        SyntheticOutcomeNoopVerticalSlice slice =
                new SyntheticOutcomeNoopVerticalSlice(activity(), ledger);
        OutcomeOperationCommand command = wireCommand();

        SyntheticOutcomeNoopVerticalSlice.Result result =
                slice.observe(command, signedFixture(command), binding(command));

        assertThat(result.syntheticReceipt().toolInvoked()).isFalse();
        assertThat(result.syntheticReceipt().externalEffectCreated()).isFalse();
        assertThat(result.syntheticReceipt().formalBusinessWriteCreated()).isFalse();
        assertThat(result.observation().observationType())
                .isEqualTo(OutcomeAttemptObservation.ObservationType.NO_EFFECT_CONFIRMED);
        assertThat(result.observation().effectMayHaveOccurred()).isFalse();
        assertThat(result.observation().retryPermitted()).isFalse();
        assertThat(result.closureReadiness().closureReady()).isFalse();
        assertThat(result.orderedSteps())
                .containsExactly(
                        SyntheticOutcomeNoopVerticalSlice.Step.OPERATION_RESERVED,
                        SyntheticOutcomeNoopVerticalSlice.Step.SYNTHETIC_NOOP_OBSERVED);
        assertThat(result.terminalReceiptCreated()).isFalse();
        assertThat(result.formalSinkInvoked()).isFalse();
        assertThat(ledger.recordReceiptCalls).isZero();
        assertThat(ledger.reserved).isNotNull();
        assertThat(ledger.attempts).hasSize(1);
    }

    @Test
    void ambiguousAttemptOnlyAppendsObservationAndRequiresReconciliation() {
        FakeLedger ledger = new FakeLedger(false);
        SyntheticOutcomeNoopVerticalSlice slice =
                new SyntheticOutcomeNoopVerticalSlice(activity(), ledger);
        OutcomeOperationCommand command = wireCommand();

        SyntheticOutcomeNoopVerticalSlice.AmbiguousResult result =
                slice.observeAmbiguous(
                        command, signedFixture(command), ambiguous(command), binding(command));

        assertThat(result.ledgerObservation().observationType())
                .isEqualTo(OutcomeAttemptObservation.ObservationType.AMBIGUOUS);
        assertThat(result.ledgerObservation().effectMayHaveOccurred()).isTrue();
        assertThat(result.ledgerObservation().retryPermitted()).isFalse();
        assertThat(result.reconciliationRequired()).isTrue();
        assertThat(result.blindRetryAllowed()).isFalse();
        assertThat(result.terminalReceiptCreated()).isFalse();
        assertThat(result.closureReadiness().closureReady()).isFalse();
        assertThat(result.orderedSteps())
                .containsExactly(
                        SyntheticOutcomeNoopVerticalSlice.Step.OPERATION_RESERVED,
                        SyntheticOutcomeNoopVerticalSlice.Step.AMBIGUOUS_OBSERVED,
                        SyntheticOutcomeNoopVerticalSlice.Step.RECONCILIATION_REQUIRED);
        assertThat(ledger.recordReceiptCalls).isZero();
    }

    @Test
    void rejectedJavaSignatureLeavesTheLedgerUntouched() {
        FakeLedger ledger = new FakeLedger(false);
        SyntheticOutcomeNoopVerticalSlice slice =
                new SyntheticOutcomeNoopVerticalSlice(activity(), ledger);
        OutcomeOperationCommand command = wireCommand();

        assertThatThrownBy(
                        () ->
                                slice.observe(
                                        command,
                                        signedFixture(command, "C".repeat(86)),
                                        binding(command)))
                .isInstanceOf(SyntheticNoopToolActivity.ExecutionException.class);
        assertThat(ledger.reserved).isNull();
        assertThat(ledger.attempts).isEmpty();
        assertThat(ledger.recordReceiptCalls).isZero();
    }

    @Test
    void rejectedJavaSignatureCannotWriteAnAmbiguousAttempt() {
        FakeLedger ledger = new FakeLedger(false);
        SyntheticOutcomeNoopVerticalSlice slice =
                new SyntheticOutcomeNoopVerticalSlice(activity(), ledger);
        OutcomeOperationCommand command = wireCommand();

        assertThatThrownBy(
                        () ->
                                slice.observeAmbiguous(
                                        command,
                                        signedFixture(command, "C".repeat(86)),
                                        ambiguous(command),
                                        binding(command)))
                .isInstanceOf(SyntheticNoopToolActivity.ExecutionException.class);
        assertThat(ledger.reserved).isNull();
        assertThat(ledger.attempts).isEmpty();
        assertThat(ledger.recordReceiptCalls).isZero();
    }

    @Test
    void publicApiCannotConstructTheAdapterOrForgeALedgerWriteCapability() {
        assertThat(Arrays.stream(SyntheticOutcomeLedgerAdapter.class.getDeclaredConstructors()))
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        assertThat(
                        Arrays.stream(SyntheticOutcomeLedgerAdapter.class.getDeclaredMethods())
                                .filter(
                                        method ->
                                                method.getName().equals("reserve")
                                                        || method.getName()
                                                                .equals("appendNoEffect")
                                                        || method.getName()
                                                                .equals("appendAmbiguous")))
                .allMatch(method -> !Modifier.isPublic(method.getModifiers()));
        assertThat(Arrays.stream(SyntheticOutcomeNoopVerticalSlice.class.getConstructors()))
                .allMatch(
                        constructor ->
                                Arrays.equals(
                                        constructor.getParameterTypes(),
                                        new Class<?>[] {
                                            SyntheticNoopToolActivityImpl.class,
                                            OutcomeOperationLedger.class
                                        }));
        assertThat(
                        Arrays.stream(
                                SyntheticNoopToolActivityImpl.VerifiedExecution.class
                                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(
                        Arrays.stream(
                                SyntheticNoopToolActivityImpl.VerifiedAmbiguousAttempt.class
                                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(
                        Arrays.stream(SyntheticNoopExecutionReceipt.class.getDeclaredMethods())
                                .map(method -> method.getName()))
                .doesNotContain("javaSignatureVerified");
    }

    private static void assertNoForbiddenFieldType(Class<?> type) {
        assertThat(
                        Arrays.stream(type.getDeclaredFields())
                                .map(Field::getType)
                                .map(Class::getName))
                .noneMatch(
                        name ->
                                name.contains("ToolRegistry")
                                        || name.contains("Repository")
                                        || name.contains("RestClient")
                                        || name.contains("EvaluationAgentClient")
                                        || name.startsWith("java.net"));
    }

    private static SyntheticNoopToolActivityImpl activity() {
        return new SyntheticNoopToolActivityImpl(
                fixture -> fixture.signature().equals("A".repeat(86)),
                new SyntheticNoopToolActivity.ReceiptSigner() {
                    @Override
                    public String signingKeyId() {
                        return "outcome-synthetic-receipt-key-1";
                    }

                    @Override
                    public String sign(String lowercaseReceiptHash) {
                        return "B".repeat(86);
                    }
                });
    }

    private static SyntheticNoopExecutionCommand command() {
        return command(0);
    }

    private static SyntheticNoopExecutionCommand command(long epoch) {
        return new SyntheticNoopExecutionCommand(
                SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                SyntheticNoopExecutionCommand.MARKER,
                SyntheticNoopExecutionCommand.RUNTIME_MODE,
                SyntheticNoopExecutionCommand.TRAFFIC_SOURCE,
                "OUTCOME_SYNTHETIC_ASSEMBLY",
                "outcome-synthetic/assembly",
                "operation.assembly",
                "synthetic/packet/assembly",
                "a".repeat(64),
                "b".repeat(64),
                epoch,
                5,
                7,
                false,
                Instant.parse("2026-07-24T04:00:00Z"),
                SyntheticNoopExecutionCommand.SIGNER,
                SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM,
                "outcome-synthetic-input-key-1",
                "A".repeat(86));
    }

    private static OutcomeOperationCommand wireCommand() {
        return new OutcomeOperationCommand(
                OutcomeOperationCommand.SCHEMA_VERSION,
                "OUTCOME_SYNTHETIC_P7E2",
                "OUTCOME_SYNTHETIC_CASE_P7E2",
                "COMMAND_P7E2",
                "operation.P7E2",
                "1".repeat(64),
                "synthetic/approval/P7E2",
                "2".repeat(64),
                "synthetic/packet/P7E2",
                "a".repeat(64),
                "synthetic/request/P7E2",
                "b".repeat(64),
                "3".repeat(64),
                OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT,
                true,
                false,
                1,
                0,
                4,
                5,
                7,
                11,
                1,
                Instant.parse("2026-07-24T04:05:00Z"),
                "synthetic-noop-v1",
                OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
                OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1,
                true);
    }

    private static SyntheticNoopExecutionCommand signedFixture(
            OutcomeOperationCommand command) {
        return signedFixture(command, "A".repeat(86));
    }

    private static SyntheticNoopExecutionCommand signedFixture(
            OutcomeOperationCommand command, String signature) {
        return new SyntheticNoopExecutionCommand(
                SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                SyntheticNoopExecutionCommand.MARKER,
                SyntheticNoopExecutionCommand.RUNTIME_MODE,
                SyntheticNoopExecutionCommand.TRAFFIC_SOURCE,
                "OUTCOME_SYNTHETIC_P7E2",
                "outcome-synthetic/" + command.workflowId(),
                command.operationId(),
                command.approvedActionSnapshotRef(),
                command.approvedActionSnapshotHash(),
                command.requestHash(),
                command.epoch(),
                command.revision(),
                command.fence(),
                false,
                Instant.parse("2026-07-24T04:00:00Z"),
                SyntheticNoopExecutionCommand.SIGNER,
                SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM,
                "outcome-synthetic-input-key-1",
                signature);
    }

    private static SyntheticOutcomeNoopVerticalSlice.Binding binding(
            OutcomeOperationCommand command) {
        return new SyntheticOutcomeNoopVerticalSlice.Binding(
                new OutcomeOperationLedger.ProjectionExpectation(
                        "OUTCOME_SYNTHETIC_PROJECTION",
                        "OUTCOME_SYNTHETIC_TENANT",
                        command.caseId(),
                        command.epoch(),
                        command.fence(),
                        command.sourceRevision(),
                        command.revision()),
                "OUTCOME_SYNTHETIC_PACKET",
                1,
                "4".repeat(64),
                command.approvedActionSnapshotHash(),
                "OUTCOME_SYNTHETIC_APPROVAL",
                "synthetic-policy-v1");
    }

    private static OutcomeExecutionAttemptObservation ambiguous(
            OutcomeOperationCommand command) {
        return new OutcomeExecutionAttemptObservation(
                OutcomeExecutionAttemptObservation.SCHEMA_VERSION,
                command.workflowId(),
                command.caseId(),
                "AMBIGUOUS_P7E2",
                "5".repeat(64),
                command.operationId(),
                command.operationKeyHash(),
                command.requestHash(),
                command.externalIdempotencyKeyHash(),
                command.attemptNo(),
                command.operationSequence(),
                command.requiredForClosure(),
                command.compensable(),
                OutcomeWireTypes.AttemptObservationStatus.AMBIGUOUS,
                OutcomeWireTypes.ExternalEffectTruth.UNKNOWN,
                OutcomeWireTypes.OperationStatus.RECONCILING,
                Instant.parse("2026-07-24T04:01:00Z"),
                Instant.parse("2026-07-24T04:02:00Z"),
                command.epoch(),
                command.revision(),
                command.revision() + 1,
                command.fence(),
                command.committedEventSequence() + 1,
                true,
                true,
                true);
    }

    private static final class FakeLedger implements OutcomeOperationLedger {
        private final boolean closureReady;
        private final List<OutcomeAttemptObservation> attempts = new ArrayList<>();
        private OutcomeOperation reserved;
        private int recordReceiptCalls;

        private FakeLedger(boolean closureReady) {
            this.closureReady = closureReady;
        }

        @Override
        public OutcomeProcessProjection createProjection(OutcomeProcessProjection projection) {
            return projection;
        }

        @Override
        public OutcomeProcessProjection advanceProjection(
                ProjectionExpectation expectation,
                OutcomeProcessProjection.ProcessState nextState,
                Instant advancedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutcomeOperation reserve(
                OutcomeOperation operation, OutcomeCompensationParent compensationParent) {
            assertThat(compensationParent).isNull();
            reserved = operation;
            return operation;
        }

        @Override
        public OutcomeAttemptObservation appendAttempt(OutcomeAttemptObservation observation) {
            attempts.add(observation);
            return observation;
        }

        @Override
        public OutcomeOperationReceipt recordReceipt(OutcomeOperationReceipt receipt) {
            recordReceiptCalls++;
            return receipt;
        }

        @Override
        public Optional<OutcomeOperation> findOperation(OperationLookup lookup) {
            return Optional.ofNullable(reserved);
        }

        @Override
        public Optional<OutcomeOperationReceipt> findReceipt(String operationId) {
            return Optional.empty();
        }

        @Override
        public List<OutcomeOperationState> readOperationStates(
                ProjectionExpectation expectation) {
            return List.of();
        }

        @Override
        public List<OutcomeCompensationParent> findCompensationParents(
                ProjectionExpectation expectation) {
            return List.of();
        }

        @Override
        public OutcomeClosureReadiness closureReadiness(ProjectionExpectation expectation) {
            return new OutcomeClosureReadiness(
                    expectation.projectionId(),
                    expectation.tenantSurrogate(),
                    expectation.caseId(),
                    expectation.outcomeEpoch(),
                    expectation.fencingToken(),
                    1,
                    1,
                    closureReady ? 0 : 1,
                    0,
                    0,
                    0,
                    closureReady);
        }
    }
}
