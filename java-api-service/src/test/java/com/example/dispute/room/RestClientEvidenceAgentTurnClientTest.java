package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.room.application.AgentInvocationContext;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.RestClientEvidenceAgentTurnClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientEvidenceAgentTurnClientTest {

    @Test
    void sendsOnlyTheVersionedEnvelopeAndInvocationContext() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientEvidenceAgentTurnClient client =
                new RestClientEvidenceAgentTurnClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://agent.test/internal/agents/evidence/turn"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Trace-Id", "TRACE_ENVELOPE"))
                .andExpect(header("X-Request-Id", "REQ_ENVELOPE"))
                .andExpect(header("X-Role", "SYSTEM"))
                .andExpect(
                        jsonPath("$.context_envelope.schema_version")
                                .value("evidence_context_envelope.v1"))
                .andExpect(
                        jsonPath("$.context_envelope.captured_at")
                                .value("2026-07-11T00:00:00Z"))
                .andExpect(jsonPath("$.context_envelope.case_snapshot.case_id").value("CASE_1"))
                .andExpect(
                        jsonPath("$.context_envelope.current_event.event_type")
                                .value("ROOM_OPENING"))
                .andExpect(
                        jsonPath("$.context_envelope.current_event.occurred_at")
                                .value("2026-07-11T00:00:00Z"))
                .andExpect(jsonPath("$.agent_context.agent_key").value("EVIDENCE_CLERK"))
                .andExpect(jsonPath("$.case_id").doesNotExist())
                .andExpect(jsonPath("$.room_type").doesNotExist())
                .andExpect(jsonPath("$.turn_source").doesNotExist())
                .andExpect(jsonPath("$.case_intake_dossier").doesNotExist())
                .andExpect(jsonPath("$.available_evidence").doesNotExist())
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "room_utterance":"请提交原始证据。",
                                  "memory_patch":{},
                                  "canvas_operations":[],
                                  "referenced_evidence_ids":[],
                                  "verification_suggestions":[],
                                  "authenticity_flags":[],
                                  "evidence_assessments":[{
                                    "evidence_id":"EVIDENCE_1",
                                    "analysis_method":"HYBRID",
                                    "inspected_modalities":["IMAGE","OCR_TEXT"],
                                    "fact_links":[{"fact_id":"FACT_DELIVERY","relation":"SUPPORTS"}],
                                    "authenticity_score":0.81,
                                    "relevance_score":0.92,
                                    "completeness_score":0.74,
                                    "assessment_confidence":0.86,
                                    "findings":[{"type":"VISIBLE_TEXT","description":"Order number visible"}],
                                    "limitations":["Source export was not available"],
                                    "risk_flags":[{"code":"SOURCE_UNVERIFIED","severity":"MEDIUM"}],
                                    "recommendation":"PLAUSIBLE",
                                    "human_review":{"required":false,"reason_codes":[],"instructions":[]},
                                    "summary":"The screenshot is relevant and readable."
                                  }],
                                  "liability_determined":false,
                                  "remedy_recommended":false,
                                  "knowledge_answer_mode":"NONE",
                                  "confidence":0.8
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        var result = client.run(command(), "TRACE_ENVELOPE", "REQ_ENVELOPE");

        assertThat(result.roomUtterance()).isEqualTo("请提交原始证据。");
        assertThat(result.evidenceAssessments()).singleElement().satisfies(
                assessment -> {
                    assertThat(assessment.evidenceId()).isEqualTo("EVIDENCE_1");
                    assertThat(assessment.inspectedModalities())
                            .containsExactly("IMAGE", "OCR_TEXT");
                    assertThat(assessment.assessmentConfidence()).isEqualTo(0.86);
                    assertThat(assessment.humanReview().required()).isFalse();
                });
        server.verify();
    }

    @Test
    void preservesVerificationSuggestionsForTextOnlyConversationResponses() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientEvidenceAgentTurnClient client =
                new RestClientEvidenceAgentTurnClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://agent.test/internal/agents/evidence/turn"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "room_utterance":"Please explain where the evidence came from.",
                                  "memory_patch":{},
                                  "canvas_operations":[],
                                  "referenced_evidence_ids":["EVIDENCE_HISTORY"],
                                  "verification_suggestions":[{
                                    "evidence_id":"EVIDENCE_HISTORY",
                                    "suggestion":"Provide the original export.",
                                    "confidence_score":0.64
                                  }],
                                  "authenticity_flags":[],
                                  "evidence_assessments":[],
                                  "liability_determined":false,
                                  "remedy_recommended":false,
                                  "knowledge_answer_mode":"NONE",
                                  "confidence":0.7
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        var result = client.run(command(), "TRACE_TEXT_ONLY", "REQ_TEXT_ONLY");

        assertThat(result.evidenceAssessments()).isEmpty();
        assertThat(result.verificationSuggestions()).singleElement().satisfies(
                suggestion -> {
                    assertThat(suggestion.evidenceId()).isEqualTo("EVIDENCE_HISTORY");
                    assertThat(suggestion.suggestion()).isEqualTo("Provide the original export.");
                    assertThat(suggestion.confidenceScore()).isEqualTo(0.64);
                });
        server.verify();
    }

    @Test
    void mapsHttp422ToAnExplicitAgentContractFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientEvidenceAgentTurnClient client =
                new RestClientEvidenceAgentTurnClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://agent.test/internal/agents/evidence/turn"))
                .andRespond(
                        withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"success\":false,\"details\":{\"errors\":[{\"type\":\"missing\",\"loc\":[\"body\",\"context_envelope\",\"case_snapshot\",\"source_type\"],\"msg\":\"Field required\",\"input\":{\"private\":\"must-not-leak\"}}]}}"));

        assertThatThrownBy(() -> client.run(command(), "TRACE_422", "REQ_422"))
                .isInstanceOfSatisfying(
                        AgentExecutionException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo(ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID);
                            assertThat(failure.details().toString())
                                    .contains("case_snapshot", "source_type", "missing")
                                    .doesNotContain("must-not-leak", "private");
                        });
        server.verify();
    }

    private static EvidenceAgentTurnCommand command() {
        Instant now = Instant.parse("2026-07-11T00:00:00Z");
        EvidenceContextEnvelopeV1 envelope =
                new EvidenceContextEnvelopeV1(
                        EvidenceContextEnvelopeV1.SCHEMA_VERSION,
                        now.toString(),
                        new EvidenceContextEnvelopeV1.CaseSnapshot(
                                "CASE_1",
                                1,
                                "EVIDENCE_OPEN",
                                "DISPUTE",
                                "SIGNED_NOT_RECEIVED",
                                "USER",
                                "签收争议",
                                "用户称未收到包裹。",
                                "MEDIUM",
                                null,
                                "ORDER_1",
                                null,
                                null,
                                "INTAKE_CREATED",
                                null,
                                null,
                                "EVIDENCE",
                                null),
                        null,
                        new EvidenceContextEnvelopeV1.ActorSnapshot(
                                "user-local",
                                "USER",
                                "USER",
                                "ACCESS_1",
                                "SESSION_1",
                                "default:CASE_1:EVIDENCE:user-local:USER:EVIDENCE_CLERK",
                                "EVIDENCE_CLERK:USER:v1",
                                "MEMEO_DEFAULT"),
                        new EvidenceContextEnvelopeV1.CurrentEvent(
                                "EVIDENCE_OPENING_1",
                                "ROOM_OPENING",
                                MessageType.AGENT_MESSAGE,
                                "user-local",
                                "USER",
                                null,
                                List.of(),
                                1,
                                now.toString()),
                        List.of(),
                        new EvidenceContextEnvelopeV1.PrivateConversation(
                                "SESSION_1",
                                "default:CASE_1:EVIDENCE:user-local:USER:EVIDENCE_CLERK",
                                0,
                                false,
                                List.of()),
                        new EvidenceContextEnvelopeV1.RoomPolicy(
                                "ROOM_1",
                                RoomType.EVIDENCE,
                                "OPEN",
                                null,
                                "USER",
                                true));
        AgentInvocationContext invocationContext =
                new AgentInvocationContext(
                        "default",
                        "CASE_1",
                        RoomType.EVIDENCE,
                        "user-local",
                        "USER",
                        "ACCESS_1",
                        "PARTY_USER",
                        List.of("EVIDENCE_SUBMIT"),
                        "EVIDENCE_CLERK",
                        "INVOCATION_1",
                        "SESSION_1",
                        "default:CASE_1:EVIDENCE:user-local:USER:EVIDENCE_CLERK",
                        "EVIDENCE_PARTY_PRIVATE",
                        List.of("user-local"),
                        List.of("USER"),
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");
        return new EvidenceAgentTurnCommand(envelope, invocationContext);
    }
}
