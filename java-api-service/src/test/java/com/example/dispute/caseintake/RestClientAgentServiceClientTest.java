package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.caseintake.infrastructure.RestClientAgentServiceClient;
import com.example.dispute.domain.model.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientAgentServiceClientTest {

    @Test
    void sendsServiceCredentialAndMapsStructuredIntakeResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientAgentServiceClient client =
                new RestClientAgentServiceClient(
                        builder.baseUrl("http://agent:8000")
                                .defaultHeader("X-Service-Secret", "agent-secret")
                                .build());
        server.expect(
                        requestTo(
                                "http://agent:8000/internal/agents/legacy/intake/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Secret", "agent-secret"))
                .andExpect(header("X-Trace-Id", "TRACE_agent_test"))
                .andExpect(header("X-Request-Id", "REQ_agent_test"))
                .andExpect(jsonPath("$.order_id").value("order-1"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_type": "DISPUTE",
                                  "dispute_type": "NON_RECEIPT",
                                  "risk_level": "HIGH",
                                  "potential_dispute": true,
                                  "missing_slots": [],
                                  "title": "签收争议",
                                  "normalized_description": "物流签收但用户未收到"
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        IntakeAnalysis result =
                client.analyze(
                        new CreateCaseCommand(
                                "order-1",
                                null,
                                "user-1",
                                "merchant-1",
                                "物流签收但未收到",
                                List.of(),
                                "WEB"),
                        "TRACE_agent_test",
                        "REQ_agent_test");

        assertThat(result.caseType()).isEqualTo("DISPUTE");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.potentialDispute()).isTrue();
        server.verify();
    }

}
