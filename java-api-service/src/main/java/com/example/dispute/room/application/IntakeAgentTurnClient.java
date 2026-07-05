package com.example.dispute.room.application;

public interface IntakeAgentTurnClient {
    IntakeAgentTurnResult run(
            IntakeAgentTurnCommand command, String traceId, String requestId);
}
