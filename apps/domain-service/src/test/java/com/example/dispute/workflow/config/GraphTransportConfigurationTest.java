package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.dispute.workflow.infrastructure.agent.GraphReadinessCoordinator;
import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.mockito.MockedStatic;

class GraphTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GraphTransportConfiguration.class)
            .withPropertyValues(
                    "app.agent-run-v2.graph-client.mode=SHADOW",
                    "app.agent-run-v2.graph-client.request-timeout=PT10M");

    @Test
    void plaintextRequiresBothTheExplicitFlagAndALocalOrTestProfile() {
        ApplicationContextRunner plaintext = runner.withPropertyValues(
                "app.agent-run-v2.graph-client.base-uri=http://127.0.0.1:18000",
                "app.agent-run-v2.graph-client.allow-plaintext-transport=true");

        plaintext.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("local or test profile");
        });
        plaintext.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GraphTransportBundle bundle = context.getBean(GraphTransportBundle.class);
                    assertThat(bundle.transportProof().mode())
                            .isEqualTo(GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT);
                    assertThat(bundle.commandTransport().transportProof())
                            .isSameAs(bundle.reconciliationTransport().transportProof())
                            .isSameAs(bundle.transportProof());
                });
    }

    @Test
    void httpsFailsClosedWithoutCompleteClientIdentityAndTrustMaterial() {
        runner.withPropertyValues(
                        "app.agent-run-v2.graph-client.base-uri=https://python-agent-service:8000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("complete PKCS12");
                });
    }

    @Test
    void httpsReadinessFailurePreventsTransportBundlePublication() {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        Duration connectTimeout = Duration.ofSeconds(2);
        Duration readinessTimeout = Duration.ofSeconds(15);
        GraphReadinessCoordinator.Settings readinessSettings =
                new GraphReadinessCoordinator.Settings(
                        Duration.ofSeconds(20), readinessTimeout, "SHADOW");
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);
        doThrow(new IllegalStateException("Graph readiness handshake failed"))
                .when(bundle)
                .verifyReadiness(readinessTimeout, "SHADOW");

        try (MockedStatic<TrustedGraphTransportFactory> factory =
                mockStatic(TrustedGraphTransportFactory.class)) {
            factory.when(() -> TrustedGraphTransportFactory.createForEndpoint(
                            any(GraphTlsClientMaterial.class),
                            eq(connectTimeout),
                            eq(baseUri),
                            eq(readinessSettings)))
                    .thenReturn(bundle);

            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("agent-worker"))
                    .withPropertyValues(
                            "app.agent-run-v2.graph-client.base-uri=" + baseUri,
                            "app.agent-run-v2.graph-client.tls.key-store-path="
                                    + Path.of("test-client.p12").toAbsolutePath(),
                            "app.agent-run-v2.graph-client.tls.key-store-password=changeit",
                            "app.agent-run-v2.graph-client.tls.trust-store-path="
                                    + Path.of("test-trust.p12").toAbsolutePath(),
                            "app.agent-run-v2.graph-client.tls.trust-store-password=changeit",
                            "app.agent-run-v2.graph-client.tls.connect-timeout=PT2S")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessage("Graph readiness handshake failed");
                    });

            factory.verify(() -> TrustedGraphTransportFactory.createForEndpoint(
                    any(GraphTlsClientMaterial.class),
                    eq(connectTimeout),
                    eq(baseUri),
                    eq(readinessSettings)));
            verify(bundle).verifyReadiness(readinessTimeout, "SHADOW");
            verify(bundle).close();
        }
    }

    @Test
    void httpsAgentWorkerCreatesContinuousReadinessOnlyAfterStartupVerification() {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        Duration connectTimeout = Duration.ofSeconds(2);
        GraphReadinessCoordinator.Settings readinessSettings =
                new GraphReadinessCoordinator.Settings(
                        Duration.ofSeconds(20), Duration.ofSeconds(15), "SHADOW");
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);

        try (MockedStatic<TrustedGraphTransportFactory> factory =
                mockStatic(TrustedGraphTransportFactory.class)) {
            factory.when(() -> TrustedGraphTransportFactory.createForEndpoint(
                            any(GraphTlsClientMaterial.class),
                            eq(connectTimeout),
                            eq(baseUri),
                            eq(readinessSettings)))
                    .thenReturn(bundle);

            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("agent-worker"))
                    .withPropertyValues(
                            "app.agent-run-v2.graph-client.base-uri=" + baseUri,
                            "app.agent-run-v2.graph-client.tls.key-store-path="
                                    + Path.of("test-client.p12").toAbsolutePath(),
                            "app.agent-run-v2.graph-client.tls.key-store-password=changeit",
                            "app.agent-run-v2.graph-client.tls.trust-store-path="
                                    + Path.of("test-trust.p12").toAbsolutePath(),
                            "app.agent-run-v2.graph-client.tls.trust-store-password=changeit",
                            "app.agent-run-v2.graph-client.tls.connect-timeout=PT2S")
                    .run(context -> assertThat(context).hasNotFailed());

            verify(bundle).verifyReadiness(Duration.ofSeconds(15), "SHADOW");
            verify(bundle, never()).bindWorkerPolling(any(), any());
        }
    }

    @Test
    void graphTransportConfigurationHasNoTemporalWorkerDependency() {
        assertThat(Arrays.stream(GraphTransportConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("graphTransportBundle"))
                        .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                        .map(Class::getName))
                .noneMatch(name -> name.equals("io.temporal.worker.WorkerFactory")
                        || name.equals("org.springframework.beans.factory.ObjectProvider"));
    }

    @Test
    void httpsApiAndControlProfilesDoNotProbeTheWorkerGraphReadinessBoundary() {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        Duration connectTimeout = Duration.ofSeconds(2);
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);

        try (MockedStatic<TrustedGraphTransportFactory> factory =
                mockStatic(TrustedGraphTransportFactory.class)) {
            factory.when(() -> TrustedGraphTransportFactory.createForEndpoint(
                            any(GraphTlsClientMaterial.class), eq(connectTimeout), eq(baseUri)))
                    .thenReturn(bundle);

            for (String profile : new String[] {"api", "control-worker"}) {
                runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                        .withPropertyValues(
                                "app.agent-run-v2.graph-client.base-uri=" + baseUri,
                                "app.agent-run-v2.graph-client.tls.key-store-path="
                                        + Path.of("test-client.p12").toAbsolutePath(),
                                "app.agent-run-v2.graph-client.tls.key-store-password=changeit",
                                "app.agent-run-v2.graph-client.tls.trust-store-path="
                                        + Path.of("test-trust.p12").toAbsolutePath(),
                                "app.agent-run-v2.graph-client.tls.trust-store-password=changeit",
                                "app.agent-run-v2.graph-client.tls.connect-timeout=PT2S")
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(GraphTransportBundle.class)).isSameAs(bundle);
                        });
            }

            verify(bundle, never()).verifyReadiness(any(), any());
            verify(bundle, never()).bindWorkerPolling(any(), any());
        }
    }

    @Test
    void continuousReadinessPropertiesRejectEveryOpenBoundary() {
        assertThat(new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(5), Duration.ofMillis(100))
                        .settings("SHADOW")
                        .freshness())
                .isEqualTo(Duration.ofMillis(5100));
        assertThat(new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(25), Duration.ofSeconds(15))
                        .settings("TARGET_E2E_CANDIDATE")
                        .freshness())
                .isEqualTo(Duration.ofSeconds(40));
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(5).minusNanos(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(25).plusNanos(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(5), Duration.ofMillis(100).minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(25), Duration.ofSeconds(15).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledModeCreatesNoTransportBundle() {
        new ApplicationContextRunner()
                .withUserConfiguration(GraphTransportConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GraphTransportBundle.class);
                });
    }
}
