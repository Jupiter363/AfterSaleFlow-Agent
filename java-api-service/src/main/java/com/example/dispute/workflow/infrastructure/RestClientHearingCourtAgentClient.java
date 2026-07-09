package com.example.dispute.workflow.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.hearing.application.HearingCourtAgentClient;
import com.example.dispute.hearing.application.HearingCourtAgentCommand;
import com.example.dispute.hearing.application.HearingCourtAgentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientHearingCourtAgentClient implements HearingCourtAgentClient {

    private static final String ENDPOINT = "/internal/agents/hearing/round-turn";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public RestClientHearingCourtAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public HearingCourtAgentResult generateRoundTurn(
            HearingCourtAgentCommand command, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(command))
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || response.path("speaker_role").asText().isBlank()
                || response.path("message_text").asText().isBlank()
                || response.path("court_event_type").asText().isBlank()
                || response.path("round_no").asInt(0) <= 0
                || response.path("prompt_version").asText().isBlank()
                || response.path("model").asText().isBlank()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "hearing court round agent returned an invalid schema",
                    Map.of("endpoint", ENDPOINT));
        }
        JsonNode nextRound = response.path("next_round_no");
        return new HearingCourtAgentResult(
                response.path("speaker_role").asText(),
                response.path("message_text").asText(),
                response.path("round_summary").asText(""),
                textArray(response.path("questions_for_user")),
                textArray(response.path("questions_for_merchant")),
                response.path("court_event_type").asText(),
                response.path("round_no").asInt(),
                nextRound.isMissingNode() || nextRound.isNull() ? null : nextRound.asInt(),
                response.path("final_draft_required").asBoolean(false),
                textArray(response.path("review_focus_signal")),
                response.path("prompt_version").asText(),
                response.path("model").asText());
    }

    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(
                item -> {
                    String value = item.asText("");
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                });
        return List.copyOf(values);
    }

    private static Map<String, Object> requestBody(HearingCourtAgentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "case_id", command.caseId());
        put(body, "workflow_id", command.workflowId());
        put(body, "order_id", command.orderId());
        put(body, "after_sale_id", command.afterSaleId());
        put(body, "logistics_id", command.logisticsId());
        put(body, "dispute_type", command.disputeType());
        put(body, "title", command.title());
        put(body, "case_description", command.caseDescription());
        put(body, "risk_level", command.riskLevel());
        body.put("round_no", command.roundNo());
        body.put("dossier_version", command.dossierVersion());
        body.put("final_round", command.finalRound());
        put(body, "round_status", command.roundStatus());
        put(body, "stop_reason", command.stopReason());
        put(body, "round_summary_json", command.roundSummaryJson());
        body.put("courtroom_context", courtroomContext(command.courtroomContextJson()));
        body.put(
                "party_submissions",
                command.partySubmissions().stream()
                        .map(RestClientHearingCourtAgentClient::partySubmissionBody)
                        .toList());
        return body;
    }

    private static Map<String, Object> partySubmissionBody(
            HearingCourtAgentCommand.PartySubmission submission) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "participant_role", submission.participantRole());
        put(body, "participant_id", submission.participantId());
        put(body, "submission_source", submission.submissionSource());
        put(body, "submission_json", submission.submissionJson());
        return body;
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static JsonNode courtroomContext(String courtroomContextJson) {
        try {
            return OBJECT_MAPPER.readTree(
                    courtroomContextJson == null || courtroomContextJson.isBlank()
                            ? "{}"
                            : courtroomContextJson);
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }
}
