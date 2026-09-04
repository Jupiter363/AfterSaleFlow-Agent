package com.example.dispute.workflow.runtime.ingress;

public record TargetIntakeEpochBinding(
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long roomFencingToken,
        long processRevision,
        long roomRevision,
        String temporalWorkflowId,
        String temporalBuildId) {

    public TargetIntakeEpochBinding {
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        requireNonNegative(roomEpoch, "roomEpoch");
        requireNonNegative(roomFencingToken, "roomFencingToken");
        requireNonNegative(processRevision, "processRevision");
        requireNonNegative(roomRevision, "roomRevision");
        requireText(temporalWorkflowId, "temporalWorkflowId");
        requireText(temporalBuildId, "temporalBuildId");
    }

    public TargetIntakeEpochBinding(
            String tenantSurrogate, String caseId, long roomEpoch, long roomFencingToken,
            long processRevision, String temporalWorkflowId, String temporalBuildId) {
        this(tenantSurrogate, caseId, roomEpoch, roomFencingToken, processRevision, 0,
                temporalWorkflowId, temporalBuildId);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
