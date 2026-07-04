package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.TraceIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.flyway.enabled=false",
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "dispute.scheduling.enabled=false"
        })
@Import(SecurityConfigurationTest.TestEndpointConfiguration.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class SecurityConfigurationTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private Filter springSecurityFilterChain;
    @Autowired private ApplicationContext applicationContext;

    @Test
    void anonymousRequestReceivesUnifiedUnauthorizedResponse() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/api/disputes/security-test"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .containsEntry("success", false)
                .containsEntry("code", "UNAUTHORIZED");
        assertThat(response.getBody().get("request_id").toString()).startsWith("REQ_");
        assertThat(response.getBody().get("trace_id").toString()).startsWith("TRACE_");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void validActorHeadersAuthenticateKnownRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "PLATFORM_REVIEWER");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        url("/api/reviews/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ok");
    }

    @Test
    void actorHeadersCannotCallInternalServiceRoutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "PLATFORM_REVIEWER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/internal/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void serviceIdentityCanCallInternalServiceRoutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(
                HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER,
                "trusted-platform-adapter");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        url("/internal/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void partyRolesCannotEnterReviewApis() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user_001");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/api/reviews/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownRoleCannotAuthenticate() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "attacker");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "SUPERUSER");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        url("/api/disputes/security-test"),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    void asyncSseDispatchBypassesAuthenticationGateAfterResponseHasStarted()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET", "/api/disputes/CASE_security_test/events");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedApplication = new AtomicBoolean(false);
        FilterChain terminalChain =
                (servletRequest, servletResponse) ->
                        reachedApplication.set(true);

        springSecurityFilterChain.doFilter(request, response, terminalChain);

        assertThat(reachedApplication).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void schedulingCanBeDisabledForFocusedSecurityContexts() {
        assertThat(
                        applicationContext.getBeansOfType(
                                ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointConfiguration {

        @Bean
        SecurityTestController securityTestController() {
            return new SecurityTestController();
        }
    }

    @RestController
    static class SecurityTestController {

        @GetMapping({
            "/api/disputes/security-test",
            "/api/reviews/security-test",
            "/internal/security-test"
        })
        String protectedEndpoint() {
            return "ok";
        }
    }
}
