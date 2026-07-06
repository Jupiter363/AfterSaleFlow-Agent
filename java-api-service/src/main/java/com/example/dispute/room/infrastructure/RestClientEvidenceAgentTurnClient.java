package com.example.dispute.room.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.room.application.EvidenceAgentTurnClient;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientEvidenceAgentTurnClient implements EvidenceAgentTurnClient {

    private static final String ENDPOINT = "/internal/agents/evidence/turn";

    private final RestClient restClient;

    public RestClientEvidenceAgentTurnClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public EvidenceAgentTurnResult run(
            EvidenceAgentTurnCommand command, String traceId, String requestId) {
        EvidenceAgentTurnResult response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .body(EvidenceAgentTurnResult.class);
        if (response == null
                || response.roomUtterance() == null
                || response.roomUtterance().isBlank()
                || response.memoryPatch() == null
                || response.canvasOperations() == null
                || response.liabilityDetermined()
                || response.remedyRecommended()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evidence turn agent returned an invalid or unsafe schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return response;
    }
}
