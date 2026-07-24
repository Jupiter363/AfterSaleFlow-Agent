package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Kind;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Observation;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Request;
import com.example.dispute.evaluation.application.OutcomeClosurePrerequisiteService.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseClosureServiceTest {

    private static final String OP_HASH = "a".repeat(64);
    private static final String COMP_HASH = "b".repeat(64);

    private final OutcomeClosurePrerequisiteService service =
            new OutcomeClosurePrerequisiteService();

    @Test
    void permitsClosureOnlyAfterEveryRequiredAuthoritativeSuccess() {
        Request request =
                new Request(
                        3,
                        5,
                        7,
                        Map.of("operation.1", OP_HASH),
                        Map.of("compensation.1", COMP_HASH),
                        List.of(
                                observation(
                                        "operation.1", Kind.OPERATION, Status.SUCCEEDED, true, OP_HASH),
                                observation(
                                        "compensation.1",
                                        Kind.COMPENSATION,
                                        Status.SUCCEEDED,
                                        true,
                                        COMP_HASH)));

        assertThat(service.assess(request).ready()).isTrue();
        assertThat(service.assess(request).blockers()).isEmpty();
    }

    @Test
    void missingNonAuthoritativeStaleAndFailedReceiptsBlockClosure() {
        Request request =
                new Request(
                        3,
                        5,
                        7,
                        Map.of("operation.1", OP_HASH, "operation.missing", "c".repeat(64)),
                        Map.of(),
                        List.of(
                                new Observation(
                                        "operation.1",
                                        Kind.OPERATION,
                                        Status.FAILED,
                                        false,
                                        3,
                                        4,
                                        6,
                                        OP_HASH,
                                        "d".repeat(64))));

        assertThat(service.assess(request).ready()).isFalse();
        assertThat(service.assess(request).blockers())
                .contains(
                        "MISSING_OPERATION:operation.missing",
                        "NON_AUTHORITATIVE:operation.1",
                        "STALE_REVISION_OR_FENCE:operation.1",
                        "NOT_SUCCEEDED:operation.1");
    }

    @Test
    void anyUnresolvedAmbiguityBlocksClosureAndCannotBeCoveredBySuccess() {
        Request request =
                new Request(
                        3,
                        5,
                        7,
                        Map.of("operation.1", OP_HASH),
                        Map.of(),
                        List.of(
                                observation(
                                        "operation.1", Kind.OPERATION, Status.SUCCEEDED, true, OP_HASH),
                                observation(
                                        "operation.optional",
                                        Kind.OPERATION,
                                        Status.AMBIGUOUS,
                                        true,
                                        "e".repeat(64),
                                        null)));

        assertThat(service.assess(request).ready()).isFalse();
        assertThat(service.assess(request).blockers())
                .contains("UNRESOLVED_AMBIGUOUS:operation.optional");
    }

    private static Observation observation(
            String id, Kind kind, Status status, boolean authoritative, String requestHash) {
        return observation(id, kind, status, authoritative, requestHash, "f".repeat(64));
    }

    private static Observation observation(
            String id,
            Kind kind,
            Status status,
            boolean authoritative,
            String requestHash,
            String receiptHash) {
        return new Observation(
                id,
                kind,
                status,
                authoritative,
                3,
                5,
                7,
                requestHash,
                receiptHash);
    }
}
