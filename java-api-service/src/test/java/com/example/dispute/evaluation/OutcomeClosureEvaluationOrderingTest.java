package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Kind;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Observation;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Request;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Status;
import com.example.dispute.evaluation.application.SyntheticClosedOutcomeSnapshot;
import com.example.dispute.evaluation.application.SyntheticOutcomeClosureEvaluationService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
