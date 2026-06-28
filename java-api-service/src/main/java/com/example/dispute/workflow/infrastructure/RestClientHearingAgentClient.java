package com.example.dispute.workflow.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.example.dispute.workflow.application.HearingAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientHearingAgentClient implements HearingAgentClient {

    private final RestClient restClient;

    public RestClientHearingAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public HearingAgentResult analyze(
            JsonNode request, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri("/agent-api/v1/hearings/analyze")
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || response.path("workflow_status").isMissingNode()
                || !response.path("executed_nodes").isArray()
                || response.path("adjudication_draft").path("draft").isMissingNode()
                || response.path("prompt_version").asText().isBlank()
                || response.path("model").asText().isBlank()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "hearing agent returned an invalid schema",
                    Map.of("endpoint", "/agent-api/v1/hearings/analyze"));
        }
        List<String> nodes = new ArrayList<>();
        response.path("executed_nodes").forEach(node -> nodes.add(node.asText()));
        boolean requiresEvidence =
                response.path("evidence_gap")
                        .path("requires_supplemental_evidence")
                        .asBoolean(false);
        boolean manual =
                "MANUAL_REVIEW_REQUIRED"
                                .equals(response.path("workflow_status").asText())
                        || !response.path("manual_review_reasons").isEmpty();
        return new HearingAgentResult(
                response,
                requiresEvidence,
                manual,
                List.copyOf(nodes),
                response.path("prompt_version").asText(),
                response.path("model").asText());
    }
}
