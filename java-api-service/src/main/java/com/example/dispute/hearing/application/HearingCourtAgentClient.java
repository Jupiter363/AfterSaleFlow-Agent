package com.example.dispute.hearing.application;

public interface HearingCourtAgentClient {

    HearingCourtAgentResult generateRoundTurn(
            HearingCourtAgentCommand command, String traceId, String requestId);
}
