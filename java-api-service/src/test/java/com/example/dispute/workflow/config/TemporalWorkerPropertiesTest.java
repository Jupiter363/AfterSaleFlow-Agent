package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.config.TemporalWorkerProperties.ControlRegistrationScope;
import com.example.dispute.workflow.config.TemporalWorkerProperties.VersioningMode;
import com.example.dispute.workflow.config.TemporalWorkerProperties.WorkerRole;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Configuration;

class TemporalWorkerPropertiesTest {

    @Test
    void bindsAValidatedWorkerDeploymentAndBoundedQueueCapacities() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "app.temporal.worker.enabled=true",
                        "app.temporal.worker.role=AGENT",
                        "app.temporal.worker.versioning-mode=DEPLOYMENT",
                        "app.temporal.worker.deployment-name=after-sale-agent",
                        "app.temporal.worker.build-id=git-81d2969a",
                        "app.temporal.worker.agent-execution.max-concurrent-activities=96")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(TemporalWorkerProperties.class);
                            TemporalWorkerProperties properties =
                                    context.getBean(TemporalWorkerProperties.class);
                            assertThat(properties.enabled()).isTrue();
                            assertThat(properties.role()).isEqualTo(WorkerRole.AGENT);
                            assertThat(properties.controlRegistrationScope())
                                    .isEqualTo(ControlRegistrationScope.FULL);
                            assertThat(properties.versioningMode())
                                    .isEqualTo(VersioningMode.DEPLOYMENT);
                            assertThat(properties.deploymentName())
                                    .isEqualTo("after-sale-agent");
                            assertThat(properties.agentExecution().maxConcurrentActivities())
                                    .isEqualTo(96);
                            assertThat(properties.caseControl().workflowPollers()).isEqualTo(4);
                        });
    }

    @Test
    void bindsCaseProcessRecoveryOnlyScopeOnlyForControlWorkersAndRejectsUnknownValues() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "app.temporal.worker.enabled=true",
                        "app.temporal.worker.versioning-mode=BUILD_ID",
                        "app.temporal.worker.control-registration-scope=CASE_PROCESS_RECOVERY_ONLY");

        runner.withPropertyValues("app.temporal.worker.role=CONTROL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TemporalWorkerProperties.class)
                                    .controlRegistrationScope())
                            .isEqualTo(ControlRegistrationScope.CASE_PROCESS_RECOVERY_ONLY);
                });

        runner.withPropertyValues("app.temporal.worker.role=AGENT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(
                                    "restricted controlRegistrationScope requires CONTROL role");
                });

        runner.withPropertyValues(
                        "app.temporal.worker.role=CONTROL",
                        "app.temporal.worker.control-registration-scope=UNKNOWN")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsApiAsANonWorkerProcessRoleAndRejectsWorkerActivation() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(PropertiesConfiguration.class)
                        .withPropertyValues("app.temporal.worker.role=API");

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    TemporalWorkerProperties properties =
                            context.getBean(TemporalWorkerProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.role()).isEqualTo(WorkerRole.API);
                });

        runner.withPropertyValues("app.temporal.worker.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining(
                                            "API process role cannot enable a Temporal worker");
                        });
    }

    @Test
    void rejectsWorkflowPollerCountsThatTheTemporalSdkWouldSilentlyRewrite() {
        assertThatThrownBy(() -> new TemporalWorkerProperties.QueueCapacity(8, 8, 1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowPollers must be between 2 and 64");
    }

    @Test
    void controlWorkerProfileRequiresLegacyBuildIdRouting() throws IOException {
        var profile =
                new YamlPropertySourceLoader()
                        .load(
                                "control-worker",
                                new ClassPathResource("application-control-worker.yml"))
                        .getFirst();

        assertThat(profile.getProperty("app.temporal.worker.enabled")).isEqualTo(true);
        assertThat(profile.getProperty("app.temporal.worker.role")).isEqualTo("CONTROL");
        assertThat(profile.getProperty("app.temporal.worker.versioning-mode"))
                .isEqualTo("BUILD_ID");
        assertThat(profile.getProperty("app.temporal.worker.deployment-name"))
                .isEqualTo("after-sale-control");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TemporalWorkerProperties.class)
    static class PropertiesConfiguration {}
}
