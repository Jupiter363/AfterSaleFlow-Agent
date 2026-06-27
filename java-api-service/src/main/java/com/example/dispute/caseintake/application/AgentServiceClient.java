package com.example.dispute.caseintake.application;

public interface AgentServiceClient {
    IntakeAnalysis analyze(CreateCaseCommand command, String traceId, String requestId);
}
