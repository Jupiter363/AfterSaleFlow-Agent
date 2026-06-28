package com.example.dispute.workflow.domain;

public record CaseWorkflowSnapshot(
        String status,
        int roundNo,
        boolean waitingForEvidence,
        boolean evidenceTimedOut,
        boolean manualRequired) {}
