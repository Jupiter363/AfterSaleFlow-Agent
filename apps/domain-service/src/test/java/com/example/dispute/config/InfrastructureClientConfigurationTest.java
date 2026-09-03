package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.OpenTelemetryTraceConfiguration;
import com.example.dispute.workflow.observability.TemporalPayloadCodecConfiguration;
import com.example.dispute.workflow.observability.TemporalTraceContextPropagator;
import com.example.dispute.workflow.observability.TemporalTracingClientInterceptor;
import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

class InfrastructureClientConfigurationTest {

    private static final Duration OVER_LIMIT_HEALTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration MAXIMUM_EXPECTED_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void connectsExactSharedTemporalStubBeforePublicationAndClosesItOnce() throws Exception {
        AppProperties properties = properties();
        WorkflowServiceStubs serviceStubs = mock(WorkflowServiceStubs.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowServiceStubsOptions connectedOptions =
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget("localhost:7233")
                        .setHealthCheckTimeout(OVER_LIMIT_HEALTH_TIMEOUT)
                        .build();
        when(serviceStubs.getOptions()).thenReturn(connectedOptions);

        AtomicBoolean connected = new AtomicBoolean();
        doAnswer(invocation -> {
                    connected.set(true);
                    return null;
                })
                .when(serviceStubs)
                .connect(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);

        assertLifecycleAnnotations();

        try (MockedStatic<WorkflowServiceStubs> serviceStubsFactory =
                        mockStatic(WorkflowServiceStubs.class);
                MockedStatic<WorkflowClient> workflowClientFactory =
                        mockStatic(WorkflowClient.class)) {
            serviceStubsFactory
                    .when(() -> WorkflowServiceStubs.newServiceStubs(
                            any(WorkflowServiceStubsOptions.class)))
                    .thenReturn(serviceStubs);
            workflowClientFactory
                    .when(() -> WorkflowClient.newInstance(
                            same(serviceStubs), any(WorkflowClientOptions.class)))
                    .thenAnswer(invocation -> {
                        assertThat(connected).isTrue();
                        return workflowClient;
                    });

            contextRunner(properties)
                    .run(context -> {
                        assertThat(context).hasSingleBean(MinioClient.class);
                        assertThat(context).hasSingleBean(WorkflowServiceStubs.class);
                        assertThat(context).hasSingleBean(WorkflowClient.class);
                        assertThat(context.getBean(WorkflowServiceStubs.class))
                                .isSameAs(serviceStubs)
                                .isSameAs(context.getBean(WorkflowServiceStubs.class));
                        assertThat(context.getBean(WorkflowClient.class))
                                .isSameAs(workflowClient)
                                .isSameAs(context.getBean(WorkflowClient.class));
                    });

            ArgumentCaptor<WorkflowServiceStubsOptions> serviceOptions =
                    ArgumentCaptor.forClass(WorkflowServiceStubsOptions.class);
            serviceStubsFactory.verify(
                    () -> WorkflowServiceStubs.newServiceStubs(serviceOptions.capture()), times(1));
            assertThat(serviceOptions.getValue().getTarget()).isEqualTo("localhost:7233");
            assertThat(serviceOptions.getValue().getHealthCheckTimeout())
                    .isEqualTo(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);
            assertThat(serviceOptions.getValue().getSystemInfoTimeout())
                    .isEqualTo(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);

            ArgumentCaptor<WorkflowClientOptions> clientOptions =
                    ArgumentCaptor.forClass(WorkflowClientOptions.class);
            workflowClientFactory.verify(
                    () -> WorkflowClient.newInstance(same(serviceStubs), clientOptions.capture()),
                    times(1));
            assertThat(clientOptions.getValue().getNamespace()).isEqualTo("default");
            assertThat(clientOptions.getValue().getContextPropagators())
                    .singleElement()
                    .isInstanceOf(TemporalTraceContextPropagator.class);
            assertThat(clientOptions.getValue().getInterceptors())
                    .singleElement()
                    .isInstanceOf(TemporalTracingClientInterceptor.class);
        }

        verify(serviceStubs, times(1)).connect(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);
        verify(serviceStubs, times(1)).shutdown();
        verify(serviceStubs, never()).shutdownNow();
        verifyNoInteractions(workflowClient);
    }

    @Test
    void failedTemporalConnectPreservesOriginalAndSuppressesCleanupFailure() {
        AppProperties properties = properties();
        WorkflowServiceStubs serviceStubs = mock(WorkflowServiceStubs.class);
        WorkflowServiceStubsOptions connectedOptions =
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget("localhost:7233")
                        .setHealthCheckTimeout(OVER_LIMIT_HEALTH_TIMEOUT)
                        .build();
        when(serviceStubs.getOptions()).thenReturn(connectedOptions);

        IllegalStateException connectFailure = new IllegalStateException("connect failed");
        IllegalArgumentException cleanupFailure = new IllegalArgumentException("cleanup failed");
        doThrow(connectFailure)
                .when(serviceStubs)
                .connect(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);
        doThrow(cleanupFailure).when(serviceStubs).shutdownNow();

        try (MockedStatic<WorkflowServiceStubs> serviceStubsFactory =
                        mockStatic(WorkflowServiceStubs.class);
                MockedStatic<WorkflowClient> workflowClientFactory =
                        mockStatic(WorkflowClient.class)) {
            serviceStubsFactory
                    .when(() -> WorkflowServiceStubs.newServiceStubs(
                            any(WorkflowServiceStubsOptions.class)))
                    .thenReturn(serviceStubs);

            contextRunner(properties)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCause(connectFailure);
                    });

            serviceStubsFactory.verify(
                    () -> WorkflowServiceStubs.newServiceStubs(
                            any(WorkflowServiceStubsOptions.class)),
                    times(1));
            workflowClientFactory.verifyNoInteractions();
        }

