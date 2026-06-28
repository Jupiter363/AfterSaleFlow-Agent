package com.example.dispute.workflow.application;

public record WorkflowStartView(
        String caseId, String workflowId, String status, String routeType) {}
