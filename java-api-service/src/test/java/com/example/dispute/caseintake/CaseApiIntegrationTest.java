package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.caseintake.application.AgentServiceClient;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false"
        })
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class CaseApiIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_case_api")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_case_api");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AuditLogRepository auditLogRepository;
    @MockitoBean private AgentServiceClient agentServiceClient;

    @Test
    void createQueryAndReplayArePersistentAuditedAndIdempotent() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "DISPUTE",
                                "NON_RECEIPT",
                                RiskLevel.HIGH,
                                true,
                                List.of(),
                                "物流签收争议",
                                "用户与物流记录存在冲突"));
        HttpHeaders headers = actorHeaders("user-case-api", "USER", "idem-case-api");
        Map<String, Object> request =
                Map.of(
                        "order_id", "order-case-api",
                        "user_id", "user-case-api",
                        "merchant_id", "merchant-case-api",
                        "description", "物流签收但本人没有收到",
                        "channel", "WEB");

        ResponseEntity<Map> created =
                restTemplate.exchange(
                        url("/api/v1/cases"),
                        HttpMethod.POST,
                        new HttpEntity<>(request, headers),
                        Map.class);
        ResponseEntity<Map> replayed =
                restTemplate.exchange(
                        url("/api/v1/cases"),
                        HttpMethod.POST,
                        new HttpEntity<>(request, headers),
                        Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> createdData = data(created);
        assertThat(data(replayed).get("id")).isEqualTo(createdData.get("id"));
        assertThat(createdData)
                .containsEntry("case_status", "INTAKE_COMPLETED")
                .containsEntry("potential_dispute", true)
                .containsEntry("agent_degraded", false);

        ResponseEntity<Map> queried =
                restTemplate.exchange(
                        url("/api/v1/cases/" + createdData.get("id")),
                        HttpMethod.GET,
                        new HttpEntity<>(actorHeaders("user-case-api", "USER", null)),
                        Map.class);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(queried).get("order_id")).isEqualTo("order-case-api");
        assertThat(auditLogRepository.countByCaseId(createdData.get("id").toString()))
                .isEqualTo(1);
    }

    @Test
    void missingOrderIsAcceptedForSlotCompletion() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "REGULAR_FULFILLMENT",
                                null,
                                RiskLevel.LOW,
                                false,
                                List.of("ORDER_ID"),
                                "查询物流",
                                "需要补充订单号"));
        Map<String, Object> request =
                Map.of(
                        "user_id", "user-missing-api",
                        "merchant_id", "merchant-missing-api",
                        "description", "帮我查一下包裹",
                        "channel", "WEB");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/api/v1/cases"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                request,
                                actorHeaders(
                                        "user-missing-api",
                                        "USER",
                                        "idem-missing-api")),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(response))
                .containsEntry("case_status", "WAITING_SLOT_COMPLETION");
        assertThat((List<String>) data(response).get("missing_slots"))
                .containsExactly("ORDER_ID");
    }

    private HttpHeaders actorHeaders(String actorId, String role, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, actorId);
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, role);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