        verify(serviceStubs, times(1)).connect(MAXIMUM_EXPECTED_CONNECT_TIMEOUT);
        verify(serviceStubs, times(1)).shutdownNow();
        verify(serviceStubs, never()).shutdown();
        assertThat(connectFailure.getSuppressed()).containsExactly(cleanupFailure);
    }

    private static void assertLifecycleAnnotations() throws NoSuchMethodException {
        Method factoryMethod = InfrastructureClientConfiguration.class.getDeclaredMethod(
                "workflowServiceStubs", AppProperties.class);
        Bean bean = factoryMethod.getAnnotation(Bean.class);
        Lazy lazy = factoryMethod.getAnnotation(Lazy.class);
        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEqualTo("shutdown");
        assertThat(lazy).isNotNull();
        assertThat(lazy.value()).isFalse();
    }

    private static ApplicationContextRunner contextRunner(AppProperties properties) {
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withUserConfiguration(
                        InfrastructureClientConfiguration.class,
                        OpenTelemetryTraceConfiguration.class,
                        TemporalPayloadCodecConfiguration.class,
                        TemporalTraceContextPropagator.class,
                        TemporalTracingClientInterceptor.class);
    }

    private static AppProperties properties() {
        return new AppProperties(
                "test",
                new AppProperties.Security("java-secret"),
                new AppProperties.Integration(
                        "http://agent:8000", "agent-secret", 120000),
                new AppProperties.Integration(
                        "http://ocr:8010", "ocr-secret", 120000),
                AppProperties.Temporal.defaults(
                        "localhost:7233", "default", "legacy-evidence-window"),
                new AppProperties.Minio(
                        "http://localhost:19000",
                        "minio-user",
                        "minio-password",
                        "evidence-original",
                        "evidence-desensitized"),
                new AppProperties.Elasticsearch("http://localhost:19200"),
                new AppProperties.Feature(true, true, true, true, true, true, true),
                new AppProperties.Logging(true, true));
    }
}
