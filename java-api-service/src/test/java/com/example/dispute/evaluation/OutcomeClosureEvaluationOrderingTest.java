package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evaluation.application.OutcomeClosureEvaluationProtocolGate;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Kind;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Observation;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Request;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Status;
import com.example.dispute.evaluation.application.SyntheticClosedOutcomeSnapshot;
import com.example.dispute.evaluation.application.SyntheticOutcomeClosureEvaluationService;
import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeCompensationParent;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutcomeClosureEvaluationOrderingTest {

    @Test
    void evaluationReadsExactClosedSnapshotAfterSuccessfulReceipts() {
        OutcomeClosurePrerequisiteService prerequisites =
                new OutcomeClosurePrerequisiteService();
        SyntheticOutcomeClosureEvaluationService service =
                new SyntheticOutcomeClosureEvaluationService(prerequisites);
        Map<String, String> sourceProjection = new HashMap<>();
        sourceProjection.put("decision", "APPROVE");
        SyntheticClosedOutcomeSnapshot snapshot = snapshot(sourceProjection);
        sourceProjection.put("decision", "REJECT");

        var first = service.evaluateAfterClosure(successfulRequest(), snapshot);
        var second = service.evaluateAfterClosure(successfulRequest(), snapshot);

        assertThat(first).isEqualTo(second);
        assertThat(first.snapshotRef()).isEqualTo(snapshot.snapshotRef());
        assertThat(first.snapshotHash()).isEqualTo(snapshot.snapshotHash());
        assertThat(first.evaluationStatus()).isEqualTo("COMPLETED_READ_ONLY");
        assertThat(first.automaticChangesApplied()).isFalse();
        assertThat(first.processMutated()).isFalse();
        assertThat(first.caseReopened()).isFalse();
        assertThat(first.projectionOnly()).isTrue();
        assertThat(snapshot.projection()).containsEntry("decision", "APPROVE");
        assertThatThrownBy(() -> snapshot.projection().put("decision", "REJECT"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unresolvedAmbiguityStopsBeforeEvaluation() {
        SyntheticOutcomeClosureEvaluationService service =
                new SyntheticOutcomeClosureEvaluationService(
                        new OutcomeClosurePrerequisiteService());
        Request request =
                new Request(
                        3,
                        5,
                        7,
                        Map.of("operation.1", "a".repeat(64)),
                        Map.of(),
                        List.of(
                                new Observation(
                                        "operation.1",
                                        Kind.OPERATION,
                                        Status.AMBIGUOUS,
                                        true,
                                        3,
                                        5,
                                        7,
                                        "a".repeat(64),
                                        null)));

        assertThatThrownBy(() -> service.evaluateAfterClosure(request, snapshot(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNRESOLVED_AMBIGUOUS");
    }

    @Test
    void syntheticEvaluationHasNoExternalClientOrProcessWriterDependency() {
        assertThat(
                        Arrays.stream(
                                        SyntheticOutcomeClosureEvaluationService.class
                                                .getDeclaredFields())
                                .map(Field::getType)
                                .map(Class::getName))
                .noneMatch(
                        name ->
                                name.contains("EvaluationAgentClient")
                                        || name.contains("RestClient")
                                        || name.contains("Repository")
                                        || name.startsWith("java.net"));
        assertThat(
                        Arrays.stream(
                                        OutcomeClosureEvaluationProtocolGate.class
                                                .getDeclaredFields())
                                .map(Field::getType)
                                .map(Class::getName))
                .noneMatch(
                        name ->
                                name.contains("EvaluationAgentClient")
                                        || name.contains("RestClient")
                                        || name.startsWith("java.net"));
    }

    @Test
    void committedClosureThenEvaluationPreserveExactSnapshotOrdering() {
        ClosureLedger ledger = new ClosureLedger(true);
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        ledger,
                        (ignoredExpectation, receiptId) ->
                                receiptId.equals(closure.receiptId())
                                        ? Optional.of(closure)
                                        : Optional.empty());
        OutcomeOperationLedger.ProjectionExpectation expectation = expectation();

        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot closed =
                gate.acceptCommittedClosure(expectation, closure);
        OutcomeClosureEvaluationProtocolGate.EvaluationAcceptance evaluation =
                gate.acceptCommittedEvaluation(closed, evaluationReceipt(closure));

        assertThat(closed.snapshotRef()).isEqualTo(closure.closedSnapshotRef());
        assertThat(closed.snapshotHash()).isEqualTo(closure.closedSnapshotHash());
        assertThat(evaluation.closedSnapshot()).isEqualTo(closed);
        assertThat(evaluation.caseReopened()).isFalse();
        assertThat(evaluation.readOnly()).isTrue();
        assertThat(ledger.recordReceiptCalls).isZero();
    }

    @Test
    void committedClosureIsRejectedUntilJavaLedgerReadinessIsTrue() {
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        new ClosureLedger(false),
                        (ignoredExpectation, ignoredReceiptId) -> Optional.of(closure));

        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), closure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void missingAuthoritativeClosureReceiptFailsClosedByDefault() {
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(new ClosureLedger(true));

        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), closureReceipt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative closure receipt is missing");
    }

    @Test
    void substitutedAuthoritativeClosureReceiptIsRejectedEvenWhenIdentityMatches() {
        OutcomeClosureReceipt claimed = closureReceipt();
        OutcomeClosureReceipt substituted =
                closureReceiptWithClosedSnapshotHash("9".repeat(64));
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        new ClosureLedger(true),
                        (ignoredExpectation, receiptId) ->
                                receiptId.equals(claimed.receiptId())
                                        ? Optional.of(substituted)
                                        : Optional.empty());

        assertThat(substituted.receiptId()).isEqualTo(claimed.receiptId());
        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match");
    }

    @Test
    void evaluationMustSucceedAgainstTheExactAuthoritativeClosedSnapshot() {
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        new ClosureLedger(true),
                        (ignoredExpectation, ignoredReceiptId) -> Optional.of(closure));
        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot closed =
                gate.acceptCommittedClosure(expectation(), closure);

        assertThatThrownBy(
                        () ->
                                gate.acceptCommittedEvaluation(
                                        closed,
                                        evaluationReceipt(
                                                closure,
                                                OutcomeWireTypes.EvaluationStatus.FAILED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact committed CLOSED snapshot");
        assertThat(
                        Arrays.stream(
                                OutcomeClosureEvaluationProtocolGate.ClosedSnapshot.class
                                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    private static Request successfulRequest() {
        return new Request(
                3,
                5,
                7,
                Map.of("operation.1", "a".repeat(64)),
                Map.of(),
                List.of(
                        new Observation(
                                "operation.1",
                                Kind.OPERATION,
                                Status.SUCCEEDED,
                                true,
                                3,
                                5,
                                7,
                                "a".repeat(64),
                                "b".repeat(64))));
    }

    private static SyntheticClosedOutcomeSnapshot snapshot(Map<String, String> projection) {
        return SyntheticClosedOutcomeSnapshot.create(
                "synthetic/snapshot/P7E1",
                3,
                5,
                7,
                Instant.parse("2026-07-24T05:00:00Z"),
                projection);
    }

    private static OutcomeOperationLedger.ProjectionExpectation expectation() {
        return new OutcomeOperationLedger.ProjectionExpectation(
                "OUTCOME_CLOSURE_PROJECTION",
                "OUTCOME_FIXTURE_TENANT",
                "OUTCOME_CLOSURE_FIXTURE",
                3,
                7,
                4,
                5);
    }

    private static OutcomeClosureReceipt closureReceipt() {
        return closureReceiptWithClosedSnapshotHash("6".repeat(64));
    }

    private static OutcomeClosureReceipt closureReceiptWithClosedSnapshotHash(
            String closedSnapshotHash) {
        return new OutcomeClosureReceipt(
                OutcomeClosureReceipt.SCHEMA_VERSION,
                "OUTCOME_CLOSURE_WORKFLOW",
                "OUTCOME_CLOSURE_FIXTURE",
                "CLOSURE_RECEIPT_P7E2",
                "1".repeat(64),
                "fixture/approval/P7E2",
                "2".repeat(64),
                "fixture/action/P7E2",
                "3".repeat(64),
                "fixture/required/P7E2",
                "4".repeat(64),
                1,
                "fixture/terminal/P7E2",
                "5".repeat(64),
                "fixture/closed/P7E2",
                closedSnapshotHash,
                20,
                0,
                0,
                0,
                0,
                0,
                Instant.parse("2026-07-24T05:00:00Z"),
                3,
                5,
                6,
                7,
                20,
                false);
    }

    private static OutcomeEvaluationReceipt evaluationReceipt(OutcomeClosureReceipt closure) {
        return evaluationReceipt(closure, OutcomeWireTypes.EvaluationStatus.SUCCEEDED);
    }

    private static OutcomeEvaluationReceipt evaluationReceipt(
            OutcomeClosureReceipt closure, OutcomeWireTypes.EvaluationStatus status) {
        return new OutcomeEvaluationReceipt(
                OutcomeEvaluationReceipt.SCHEMA_VERSION,
                closure.workflowId(),
                closure.caseId(),
                "EVALUATION_RECEIPT_P7E2",
                "7".repeat(64),
                closure.closedSnapshotRef(),
                closure.closedSnapshotHash(),
                "fixture/evaluation/P7E2",
                "8".repeat(64),
                status,
                Instant.parse("2026-07-24T05:01:00Z"),
                closure.epoch(),
                closure.revision(),
                closure.revision() + 1,
                closure.fence(),
                closure.committedEventSequence() + 1,
                false);
    }

    private static final class ClosureLedger implements OutcomeOperationLedger {
        private final boolean ready;
        private int recordReceiptCalls;

        private ClosureLedger(boolean ready) {
            this.ready = ready;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public OutcomeAttemptObservation appendAttempt(OutcomeAttemptObservation observation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutcomeOperationReceipt recordReceipt(OutcomeOperationReceipt receipt) {
            recordReceiptCalls++;
            return receipt;
        }

        @Override
        public Optional<OutcomeOperation> findOperation(OperationLookup lookup) {
            return Optional.empty();
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
                    ready ? 0 : 1,
                    0,
                    0,
                    0,
                    ready);
        }
    }
}
