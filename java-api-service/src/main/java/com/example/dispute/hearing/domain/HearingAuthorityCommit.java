package com.example.dispute.hearing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/** Idempotent Java authority command. The request hash binds the complete formal mutation. */
public record HearingAuthorityCommit(
        String schemaVersion,
        HearingAuthorityExpectation authority,
        OperationType operationType,
        String operationKey,
        String requestHash,
        Long temporalHistoryEventId,
        Instant committedAt) {

    public static final String SCHEMA_VERSION = "hearing-authority-commit.v1";

    private static final Pattern OPERATION_KEY =
            Pattern.compile("hearing[.](?:stage|party|agent|finalize|handoff|close)[A-Za-z0-9._:-]{1,480}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public HearingAuthorityCommit {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(operationType, "operationType");
        if (operationKey == null || !OPERATION_KEY.matcher(operationKey).matches()) {
            throw new IllegalArgumentException("operationKey is not a bounded Hearing operation key");
        }
        String requiredPrefix = switch (operationType) {
            case STAGE -> "hearing.stage";
            case PARTY_TERMINAL -> "hearing.party";
            case AGENT_RESULT -> "hearing.agent";
            case FINALIZE -> "hearing.finalize";
            case HANDOFF -> "hearing.handoff";
            case CLOSE -> "hearing.close";
        };
        if (!operationKey.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("operationKey does not match operationType");
        }
        if (requestHash == null || !SHA256.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256");
        }
        if (temporalHistoryEventId != null
                && (temporalHistoryEventId < 1 || temporalHistoryEventId > MAX_SAFE_INTEGER)) {
            throw new IllegalArgumentException("temporalHistoryEventId must be a positive safe integer");
        }
        committedAt = Objects.requireNonNull(committedAt, "committedAt")
                .truncatedTo(ChronoUnit.MICROS);
    }

    public enum OperationType {
        STAGE,
        PARTY_TERMINAL,
        AGENT_RESULT,
        FINALIZE,
        HANDOFF,
        CLOSE
    }
}
