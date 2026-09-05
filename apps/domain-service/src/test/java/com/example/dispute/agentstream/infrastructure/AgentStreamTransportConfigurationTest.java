package com.example.dispute.agentstream.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.dispute.agentstream.application.AgentRunExecutionDescriptor;
import com.example.dispute.agentstream.application.AgentStreamTransportException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class AgentStreamTransportConfigurationTest {
    private final AppProperties app = mock(AppProperties.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AgentStreamTransportConfiguration.class)
            .withBean(AppProperties.class, () -> app)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AgentNdjsonStreamClient.class);

    @Test
    void defaultsRetainSystemTransportAndInjectThatExactClient() {
        when(app.agent()).thenReturn(new AppProperties.Integration("http://127.0.0.1:18000", "test", 3000));
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var client = ctx.getBean("agentStreamHttpClient", HttpClient.class);
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(ReflectionTestUtils.getField(ctx.getBean(AgentNdjsonStreamClient.class), "httpClient"))
                    .isSameAs(client);
        });
    }

    @Test
    void explicitMtlsBindsMaterialAndInjectsValidatedClient() {
        when(app.agent()).thenReturn(new AppProperties.Integration("https://graph.example.test:8443", "test", 3000));
        HttpClient validated = mock(HttpClient.class);
        try (var factory = mockStatic(TrustedGraphTransportFactory.class)) {
            factory.when(() -> TrustedGraphTransportFactory.createHttpClient(any(), any()))
                    .thenAnswer(call -> {
                        GraphTlsClientMaterial material = call.getArgument(0);
                        assertThat(material.keyStorePath()).isEqualTo(Path.of("client.p12").toAbsolutePath());
                        assertThat((char[]) ReflectionTestUtils.invokeMethod(material, "copyKeyStorePassword"))
                                .containsExactly("key-test".toCharArray());
                        assertThat((char[]) ReflectionTestUtils.invokeMethod(material, "copyTrustStorePassword"))
                                .containsExactly("trust-test".toCharArray());
                        assertThat(call.<Duration>getArgument(1)).isEqualTo(Duration.ofSeconds(2));
                        return validated;
                    });
            context.withPropertyValues("app.agent-stream.tls.mode=MUTUAL_TLS",
                    "app.agent-stream.tls.key-store-path=" + Path.of("client.p12").toAbsolutePath(),
                    "app.agent-stream.tls.trust-store-path=" + Path.of("trust.p12").toAbsolutePath(),
                    "app.agent-stream.tls.key-store-password=key-test",
                    "app.agent-stream.tls.trust-store-password=trust-test").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ReflectionTestUtils.getField(ctx.getBean(AgentNdjsonStreamClient.class), "httpClient"))
                        .isSameAs(validated);
                var props = ctx.getBean(AgentStreamTransportConfiguration.TlsProperties.class);
                assertThat(props.keyStorePassword()).containsOnly('\0');
                assertThat(props.trustStorePassword()).containsOnly('\0');
            });
            factory.verify(() -> TrustedGraphTransportFactory.createHttpClient(any(), any()), times(1));
        }
    }

    @Test
    void missingMaterialPlaintextUnknownModeAndIgnoredMaterialFailClosed() {
        when(app.agent()).thenReturn(new AppProperties.Integration("https://graph.example.test", "test", 3000));
        context.withPropertyValues("app.agent-stream.tls.mode=MUTUAL_TLS")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("app.agent-stream.tls.mode=UNKNOWN")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("app.agent-stream.tls.trust-store-password=must-not-ignore")
                .run(ctx -> assertThat(ctx).hasFailed());
        when(app.agent()).thenReturn(new AppProperties.Integration("http://graph.example.test", "test", 3000));
        context.withPropertyValues("app.agent-stream.tls.mode=MUTUAL_TLS")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void handshakeFailureNeverFallsBackOrEmitsOutput() throws Exception {
        when(app.agent()).thenReturn(new AppProperties.Integration("https://graph.example.test:8443", "test", 3000));
        HttpClient secured = mock(HttpClient.class);
        when(secured.send(any(), any())).thenThrow(new SSLHandshakeException("untrusted peer"));
        var stream = new AgentNdjsonStreamClient(app, new ObjectMapper(), secured);
        var run = new AgentRunExecutionDescriptor("run-1", "case-1", "review-1", "REVIEW", "review_copilot",
                "/internal/agents/review-copilot/query/stream", "{}", "trace-1", "request-1", Set.of(), 0L);
        assertThatThrownBy(() -> stream.stream(run, ignored -> { throw new AssertionError("no frame expected"); }))
                .isInstanceOf(AgentStreamTransportException.class).hasCauseInstanceOf(SSLHandshakeException.class);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(secured, times(1)).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://graph.example.test:8443/internal/agents/review-copilot/query/stream");
        assertThat(request.getValue().headers().firstValue("X-Agent-Run-Id")).contains("run-1");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
    }
}
