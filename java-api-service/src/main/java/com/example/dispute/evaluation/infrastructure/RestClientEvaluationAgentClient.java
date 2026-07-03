package com.example.dispute.evaluation.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.evaluation.application.EvaluationAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientEvaluationAgentClient
        implements EvaluationAgentClient {

    private static final String ENDPOINT =
            "/internal/agents/evaluation/analyze";

    private final RestClient restClient;

    public RestClientEvaluationAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public EvaluationAgentResult analyze(
            JsonNode request, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || !"COMPLETED"
                        .equals(
                                response.path("evaluation_status").asText())
                || response.path("case_id").asText().isBlank()
                || !response.path("metric_scores").isObject()
                || !response.path("findings").isArray()
                || response.path("evaluator_model").asText().isBlank()
                || response.path("prompt_version").asText().isBlank()
                || response.path("automatic_changes_applied").asBoolean(true)
                || response.path("online_case_mutated").asBoolean(true)) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evaluation agent returned an invalid or unsafe schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return new EvaluationAgentResult(
                response,
                response.path("evaluator_model").asText(),
                response.path("prompt_version").asText(),
                response.path("latency_ms").asLong(),
                response.path("token_usage").asInt());
    }
}
