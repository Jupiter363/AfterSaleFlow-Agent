package com.example.dispute.workflow.domain;

import java.time.Duration;

public record HumanReviewCommand(
        String caseId,
        String reviewPacketId,
        int reviewPacketVersion,
        String actionHash,
        long expiresAtEpochMillis,
        Duration waitTimeout,
        String requiredRole) {}
