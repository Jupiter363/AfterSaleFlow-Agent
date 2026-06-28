package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.ocr.service-secret=test-ocr-callback-secret"
        })
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class EvidenceApiIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_evidence_api")
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
                                + "/dispute_evidence_api");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @MockitoBean private EvidenceStorage storage;
    @MockitoBean private OcrTaskClient ocrTaskClient;
    @MockitoBean private EvidenceSearchIndexer searchIndexer;

    @BeforeEach
    void seedCase() {
        if (!caseRepository.existsById("CASE_evidenceapi")) {
            FulfillmentCaseEntity entity =
                    FulfillmentCaseEntity.create(
                            "CASE_evidenceapi",
                            "order-evidence-api",
                            null,
                            "user-evidence-api",
                            "merchant-evidence-api",
                            "idem-evidence-api",
                            "DISPUTE",
                            "物流争议",
                            "签收状态存在争议",
                            RiskLevel.HIGH,
                            "user-evidence-api");
            entity.completeIntake(
                    "NON_RECEIPT",
                    CaseStatus.INTAKE_COMPLETED,
                    RiskLevel.HIGH,
                    """
                    {"potentialDispute":true,"missingSlots":[],"agentDegraded":false,\
"analyzedAt":"2026-06-28T00:00:00Z"}
                    """,
                    "user-evidence-api");
            caseRepository.saveAndFlush(entity);
        }
    }

    @Test
    void uploadsMetadataAndBuildsQueryableVersionedDossierWhenOcrIsDown() {
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidenceapi/evidence/proof.png"));
        doThrow(new IllegalStateException("ocr down"))
                .when(ocrTaskClient)
                .createParseTask(any());
        LinkedMultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add(
                "file",
                new ByteArrayResource(
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        }) {
                    @Override
                    public String getFilename() {
                        return "proof.png";
                    }
                });
        multipart.add("evidence_type", "LOGISTICS_PROOF");
        multipart.add("source_type", "USER_UPLOAD");
        multipart.add("visibility", "PARTIES");
        HttpHeaders headers = actorHeaders(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map> uploaded =
                restTemplate.exchange(
                        url("/api/v1/cases/CASE_evidenceapi/evidences"),
                        HttpMethod.POST,
                        new HttpEntity<>(multipart, headers),
                        Map.class);

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> evidence = data(uploaded);
        assertThat(evidence)
                .containsEntry("content_type", "image/png")
                .containsEntry("parse_status", "PENDING")
                .containsEntry("desensitized", false);
        assertThat(evidenceRepository.count()).isEqualTo(1);

        ResponseEntity<Map> callback =
                restTemplate.exchange(
                        url(
                                "/internal/v1/evidences/"
                                        + evidence.get("id")
                                        + "/parse-result"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "status", "SUCCEEDED",
                                        "text", "签收证明解析文本",
                                        "metadata", Map.of("engine", "paddleocr")),
                                systemHeaders()),
                        Map.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        evidenceRepository
                                .findById(evidence.get("id").toString())
                                .orElseThrow()
                                .getParsedText())
                .isEqualTo("签收证明解析文本");

        ResponseEntity<Map> built =
                restTemplate.exchange(
                        url("/api/v1/cases/CASE_evidenceapi/dossier/build"),
                        HttpMethod.POST,
                        new HttpEntity<>(actorHeaders(MediaType.APPLICATION_JSON)),
                        Map.class);
        ResponseEntity<Map> queried =
                restTemplate.exchange(
                        url("/api/v1/cases/CASE_evidenceapi/dossier"),
                        HttpMethod.GET,
                        new HttpEntity<>(actorHeaders(MediaType.APPLICATION_JSON)),
                        Map.class);

        assertThat(built.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(built).get("version")).isEqualTo(1);
        assertThat(((Map<?, ?>) data(built).get("summary")).get("evidence_count"))
                .isEqualTo(1);
        assertThat((java.util.List<?>) data(built).get("matrix")).hasSize(1);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(queried).get("version")).isEqualTo(1);
    }

    private HttpHeaders actorHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user-evidence-api");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");
        return headers;
    }

    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "ocr-parser-service");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "SYSTEM");
        headers.set("X-Service-Secret", "test-ocr-callback-secret");
        return headers;
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
