package com.example.dispute.workflow.targete2e.ingress;

import java.time.Instant;
import java.util.Objects;

/** Non-secret activation identity. A compact activation JWS is never carried past the authority. */
public record TargetIntakeActivationGrant(
        String lane,
        String activationId,
        String manifestHash,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long roomFencingToken,
        long processRevision,
        long roomRevision,
        String temporalWorkflowId,
        String temporalBuildId,
        Instant expiresAt) {

    public static final String TARGET_LANE = "TARGET_E2E_CANDIDATE";

    public TargetIntakeActivationGrant {
        if (!TARGET_LANE.equals(lane)) {
            throw new IllegalArgumentException("target activation lane is invalid");
        }
        if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
            throw new IllegalArgumentException("activationId is invalid");
        }
        if (manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifestHash is invalid");
        }
        Objects.requireNonNull(tenantSurrogate, "tenantSurrogate must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(temporalWorkflowId, "temporalWorkflowId must not be null");
        Objects.requireNonNull(temporalBuildId, "temporalBuildId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (roomEpoch < 0 || roomFencingToken < 0 || processRevision < 0 || roomRevision < 0) {
            throw new IllegalArgumentException("activation revisions must be non-negative");
        }
    }

    public TargetIntakeActivationGrant(
            String lane, String activationId, String manifestHash, String tenantSurrogate, String caseId,
            long roomEpoch, long roomFencingToken, long processRevision, String temporalWorkflowId,
            String temporalBuildId, Instant expiresAt) {
        this(lane, activationId, manifestHash, tenantSurrogate, caseId, roomEpoch, roomFencingToken,
                processRevision, 0, temporalWorkflowId, temporalBuildId, expiresAt);
    }

    public void assertMatches(TargetIntakeEpochBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        if (!tenantSurrogate.equals(binding.tenantSurrogate())
                || !caseId.equals(binding.caseId())
                || roomEpoch != binding.roomEpoch()
                || roomFencingToken != binding.roomFencingToken()
                || processRevision != binding.processRevision()
                || roomRevision != binding.roomRevision()
                || !temporalWorkflowId.equals(binding.temporalWorkflowId())
                || !temporalBuildId.equals(binding.temporalBuildId())) {
            throw new IllegalStateException("target activation does not match the locked Intake epoch");
        }
    }
}
