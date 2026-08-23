package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evaluation.application.OutcomeClosureEvaluationProtocolGate;
import com.example.dispute.evaluation.application.AuthoritativeClosureReceiptReader;
import com.example.dispute.evaluation.application.AuthoritativeEvaluationReceiptReader;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Kind;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Observation;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Request;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Status;
import com.example.dispute.evaluation.application.SyntheticClosedOutcomeSnapshot;
import com.example.dispute.evaluation.application.SyntheticOutcomeClosureEvaluationService;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
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
                        0,
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
                                        0,
                                        5,
                                        7,
                                        "a".repeat(64),
                                        null)));

        assertThatThrownBy(() -> service.evaluateAfterClosure(request, snapshot(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNRESOLVED_AMBIGUOUS");
    }

    @Test
    void firstZeroEpochIsAcceptedButNegativeOutcomeCoordinatesAreRejected() {
        assertThat(successfulRequest().epoch()).isZero();
        assertThat(snapshot(Map.of()).epoch()).isZero();

        assertThatThrownBy(
                        () -> new Request(-1, 0, 1, Map.of(), Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
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
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeEvaluationReceipt evaluationReceipt = evaluationReceipt(closure);
        int[] authoritativeClosureReads = {0};
        OutcomeOperationLedger.ProjectionExpectation[] observedExpectation = {null};
        AuthoritativeEvaluationReceiptReader.EvaluationReceiptLookup[] observedEvaluationLookup =
                {null};
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        (expectation, receiptId) -> {
                            authoritativeClosureReads[0]++;
                            observedExpectation[0] = expectation;
                            return receiptId.equals(closure.receiptId())
                                    ? Optional.of(committedClosure(expectation, closure, true))
                                    : Optional.empty();
                        },
                        lookup -> {
                            observedEvaluationLookup[0] = lookup;
                            return lookup.receiptId().equals(evaluationReceipt.receiptId())
                                    ? Optional.of(evaluationReceipt)
                                    : Optional.empty();
                        });
        OutcomeOperationLedger.ProjectionExpectation expectation = expectation();

        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot closed =
                gate.acceptCommittedClosure(expectation, closure);
        OutcomeClosureEvaluationProtocolGate.EvaluationAcceptance evaluation =
                gate.acceptCommittedEvaluation(closed, evaluationReceipt);

        assertThat(closed.snapshotRef()).isEqualTo(closure.closedSnapshotRef());
        assertThat(closed.snapshotHash()).isEqualTo(closure.closedSnapshotHash());
        assertThat(evaluation.closedSnapshot()).isEqualTo(closed);
        assertThat(evaluation.caseReopened()).isFalse();
        assertThat(evaluation.readOnly()).isTrue();
        assertThat(authoritativeClosureReads[0]).isOne();
        assertThat(observedExpectation[0]).isEqualTo(expectation);
        assertThat(observedEvaluationLookup[0].sourceRevision()).isEqualTo(closed.revision());
        assertThat(observedEvaluationLookup[0].fence()).isEqualTo(closed.fence());
        assertThat(Arrays.stream(OutcomeClosureEvaluationProtocolGate.class.getDeclaredFields())
                        .map(Field::getType))
                .noneMatch(OutcomeOperationLedger.class::equals);
    }

    @Test
    void committedClosureIsRejectedUntilJavaLedgerReadinessIsTrue() {
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        (expectation, ignoredReceiptId) ->
                                Optional.of(committedClosure(expectation, closure, false)),
                        ignoredLookup -> Optional.empty());

        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), closure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void missingAuthoritativeClosureReceiptFailsClosedByDefault() {
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate();

        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), closureReceipt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative atomic closure snapshot is missing");
    }

    @Test
    void substitutedAuthoritativeClosureReceiptIsRejectedEvenWhenIdentityMatches() {
        OutcomeClosureReceipt claimed = closureReceipt();
        OutcomeClosureReceipt substituted =
                closureReceiptWithClosedSnapshotHash("9".repeat(64));
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        (expectation, receiptId) ->
                                receiptId.equals(claimed.receiptId())
                                        ? Optional.of(committedClosure(expectation, substituted, true))
                                        : Optional.empty(),
                        ignoredLookup -> Optional.empty());

        assertThat(substituted.receiptId()).isEqualTo(claimed.receiptId());
        assertThatThrownBy(() -> gate.acceptCommittedClosure(expectation(), claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match");
    }

    @Test
    void evaluationMustSucceedAgainstTheExactAuthoritativeClosedSnapshot() {
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeEvaluationReceipt failed = evaluationReceipt(
                closure, OutcomeWireTypes.EvaluationStatus.FAILED);
        OutcomeClosureEvaluationProtocolGate gate =
                new OutcomeClosureEvaluationProtocolGate(
                        (expectation, ignoredReceiptId) ->
                                Optional.of(committedClosure(expectation, closure, true)),
                        ignoredLookup -> Optional.of(failed));
        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot closed =
                gate.acceptCommittedClosure(expectation(), closure);

        assertThatThrownBy(
                        () ->
                                gate.acceptCommittedEvaluation(
                                        closed,
                                        failed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact committed CLOSED snapshot");
        assertThat(
                        Arrays.stream(
                                OutcomeClosureEvaluationProtocolGate.ClosedSnapshot.class
                                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void missingOrSubstitutedEvaluationReceiptFailsClosed() {
        OutcomeClosureReceipt closure = closureReceipt();
        OutcomeEvaluationReceipt claimed = evaluationReceipt(closure);
        OutcomeEvaluationReceipt substituted = evaluationReceiptWithLedgerHash(
                closure, "9".repeat(64));
        OutcomeClosureEvaluationProtocolGate missingGate = gate(
                closure, ignoredLookup -> Optional.empty());
        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot missingClosed =
                missingGate.acceptCommittedClosure(expectation(), closure);

        assertThatThrownBy(() -> missingGate.acceptCommittedEvaluation(missingClosed, claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative evaluation receipt is missing");

        OutcomeClosureEvaluationProtocolGate substitutedGate = gate(
                closure, ignoredLookup -> Optional.of(substituted));
        OutcomeClosureEvaluationProtocolGate.ClosedSnapshot substitutedClosed =
                substitutedGate.acceptCommittedClosure(expectation(), closure);
        assertThat(substituted.receiptId()).isEqualTo(claimed.receiptId());
        assertThatThrownBy(
                        () -> substitutedGate.acceptCommittedEvaluation(substitutedClosed, claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match");
    }

    private static Request successfulRequest() {
        return new Request(
                0,
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
                                0,
                                5,
                                7,
                                "a".repeat(64),
                                "b".repeat(64))));
    }

    private static SyntheticClosedOutcomeSnapshot snapshot(Map<String, String> projection) {
        return SyntheticClosedOutcomeSnapshot.create(
                "synthetic/snapshot/P7E1",
                0,
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
                0,
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
                0,
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
        return evaluationReceipt(closure, status, "8".repeat(64));
    }

    private static OutcomeEvaluationReceipt evaluationReceiptWithLedgerHash(
            OutcomeClosureReceipt closure, String evaluationLedgerHash) {
        return evaluationReceipt(
                closure, OutcomeWireTypes.EvaluationStatus.SUCCEEDED, evaluationLedgerHash);
    }

    private static OutcomeEvaluationReceipt evaluationReceipt(
            OutcomeClosureReceipt closure,
            OutcomeWireTypes.EvaluationStatus status,
            String evaluationLedgerHash) {
        return new OutcomeEvaluationReceipt(
                OutcomeEvaluationReceipt.SCHEMA_VERSION,
                closure.workflowId(),
                closure.caseId(),
                "EVALUATION_RECEIPT_P7E2",
                "7".repeat(64),
                closure.closedSnapshotRef(),
                closure.closedSnapshotHash(),
                "fixture/evaluation/P7E2",
                evaluationLedgerHash,
                status,
                Instant.parse("2026-07-24T05:01:00Z"),
                closure.epoch(),
                closure.revision(),
                closure.revision() + 1,
                closure.fence(),
                closure.committedEventSequence() + 1,
                false);
    }

    private static OutcomeClosureEvaluationProtocolGate gate(
            OutcomeClosureReceipt closure,
            AuthoritativeEvaluationReceiptReader evaluationReader) {
        return new OutcomeClosureEvaluationProtocolGate(
                (expectation, ignoredReceiptId) ->
                        Optional.of(committedClosure(expectation, closure, true)),
                evaluationReader);
    }

    private static AuthoritativeClosureReceiptReader.CommittedClosureSnapshot committedClosure(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            OutcomeClosureReceipt receipt,
            boolean ready) {
        return new AuthoritativeClosureReceiptReader.CommittedClosureSnapshot(
                receipt,
                new OutcomeClosureReadiness(
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
                        ready));
    }
}
