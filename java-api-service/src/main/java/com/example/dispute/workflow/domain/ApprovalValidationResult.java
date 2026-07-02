package com.example.dispute.workflow.domain;

public record ApprovalValidationResult(
        boolean valid,
        String failureReason) {}
