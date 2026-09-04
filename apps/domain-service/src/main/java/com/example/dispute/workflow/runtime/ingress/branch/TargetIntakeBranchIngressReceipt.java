package com.example.dispute.workflow.runtime.ingress.branch;

import java.time.Instant;

public record TargetIntakeBranchIngressReceipt(
        String commandId,
        String payloadSha256,
        String commandStatus,
        boolean idempotentReplay,
        Instant acceptedAt) {}
