package com.example.dispute.room.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.room.application.IntakeAgentTurnClient;
import com.example.dispute.room.application.IntakeAgentTurnCommand;
import com.example.dispute.room.application.IntakeAgentTurnResult;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientIntakeAgentTurnClient implements IntakeAgentTurnClient {

    private static final String ENDPOINT = "/internal/agents/intake/turn";

    private final RestClient restClient;

    public RestClientIntakeAgentTurnClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public IntakeAgentTurnResult run(
            IntakeAgentTurnCommand command, String traceId, String requestId) {
        IntakeAgentTurnResult response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .body(IntakeAgentTurnResult.class);
        if (response == null || response.roomUtterance() == null
                || response.roomUtterance().isBlank()
                || response.scrollSnapshot() == null
                || response.canvasOperations() == null) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "intake turn agent returned an invalid schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return response;
    }
}
