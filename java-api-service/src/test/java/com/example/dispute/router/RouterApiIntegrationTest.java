package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
class RouterApiIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_router_api")
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
                                + "/dispute_router_api");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceDossierRepository dossierRepository;
    @Autowired private RouteDecisionRepository decisionRepository;
    @Autowired private FlowConclusionRepository conclusionRepository;
    @Autowired private AuditLogRepository auditRepository;

    @BeforeEach
    void seedCases() {
        seedCase("CASE_regularapi", "LOGISTICS_QUERY", null, RiskLevel.LOW, 0);
        seedCase("CASE_ruleapi", "UNSHIPPED_CANCEL", null, RiskLevel.MEDIUM, 1);
        seedCase(
                "CASE_hearingapi",
                "UNSHIPPED_CANCEL",
                "FULFILLMENT_CONFLICT",
                RiskLevel.HIGH,
                1);
    }

    @Test
    void routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies() {
        ResponseEntity<Map> regular = route("CASE_regularapi", "ROUTE_regular_api");
        ResponseEntity<Map> rule = route("CASE_ruleapi", "ROUTE_rule_api");
        ResponseEntity<Map> hearing = route("CASE_hearingapi", "ROUTE_hearing_api");
        ResponseEntity<Map> replay = route("CASE_ruleapi", "ROUTE_rule_api");
        ResponseEntity<Map> conflict = route("CASE_ruleapi", "ROUTE_other_key");

        assertThat(regular.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(regular).get("route_type"))
                .isEqualTo("REGULAR_FULFILLMENT");
        Map<String, Object> regularConclusion =
                (Map<String, Object>) data(regular).get("conclusion");
        assertThat(regularConclusion)
                .containsEntry("requires_remedy_planning", true)
                .containsEntry("requires_human_review", true);

        assertThat(data(rule).get("route_type"))
                .isEqualTo("RULE_BASED_RESOLUTION");
        assertThat(data(rule).get("policy_rule_id"))
                .isEqualTo("POLICY_UNSHIPPED_CANCEL_V1");
        assertThat(((Map<?, ?>) data(rule).get("conclusion")).get("policy_version"))
                .isEqualTo(1);
        assertThat(data(replay).get("id")).isEqualTo(data(rule).get("id"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_CONFLICT");

        assertThat(data(hearing).get("route_type"))
                .isEqualTo("DISPUTE_HEARING");
        assertThat(data(hearing).get("conclusion")).isNull();

        assertThat(decisionRepository.count()).isEqualTo(3);
        assertThat(conclusionRepository.count()).isEqualTo(2);
        assertThat(auditRepository.findAll())
                .extracting(audit -> audit.getAction())
                .contains("ROUTE_DECIDED");

        ResponseEntity<Map> policies =
                restTemplate.exchange(
                        url("/api/v1/policies?scope=UNSHIPPED_CANCEL"),
                        HttpMethod.GET,
                        new HttpEntity<>(actorHeaders(null)),
                        Map.class);
        assertThat(policies.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> policyData =
                (List<Map<String, Object>>) policies.getBody().get("data");
        assertThat(policyData).singleElement()
                .satisfies(
                        policy -> {
                            assertThat(policy.get("rule_code"))
                                    .isEqualTo("UNSHIPPED_CANCEL");
                            assertThat(policy.get("rule_version")).isEqualTo(1);
                        });

        ResponseEntity<Map> allPolicies =
                restTemplate.exchange(
                        url("/api/v1/policies"),
                        HttpMethod.GET,
                        new HttpEntity<>(actorHeaders(null)),
                        Map.class);
        assertThat((List<?>) allPolicies.getBody().get("data")).hasSize(2);
    }

    private void seedCase(
            String caseId,
            String caseType,
            String disputeType,
            RiskLevel riskLevel,
            int evidenceCount) {
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-route-api",
                        "merchant-route-api",
                        "IDEMPOTENCY_" + caseId,
                        caseType,
                        "Routing integration",
                        "Routing integration description",
                        riskLevel,
                        "user-route-api");
        entity.completeIntake(
                disputeType,
                CaseStatus.INTAKE_COMPLETED,
                riskLevel,
                "{}",
                "user-route-api");
        entity.markDossierBuilt("user-route-api");
        caseRepository.saveAndFlush(entity);
        dossierRepository.saveAndFlush(
                EvidenceDossierEntity.firstBuild(
                        "DOSSIER_" + caseId,
                        caseId,
                        "user-route-api",
                        "{\"evidence_count\":"
                                + evidenceCount
                                + ",\"pending_parse_count\":0}",
                        "[]",
                        "[]"));
    }

    private ResponseEntity<Map> route(String caseId, String idempotencyKey) {
        HttpHeaders headers = actorHeaders(idempotencyKey);
        return restTemplate.exchange(
                url("/api/v1/cases/" + caseId + "/route"),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
    }

    private HttpHeaders actorHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user-route-api");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");
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
