package com.example.dispute.workflow.domain;

public record HumanReviewSignal(
        String reviewerId,
        String reviewerRole,
        String decision,
        int reviewPacketVersion,
        String actionHash,
        String humanReviewRecordId,
        String reason) {}
