package com.example.dispute.workflow.targete2e.ingress;

import java.time.Instant;
import java.util.Objects;

/** Persists the pre-expiry command admission used to prove drain-only continuation. */
@FunctionalInterface
public interface TargetIntakeCommandAdmissionAuthority {

    AdmissionReceipt admit(AdmissionRequest request);

    record AdmissionRequest(
            String executionLane,
            String activationId,
            String manifestHash,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long roomFencingToken,
            long processRevision,
            String commandId,
            String payloadSha256,
            Instant requestedAt,
            Instant activationExpiresAt) {

        public AdmissionRequest {
            if (!TargetIntakeActivationGrant.TARGET_LANE.equals(executionLane)) {
                throw new IllegalArgumentException("executionLane is invalid");
            }
            requireText(activationId, "activationId");
            requireHash(manifestHash, "manifestHash");
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(commandId, "commandId");
            requireHash(payloadSha256, "payloadSha256");
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            Objects.requireNonNull(
                    activationExpiresAt, "activationExpiresAt must not be null");
            if (roomEpoch < 0 || roomFencingToken < 0 || processRevision < 0) {
                throw new IllegalArgumentException("command admission binding is invalid");
            }
        }
    }

    record AdmissionReceipt(
            String activationId,
            String manifestHash,
            String commandId,
            long roomEpoch,
            long roomFencingToken,
            Instant admittedAt,
            boolean idempotentReplay) {

        public AdmissionReceipt {
            requireText(activationId, "activationId");
            requireHash(manifestHash, "manifestHash");
            requireText(commandId, "commandId");
            Objects.requireNonNull(admittedAt, "admittedAt must not be null");
            if (roomEpoch < 0 || roomFencingToken < 0) {
                throw new IllegalArgumentException("command admission receipt is invalid");
            }
        }

        public void assertMatches(AdmissionRequest request) {
            if (!activationId.equals(request.activationId())
                    || !manifestHash.equals(request.manifestHash())
                    || !commandId.equals(request.commandId())
                    || roomEpoch != request.roomEpoch()
                    || roomFencingToken != request.roomFencingToken()
                    || !admittedAt.isBefore(request.activationExpiresAt())) {
                throw new IllegalStateException(
                        "pre-cutoff command admission receipt does not match the request");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
