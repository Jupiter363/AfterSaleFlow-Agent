package com.example.dispute.caseintake.infrastructure;

import com.example.dispute.caseintake.application.AgentServiceClient;
import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.domain.model.RiskLevel;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientAgentServiceClient implements AgentServiceClient {

    private final RestClient restClient;

    public RestClientAgentServiceClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public IntakeAnalysis analyze(
            CreateCaseCommand command, String traceId, String requestId) {
        IntakeResponse response =
                restClient
                        .post()
                        .uri("/agent-api/v1/intake/analyze")
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(IntakeRequest.from(command))
                        .retrieve()
                        .body(IntakeResponse.class);
        if (response == null
                || blank(response.caseType())
                || response.riskLevel() == null
                || blank(response.title())
                || blank(response.normalizedDescription())) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "intake agent returned an invalid schema",
                    Map.of("endpoint", "/agent-api/v1/intake/analyze"));
        }
        return new IntakeAnalysis(
                response.caseType(),
                response.disputeType(),
                response.riskLevel(),
                response.potentialDispute(),
                response.missingSlots(),
                response.title(),
                response.normalizedDescription());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record IntakeRequest(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("after_sale_id") String afterSaleId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("merchant_id") String merchantId,
            String description,
            @JsonProperty("attachment_ids") List<String> attachmentIds,
            String channel) {

        private static IntakeRequest from(CreateCaseCommand command) {
            return new IntakeRequest(
                    command.orderId(),
                    command.afterSaleId(),
                    command.userId(),
                    command.merchantId(),
                    command.description(),
                    command.attachmentIds(),
                    command.channel());
        }
    }

    private record IntakeResponse(
            @JsonProperty("case_type") String caseType,
            @JsonProperty("dispute_type") String disputeType,
            @JsonProperty("risk_level") RiskLevel riskLevel,
            @JsonProperty("potential_dispute") boolean potentialDispute,
            @JsonProperty("missing_slots") List<String> missingSlots,
            String title,
            @JsonProperty("normalized_description") String normalizedDescription) {}
}
