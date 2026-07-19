package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GraphCommandClientConfigurationTest {

    private static final String MODE = "app.agent-run-v2.graph-client.mode=SHADOW";
    private static final String HTTPS_ENDPOINT =
            "app.agent-run-v2.graph-client.base-uri=https://python-agent-service:18000";

    @Test
    void disabledModeCreatesNoGraphExecutionBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(GraphCommandClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AgentPlatformContractCodec.class);
                    assertThat(context).doesNotHaveBean(AgentGraphCommandClient.class);
                    assertThat(context).doesNotHaveBean(AgentRunExecutionGateway.class);
                });
    }

    @Test
    void shadowModeCreatesOnlyTheSyntheticExecutionPathWithAllDependencies() {
        shadowRunner(GraphCommandHttpTransport.TransportSecurity.MUTUAL_TLS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentPlatformContractCodec.class);
                    assertThat(context).hasSingleBean(AgentGraphCommandClient.class);
                    assertThat(context).hasSingleBean(AgentRunExecutionGateway.class);
                });
    }

    @Test
    void shadowModeFailsClosedWhenAnyAuthorityDependencyIsMissing() {
        for (Class<?> missing : List.of(
                GraphEnvelopeSigningKeyResolver.class,
                GraphCommandHttpTransport.class,
                GraphStreamVisibilityPolicy.class,
                AgentGraphReconciliationClient.class,
                AgentRunV2StreamStore.class,
                AgentRunReconciledFinalStore.class)) {
            shadowRunner(GraphCommandHttpTransport.TransportSecurity.MUTUAL_TLS, missing)
                    .run(context -> {
                        assertThat(context).as("missing %s", missing.getSimpleName()).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageContaining(missing.getName());
                    });
        }
    }

    @Test
    void httpsShadowRejectsAnUnverifiedTransport() {
        shadowRunner(GraphCommandHttpTransport.TransportSecurity.UNVERIFIED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("transport security");
                });
    }

    @Test
    void plaintextIsRestrictedToAnExplicitLocalOrTestProfile() {
        ApplicationContextRunner plaintext = shadowRunner(
                        GraphCommandHttpTransport.TransportSecurity.LOCAL_PLAINTEXT)
                .withPropertyValues(
                        "app.agent-run-v2.graph-client.base-uri=http://127.0.0.1:18000",
                        "app.agent-run-v2.graph-client.allow-plaintext-transport=true");

        plaintext.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("local or test profiles");
        });
        plaintext.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentRunExecutionGateway.class);
                });
    }

    @Test
    void shadowGatewayCannotBackTheFormalAgentRunWriter() {
        shadowRunner(GraphCommandHttpTransport.TransportSecurity.MUTUAL_TLS)
                .withPropertyValues(
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.scheduler-mode=DETECTOR")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("cannot back the formal AgentRun writer");
                });
    }

    private static ApplicationContextRunner shadowRunner(
            GraphCommandHttpTransport.TransportSecurity security,
            Class<?>... omitted) {
        Set<Class<?>> missing = Set.of(omitted);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(GraphCommandClientConfiguration.class)
                .withPropertyValues(MODE, HTTPS_ENDPOINT)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        if (!missing.contains(GraphEnvelopeSigningKeyResolver.class)) {
            runner = runner.withBean(
                    GraphEnvelopeSigningKeyResolver.class,
                    () -> mock(GraphEnvelopeSigningKeyResolver.class));
        }
        if (!missing.contains(GraphCommandHttpTransport.class)) {
            runner = runner.withBean(
                    GraphCommandHttpTransport.class, () -> transport(security));
        }
        if (!missing.contains(GraphStreamVisibilityPolicy.class)) {
            runner = runner.withBean(
                    GraphStreamVisibilityPolicy.class,
                    () -> ignored -> Map.of("node", Set.of("field")));
        }
        if (!missing.contains(AgentGraphReconciliationClient.class)) {
            runner = runner.withBean(
                    AgentGraphReconciliationClient.class,
                    () -> (request, cancellationToken) -> null);
        }
        if (!missing.contains(AgentRunV2StreamStore.class)) {
            runner = runner.withBean(
                    AgentRunV2StreamStore.class, () -> mock(AgentRunV2StreamStore.class));
        }
        if (!missing.contains(AgentRunReconciledFinalStore.class)) {
            runner = runner.withBean(
                    AgentRunReconciledFinalStore.class,
                    () -> mock(AgentRunReconciledFinalStore.class));
        }
        return runner;
    }

    private static GraphCommandHttpTransport transport(
            GraphCommandHttpTransport.TransportSecurity security) {
        GraphCommandHttpTransport transport = mock(GraphCommandHttpTransport.class);
        when(transport.transportSecurity()).thenReturn(security);
        return transport;
    }
}
