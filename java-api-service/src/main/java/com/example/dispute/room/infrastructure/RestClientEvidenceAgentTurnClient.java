package com.example.dispute.room.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.room.application.EvidenceAgentTurnClient;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientEvidenceAgentTurnClient implements EvidenceAgentTurnClient {

    private static final String ENDPOINT = "/internal/agents/evidence/turn";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestClientEvidenceAgentTurnClient(
            @Qualifier("agentRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
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
                        .onStatus(
                                status -> status.value() == 422,
                                (request, httpResponse) -> {
                                    throw new AgentExecutionException(
                                            ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                                            "evidence context envelope was rejected by the agent contract",
                                            Map.of(
                                                    "endpoint",
                                                    ENDPOINT,
                                                    "http_status",
                                                    httpResponse.getStatusCode().value(),
                                                    "validation_errors",
                                                    validationErrors(httpResponse)));
                                })
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

    private List<Map<String, Object>> validationErrors(
            org.springframework.http.client.ClientHttpResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode detail = root.path("details").path("errors");
            if (!detail.isArray()) {
                detail = root.path("detail");
            }
            if (!detail.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> errors = new ArrayList<>();
            for (JsonNode item : detail) {
                if (errors.size() >= 20) {
                    break;
                }
                List<String> location = new ArrayList<>();
                JsonNode rawLocation = item.path("loc");
                if (rawLocation.isArray()) {
                    rawLocation.forEach(
                            segment -> location.add(limit(segment.asText(), 128)));
                }
                errors.add(
                        Map.of(
                                "location", List.copyOf(location),
                                "type", limit(item.path("type").asText("unknown"), 128),
                                "message",
                                        limit(
                                                item.path("msg")
                                                        .asText("contract validation failed"),
                                                512)));
            }
            return List.copyOf(errors);
        } catch (IOException | RuntimeException ignored) {
            return List.of(
                    Map.of(
                            "location", List.of(),
                            "type", "unparseable_validation_response",
                            "message", "agent contract validation failed"));
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
