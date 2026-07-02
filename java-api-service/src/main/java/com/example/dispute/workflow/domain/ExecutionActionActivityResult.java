package com.example.dispute.workflow.domain;

public record ExecutionActionActivityResult(
        String actionId,
        String status,
        String externalReference) {}
