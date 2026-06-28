package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.evaluation.infrastructure.RestClientEvaluationAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientEvaluationAgentClientTest {

    @Test
    void callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()
            throws Exception {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent.test")
                        .defaultHeader("X-Service-Secret", "secret");
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "http://agent.test/agent-api/v1/evaluations/analyze"))
                .andExpect(header("X-Service-Secret", "secret"))
                .andExpect(header("X-Trace-Id", "TRACE_evaluation"))
                .andExpect(header("X-Request-Id", "REQ_evaluation"))
                .andExpect(header("X-Role", "SYSTEM"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_id":"CASE_test",
                                  "evaluation_status":"COMPLETED",
                                  "metric_scores":{
                                    "draft_approval_rate":1.0,
                                    "reviewer_modification_rate":0.0
                                  },
                                  "findings":[],
                                  "rule_gap_suggestions":[],
                                  "improvement_suggestions":[],
                                  "automatic_changes_applied":false,
                                  "online_case_mutated":false,
                                  "evaluator_model":"evaluation-model",
                                  "prompt_version":"evaluation-v1",
                                  "latency_ms":11,
                                  "token_usage":21
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        var client =
                new RestClientEvaluationAgentClient(builder.build());

        var result =
                client.analyze(
                        new ObjectMapper()
                                .readTree(
                                        "{\"case_id\":\"CASE_test\",\"case_status\":\"CLOSED\"}"),
                        "TRACE_evaluation",
                        "REQ_evaluation");

        assertThat(result.evaluatorModel()).isEqualTo("evaluation-model");
        assertThat(result.promptVersion()).isEqualTo("evaluation-v1");
        assertThat(result.tokenUsage()).isEqualTo(21);
        assertThat(result.report().path("online_case_mutated").asBoolean())
                .isFalse();
        server.verify();
    }
}
