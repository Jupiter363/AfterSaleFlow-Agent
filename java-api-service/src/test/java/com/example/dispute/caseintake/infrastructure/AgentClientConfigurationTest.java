package com.example.dispute.caseintake.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.infrastructure.RestClientHearingAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AgentClientConfigurationTest {

    @Test
    void forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()
            throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/legacy/hearing/analyze",
                exchange -> {
                    upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                    byte[] body =
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
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort())
                            .requestFactory(
                                    AgentClientConfiguration.http1RequestFactory(
                                            Duration.ofSeconds(2)))
                            .defaultHeader("X-Service-Secret", "secret")
                            .build();

            var result =
                    new RestClientHearingAgentClient(client)
                            .analyze(
                                    new ObjectMapper()
                                            .readTree(
                                                    "{\"case_id\":\"CASE_test\"}"),
                                    "TRACE_test",
                                    "REQ_test");

            assertThat(result.manualRequired()).isFalse();
            assertThat(upgradeHeader.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
