package com.example.dispute.workflow.domain;

public record HumanReviewResult(
        String reviewId,
        String status,
        boolean approved,
        boolean modified,
        String failureReason) {}
