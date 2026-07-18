package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationFactsProvider;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationGateway;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class AgentRunV2ApplicationContextTest {

    private static final String[] EXPLICIT_SHADOW_PROPERTIES = {
        "app.agent-run-v2.enabled=true",
        "app.agent-run-v2.protocol-default=V1",
        "app.agent-run-v2.scheduler-mode=OFF"
    };

    @Test
    void defaultOffContextDoesNotRequireFinalizationAssembly() {
        gatewayRunner()
                .withBean(
                        AgentRunV2FinalizationFactsProvider.class,
                        () -> mock(AgentRunV2FinalizationFactsProvider.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            AgentRunV2Properties properties =
                                    context.getBean(AgentRunV2Properties.class);
                            assertThat(properties.enabled()).isFalse();
                            assertThat(properties.protocolDefault()).isEqualTo(AgentRunProtocol.V1);
                            assertThat(properties.schedulerMode())
                                    .isEqualTo(SchedulerMode.EXECUTOR);
                            assertThat(context)
                                    .doesNotHaveBean(AgentRunV2FinalizationGateway.class);
                            assertThat(context).doesNotHaveBean(AgentRunFinalizationGateway.class);
                        });
    }

    @Test
    void shadowContextDoesNotAssembleFinalizerWithoutFactsProvider() {
        gatewayRunner()
                .withPropertyValues(EXPLICIT_SHADOW_PROPERTIES)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            AgentRunV2Properties properties =
                                    context.getBean(AgentRunV2Properties.class);
                            assertThat(properties.enabled()).isTrue();
                            assertThat(properties.protocolDefault()).isEqualTo(AgentRunProtocol.V1);
                            assertThat(properties.schedulerMode()).isEqualTo(SchedulerMode.OFF);
                            assertThat(context)
                                    .doesNotHaveBean(AgentRunV2FinalizationGateway.class)
                                    .doesNotHaveBean(AgentRunFinalizationGateway.class);
                            assertThat(
                                            context.getBeanProvider(
                                                            AgentRunFinalizationGateway.class)
                                                    .getIfUnique())
                                    .isNull();
                        });
    }

    @Test
    void shadowContextAssemblesFinalizerOnlyWithFactsAndRequiredCollaborators() {
        gatewayRunner()
                .withPropertyValues(EXPLICIT_SHADOW_PROPERTIES)
                .withBean(
                        AgentRunV2FinalizationFactsProvider.class,
                        () -> mock(AgentRunV2FinalizationFactsProvider.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(AgentRunV2FinalizationGateway.class)
                                    .hasSingleBean(AgentRunFinalizationGateway.class);
                            assertThat(context.getBean(AgentRunFinalizationGateway.class))
                                    .isSameAs(context.getBean(AgentRunV2FinalizationGateway.class));
                        });
    }

    @Test
    void factsProviderCannotMaskAMissingRequiredCollaborator() {
        gatewayRunnerWithoutCommitter()
                .withPropertyValues(EXPLICIT_SHADOW_PROPERTIES)
                .withBean(
                        AgentRunV2FinalizationFactsProvider.class,
                        () -> mock(AgentRunV2FinalizationFactsProvider.class))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                                    .hasMessageContaining(
                                            AgentRunFormalResultCommitter.class.getName());
                        });
    }

    @Test
    void applicationContextRejectsV2WithTheLegacyExecutor() {
        gatewayRunner()
                .withBean(
                        AgentRunV2FinalizationFactsProvider.class,
                        () -> mock(AgentRunV2FinalizationFactsProvider.class))
                .withPropertyValues(
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.protocol-default=V2",
                        "app.agent-run-v2.scheduler-mode=EXECUTOR")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining(
                                            "legacy scheduler cannot execute V2 runs");
                        });
    }

    private static ApplicationContextRunner gatewayRunner() {
        return gatewayRunnerWithoutCommitter()
                .withBean(
                        AgentRunFormalResultCommitter.class,
                        () -> mock(AgentRunFormalResultCommitter.class));
    }

    private static ApplicationContextRunner gatewayRunnerWithoutCommitter() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        PropertiesConfiguration.class, FinalizationGatewayScanConfiguration.class)
                .withBean(AgentRunLedger.class, () -> mock(AgentRunLedger.class))
                .withBean(
                        AgentRunV2ManifestFactory.class,
                        () -> mock(AgentRunV2ManifestFactory.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRunV2Properties.class)
    static class PropertiesConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = AgentRunV2FinalizationGateway.class,
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = AgentRunV2FinalizationGateway.class))
    static class FinalizationGatewayScanConfiguration {}
}
