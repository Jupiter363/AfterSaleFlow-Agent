package com.example.dispute.workflow.domain;

public record ReviewGateSnapshot(
        String reviewTaskId,
        String reviewPacketId,
        int reviewPacketVersion,
        String actionHash,
        long expiresAtEpochMillis,
        String requiredRole) {}
