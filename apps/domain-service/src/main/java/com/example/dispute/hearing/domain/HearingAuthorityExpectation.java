package com.example.dispute.hearing.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact database authority tuple that a formal Hearing commit expects to fence. */
public record HearingAuthorityExpectation(
        String tenantSurrogate,
        String caseId,
        String flowInstanceId,
        String epochId,
        long roomEpoch,
        HearingWriterMode writerMode,
        HearingFlowStage stage,
        int stageSequence,
        long processRevision,
        long roomRevision,
        long fencingToken) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public HearingAuthorityExpectation {
        tenantSurrogate = identifier(tenantSurrogate, "tenantSurrogate");
        caseId = identifier(caseId, "caseId");
        flowInstanceId = identifier(flowInstanceId, "flowInstanceId");
        epochId = identifier(epochId, "epochId");
        Objects.requireNonNull(writerMode, "writerMode");
        Objects.requireNonNull(stage, "stage");
        if (roomEpoch < 0
                || processRevision < 0
                || roomRevision < 0
                || fencingToken < 0
                || roomEpoch > MAX_SAFE_INTEGER
                || processRevision > MAX_SAFE_INTEGER
                || roomRevision > MAX_SAFE_INTEGER
                || fencingToken > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("epoch, revisions, and fence must be safe non-negative integers");
        }
        if (stageSequence < 1 || stageSequence > HearingFlowStage.values().length) {
            throw new IllegalArgumentException("stageSequence is outside the Hearing stage machine");
        }
        if (stage.ordinal() + 1 != stageSequence) {
            throw new IllegalArgumentException("stageSequence must equal the durable Hearing stage ordinal");
        }
        if (writerMode != HearingWriterMode.LEGACY && fencingToken < 1) {
            throw new IllegalArgumentException("non-legacy Hearing authority requires a positive fence");
        }
    }

    public static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }
}
