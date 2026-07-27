package com.example.dispute.workflow.targete2e.ingress;

public record TargetIntakeEpochBinding(
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long roomFencingToken,
        long processRevision,
        String temporalWorkflowId,
        String temporalBuildId) {

    public TargetIntakeEpochBinding {
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        requireNonNegative(roomEpoch, "roomEpoch");
        requireNonNegative(roomFencingToken, "roomFencingToken");
        requireNonNegative(processRevision, "processRevision");
        requireText(temporalWorkflowId, "temporalWorkflowId");
        requireText(temporalBuildId, "temporalBuildId");
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
