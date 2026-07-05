package com.example.dispute.casecore.infrastructure;

import com.example.dispute.casecore.application.ExternalDisputeSimulationClient;
import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.casecore.application.SimulatedExternalDispute;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientExternalDisputeSimulationClient
        implements ExternalDisputeSimulationClient {

    private static final String ENDPOINT = "/internal/agents/external-import/simulate";

    private final RestClient restClient;

    public RestClientExternalDisputeSimulationClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<SimulatedExternalDispute> simulate(
            SimulateExternalImportCommand command,
            String traceId,
            String requestId) {
        SimulationResponse response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .body(SimulationResponse.class);
        if (response == null || response.items() == null || response.items().isEmpty()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "external import simulator returned an empty schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return response.items();
    }

    private record SimulationResponse(List<SimulatedExternalDispute> items) {}
}
