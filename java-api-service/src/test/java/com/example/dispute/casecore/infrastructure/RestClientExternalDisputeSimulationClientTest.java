package com.example.dispute.casecore.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RestClientExternalDisputeSimulationClientTest {

    @Test
    void sendsAndReadsPythonAgentContractInSnakeCase() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-001",
                                  "order_reference": "ORDER-001",
                                  "after_sales_reference": "AFTER-001",
                                  "logistics_reference": "LOG-001",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "External import test case",
                                  "description": "Python Agent generated an external dispute case.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .defaultHeader("X-Service-Secret", "secret")
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();

            var result =
                    new RestClientExternalDisputeSimulationClient(client)
                            .simulate(
                                    new SimulateExternalImportCommand(
                                            1,
                                            "watch-after-sale-dispute",
                                            RiskLevel.MEDIUM,
                                            ActorRole.USER,
                                            "user-local",
                                            "merchant-local",
                                            "external-import-batch-001"),
                                    "TRACE_test",
                                    "REQ_test");

            assertThat(requestBody.get()).contains("\"initiator_role_hint\":\"USER\"");
            assertThat(requestBody.get()).contains("\"count\":1");
            assertThat(requestBody.get()).contains("\"current_actor_id\":\"user-local\"");
            assertThat(requestBody.get())
                    .contains("\"simulation_batch_id\":\"external-import-batch-001\"");
            assertThat(requestBody.get()).doesNotContain("initiatorRoleHint");
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().externalCaseReference()).isEqualTo("SIM-001");
            assertThat(result.getFirst().orderReference()).isEqualTo("ORDER-001");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPythonAgentResponsesThatContainMoreThanOneItem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-001",
                                  "order_reference": "ORDER-001",
                                  "after_sales_reference": "AFTER-001",
                                  "logistics_reference": "LOG-001",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "First external import",
                                  "description": "First generated external dispute.",
                                  "risk_level": "MEDIUM"
                                },
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-002",
                                  "order_reference": "ORDER-002",
                                  "after_sales_reference": "AFTER-002",
                                  "logistics_reference": "LOG-002",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "Second external import",
                                  "description": "Second generated external dispute.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();
            RestClientExternalDisputeSimulationClient simulationClient =
                    new RestClientExternalDisputeSimulationClient(client);

            assertThatThrownBy(
                            () ->
                                    simulationClient.simulate(
                                            new SimulateExternalImportCommand(
                                                    1,
                                                    "watch dispute",
                                                    RiskLevel.MEDIUM,
                                                    ActorRole.USER,
                                                    "user-local",
                                                    "merchant-local",
                                                    "external-import-batch-002"),
                                            "TRACE_test",
                                            "REQ_test"))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("exactly one");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-INVALID-PARTY",
                                  "order_reference": "ORDER-INVALID-PARTY",
                                  "after_sales_reference": "AFTER-INVALID-PARTY",
                                  "logistics_reference": "LOG-INVALID-PARTY",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-1",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "Invalid external import",
                                  "description": "The simulator returned an unsupported merchant.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();
            RestClientExternalDisputeSimulationClient simulationClient =
                    new RestClientExternalDisputeSimulationClient(client);

            assertThatThrownBy(
                            () ->
                                    simulationClient.simulate(
                                            new SimulateExternalImportCommand(
                                                    1,
                                                    "watch dispute",
                                                    RiskLevel.MEDIUM,
                                                    ActorRole.USER,
                                                    "user-local",
                                                    "merchant-local",
                                                    "external-import-batch-invalid-party"),
                                            "TRACE_test",
                                            "REQ_test"))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("invalid item");
        } finally {
            server.stop(0);
        }
    }

    private static JdkClientHttpRequestFactory http1RequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
