package com.example.dispute.hearing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/** Formal facts made visible by the Java mutation inside the authority-ledger transaction. */
public record HearingFormalCommitResult(
        HearingFlowStage stage,
        int stageSequence,
        Instant sharedDeadlineAt,
        String resultRef,
        String resultHash,
        long committedEventSequence) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RESULT_REF = Pattern.compile("(?:urn|s3|minio):[^\\s]{1,1019}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public HearingFormalCommitResult {
        Objects.requireNonNull(stage, "stage");
        if (stageSequence < 1 || stageSequence != stage.ordinal() + 1) {
            throw new IllegalArgumentException("stageSequence must equal the durable Hearing stage ordinal");
        }
        if (sharedDeadlineAt != null) {
            sharedDeadlineAt = sharedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (stage.hasSharedPartyDeadline() != (sharedDeadlineAt != null)) {
            throw new IllegalArgumentException("sharedDeadlineAt is required only for a party-wait stage");
        }
        if (resultRef == null || !RESULT_REF.matcher(resultRef).matches()) {
            throw new IllegalArgumentException("resultRef must be a bounded immutable urn/s3/minio reference");
        }
        if (resultHash == null || !SHA256.matcher(resultHash).matches()) {
            throw new IllegalArgumentException("resultHash must be lowercase SHA-256");
        }
        if (committedEventSequence < 1 || committedEventSequence > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("committedEventSequence must be a positive safe integer");
        }
    }
}
