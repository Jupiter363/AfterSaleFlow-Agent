package com.example.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.TraceIdFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispute;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.flyway.enabled=false",
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false"
        })
@SuppressWarnings({"rawtypes", "unchecked"})
class DisputeApplicationTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void actuatorHealthIsPublicAndCorrelated() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_HEADER))
                .matches("TRACE_[0-9a-f]{32}");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.REQUEST_HEADER))
                .matches("REQ_[0-9a-f]{32}");
    }

    @Test
    void openApiDocumentIsPublicAndIdentifiesTheService() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/v3/api-docs", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> info = (Map<?, ?>) response.getBody().get("info");
        assertThat(info.get("title")).isEqualTo("Order Fulfillment Dispute API");
        assertThat(info.get("version")).isEqualTo("v1");
    }
}
