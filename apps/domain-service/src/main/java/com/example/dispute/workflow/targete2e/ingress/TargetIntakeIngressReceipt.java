package com.example.dispute.workflow.targete2e.ingress;

import java.time.Instant;
import java.util.Objects;

public record TargetIntakeIngressReceipt(
        String commandId,
        String runId,
        String payloadSha256,
        String commandStatus,
        boolean idempotentReplay,
        Instant admittedAt) {

    public TargetIntakeIngressReceipt {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is invalid");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is invalid");
        }
        if (!runIdForCommand(commandId).equals(runId)) {
            throw new IllegalArgumentException("runId does not match commandId");
        }
        if (payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadSha256 is invalid");
        }
        if (commandStatus == null || commandStatus.isBlank()) {
            throw new IllegalArgumentException("commandStatus is invalid");
        }
        Objects.requireNonNull(admittedAt, "admittedAt must not be null");
    }

    /** Source-compatible constructor for callers that only knew the canonical command identity. */
    public TargetIntakeIngressReceipt(
            String commandId,
            String payloadSha256,
            String commandStatus,
            boolean idempotentReplay,
            Instant admittedAt) {
        this(
                commandId,
                runIdForCommand(commandId),
                payloadSha256,
                commandStatus,
                idempotentReplay,
                admittedAt);
    }

    public static String runIdForCommand(String commandId) {
        String prefix = "intake-message:";
        if (commandId == null
                || !commandId.startsWith(prefix)
                || commandId.length() == prefix.length()) {
            throw new IllegalArgumentException("canonical Intake commandId is invalid");
        }
        return "target-intake-run:" + commandId.substring(prefix.length());
    }
}
