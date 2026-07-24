package com.example.dispute.evaluation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Evaluates only an immutable CLOSED synthetic snapshot after closure prerequisites pass. */
public final class SyntheticOutcomeClosureEvaluationService {

    private final OutcomeClosurePrerequisiteService prerequisiteService;

    public SyntheticOutcomeClosureEvaluationService(
            OutcomeClosurePrerequisiteService prerequisiteService) {
        this.prerequisiteService = Objects.requireNonNull(prerequisiteService);
    }

    public EvaluationView evaluateAfterClosure(
            OutcomeClosurePrerequisiteService.Request request,
            SyntheticClosedOutcomeSnapshot snapshot) {
        prerequisiteService.requireReady(request);
        if (snapshot.epoch() != request.epoch()
                || snapshot.revision() != request.revision()
                || snapshot.fence() != request.fence()) {
            throw new IllegalArgumentException("CLOSED snapshot revision or fence does not match");
        }
        String evaluationHash =
                sha256(
                        snapshot.snapshotRef()
                                + "\n"
                                + snapshot.snapshotHash()
                                + "\n"
                                + snapshot.closedAt());
        return new EvaluationView(
                snapshot.snapshotRef(),
                snapshot.snapshotHash(),
                evaluationHash,
                "COMPLETED_READ_ONLY",
                false,
                false,
                false,
                true);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record EvaluationView(
            String snapshotRef,
            String snapshotHash,
            String evaluationHash,
            String evaluationStatus,
            boolean automaticChangesApplied,
            boolean processMutated,
            boolean caseReopened,
            boolean projectionOnly) {}
}
