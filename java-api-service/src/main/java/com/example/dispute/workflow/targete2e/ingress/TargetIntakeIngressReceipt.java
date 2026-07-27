package com.example.dispute.workflow.targete2e.ingress;

import java.time.Instant;
import java.util.Objects;

public record TargetIntakeIngressReceipt(
        String commandId,
        String payloadSha256,
        String commandStatus,
        boolean idempotentReplay,
        Instant admittedAt) {

    public TargetIntakeIngressReceipt {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is invalid");
        }
        if (payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadSha256 is invalid");
        }
        if (commandStatus == null || commandStatus.isBlank()) {
            throw new IllegalArgumentException("commandStatus is invalid");
        }
        Objects.requireNonNull(admittedAt, "admittedAt must not be null");
    }
}
