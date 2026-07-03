package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.workflow.infrastructure.RestClientHearingAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientHearingAgentClientTest {

    @Test
    void sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()
            throws Exception {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent.test")
                        .defaultHeader("X-Service-Secret", "secret");
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "http://agent.test/internal/agents/legacy/hearing/analyze"))
                .andExpect(header("X-Service-Secret", "secret"))
                .andExpect(header("X-Trace-Id", "TRACE_test"))
                .andExpect(header("X-Request-Id", "REQ_test"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_id":"CASE_test",
                                  "workflow_id":"WORKFLOW_test",
                                  "workflow_status":"COMPLETED",
                                  "executed_nodes":["issue_framing_node","adjudication_draft_node"],
                                  "evidence_gap":{"requires_supplemental_evidence":false,"gaps":[]},
                                  "adjudication_draft":{"draft":{"confidence":0.8}},
                                  "manual_review_reasons":[],
                                  "prompt_version":"hearing-v1",
                                  "model":"test-model"
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        var client = new RestClientHearingAgentClient(builder.build());

        var result =
                client.analyze(
                        new ObjectMapper().readTree("{\"case_id\":\"CASE_test\"}"),
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.requiresAdditionalEvidence()).isFalse();
        assertThat(result.manualRequired()).isFalse();
        assertThat(result.executedNodes())
                .containsExactly(
                        "issue_framing_node", "adjudication_draft_node");
        server.verify();
    }
}
