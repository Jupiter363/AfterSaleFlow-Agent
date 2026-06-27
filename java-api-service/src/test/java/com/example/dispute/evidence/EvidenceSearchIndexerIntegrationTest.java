package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.evidence.application.EvidenceView;
import com.example.dispute.evidence.infrastructure.RestClientEvidenceSearchIndexer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class EvidenceSearchIndexerIntegrationTest {

    @Container
    private static final GenericContainer<?> ELASTICSEARCH =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "docker.elastic.co/elasticsearch/elasticsearch:8.13.4"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.enrollment.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
                    .withExposedPorts(9200)
                    .waitingFor(
                            Wait.forHttp("/_cluster/health")
                                    .forPort(9200)
                                    .forStatusCode(200)
                                    .withStartupTimeout(java.time.Duration.ofMinutes(2)));

    @Test
    void indexesEvidenceMetadataIntoSearchableEvidenceIndex() throws Exception {
        String endpoint =
                "http://"
                        + ELASTICSEARCH.getHost()
                        + ":"
                        + ELASTICSEARCH.getMappedPort(9200);
        RestClientEvidenceSearchIndexer indexer =
                new RestClientEvidenceSearchIndexer(
                        RestClient.builder().baseUrl(endpoint).build());
        EvidenceView evidence =
                new EvidenceView(
                        "EVIDENCE_search",
                        "CASE_search",
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "evidence-original",
                        "CASE_search/EVIDENCE_search/proof.txt",
                        "hash-search",
                        "proof.txt",
                        "text/plain",
                        4,
                        "PENDING",
                        "PARTIES",
                        false,
                        null,
                        null);

        indexer.indexMetadata(evidence);
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint
                                                + "/evidence_index/_doc/EVIDENCE_search?refresh=true"))
                        .GET()
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"found\":true")
                .contains("\"case_id\":\"CASE_search\"")
                .contains("\"file_hash\":\"hash-search\"");
    }
}
