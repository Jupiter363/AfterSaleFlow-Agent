package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class IntakeSyntheticShadowConfigurationTest {

    private static final String ENABLED =
            "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled=true";
    private static final String EPOCH_MODE =
            "app.orchestration.intake-epoch-selection.mode=SHADOW";
    private static final String COHORT =
            "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=1";
    private static final String POLICY =
            "app.orchestration.intake-epoch-selection.cohort-policy-version=synthetic.v1";
    private static final String GRAPH_MODE = "app.agent-run-v2.graph-client.mode=SHADOW";
    private static final String GRAPH_ENDPOINT =
            "app.agent-run-v2.graph-client.base-uri=https://python-agent-service:18000";

    @Test
    void disabledModeBuildsNoSyntheticBeanGraph() {
        new ApplicationContextRunner()
                .withUserConfiguration(IntakeSyntheticShadowConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JdbcIntakeSyntheticComparisonLedger.class);
                    assertThat(context).doesNotHaveBean(IntakeSyntheticWorkerRegistration.class);
                    assertThat(context).doesNotHaveBean(SignedSyntheticIntakeDriver.class);
                });
    }

    @Test
    void enabledModeBuildsTheLedgerRegistrationAndSameDriver() {
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JdbcIntakeSyntheticComparisonLedger.class);
            assertThat(context).hasSingleBean(IntakeSyntheticWorkerRegistration.class);
            assertThat(context).hasSingleBean(SignedSyntheticIntakeDriver.class);

            IntakeSyntheticWorkerRegistration registration =
                    context.getBean(IntakeSyntheticWorkerRegistration.class);
            assertThat(context.getBean(SignedSyntheticIntakeDriver.class))
                    .isSameAs(registration.driver());
        });
    }

    @Test
    void enabledModeFailsWhenAnyRequiredPortIsMissing() {
        List<Class<?>> requiredPorts = List.of(
                IntakeSignedSyntheticAdmissionPort.class,
                IntakeSnapshotPublicationPort.class,
                IntakeSignedSyntheticGraphExecutionPort.class,
                IntakeSyntheticParityObservationPort.class);

        for (Class<?> missing : requiredPorts) {
            enabledRunnerWithout(missing).run(context -> {
                assertThat(context).as("missing %s", missing.getSimpleName()).hasFailed();
                assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("exactly one real " + missing.getName());
            });
        }
    }

    @Test
    void enabledModeFailsWhenARequiredPortIsAmbiguousEvenWithAPrimaryCandidate() {
        enabledRunner()
                .withBean(
                        "secondAdmission",
                        IntakeSignedSyntheticAdmissionPort.class,
                        () -> mock(IntakeSignedSyntheticAdmissionPort.class),
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(
                                    "exactly one real "
                                            + IntakeSignedSyntheticAdmissionPort.class.getName());
                });
    }

    @Test
    void enabledFlagRejectsAPartialEpochSelection() {
        runnerWithRequiredBeans()
                .withPropertyValues(ENABLED, EPOCH_MODE, GRAPH_MODE, GRAPH_ENDPOINT)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("complete SHADOW epoch selection");
                });
    }

    @Test
    void enabledFlagRejectsGraphAndAgentRunModeConflicts() {
        runnerWithRequiredBeans()
                .withPropertyValues(ENABLED, EPOCH_MODE, COHORT, POLICY)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Graph client mode SHADOW");
                });

        enabledRunner()
                .withPropertyValues(
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.scheduler-mode=DETECTOR")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("while AgentRunV2 is enabled");
                });
    }

    @Test
    void configurationBeanSignaturesDoNotReferenceFormalIntakeTypes() {
        assertThat(Arrays.stream(IntakeSyntheticShadowConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(
                                org.springframework.context.annotation.Bean.class))
                        .flatMap(IntakeSyntheticShadowConfigurationTest::signatureTypes)
                        .map(Class::getName))
                .noneMatch(name -> name.contains("Formal")
                        || name.endsWith("IntakeRoomActivitiesAdapter"));
    }

    private static Stream<Class<?>> signatureTypes(Method method) {
        return Stream.concat(
                Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes()));
    }

    private static ApplicationContextRunner enabledRunner() {
        return runnerWithRequiredBeans()
                .withPropertyValues(ENABLED, EPOCH_MODE, COHORT, POLICY, GRAPH_MODE, GRAPH_ENDPOINT);
    }

    private static ApplicationContextRunner enabledRunnerWithout(Class<?> missing) {
        ApplicationContextRunner runner = baseRunner()
                .withPropertyValues(ENABLED, EPOCH_MODE, COHORT, POLICY, GRAPH_MODE, GRAPH_ENDPOINT)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class));
        if (missing != IntakeSignedSyntheticAdmissionPort.class) {
            runner = runner.withBean(
                    IntakeSignedSyntheticAdmissionPort.class,
                    () -> mock(IntakeSignedSyntheticAdmissionPort.class));
        }
        if (missing != IntakeSnapshotPublicationPort.class) {
            runner = runner.withBean(
                    IntakeSnapshotPublicationPort.class,
                    () -> mock(IntakeSnapshotPublicationPort.class));
        }
        if (missing != IntakeSignedSyntheticGraphExecutionPort.class) {
            runner = runner.withBean(
                    IntakeSignedSyntheticGraphExecutionPort.class,
                    () -> mock(IntakeSignedSyntheticGraphExecutionPort.class));
        }
        if (missing != IntakeSyntheticParityObservationPort.class) {
            runner = runner.withBean(
                    IntakeSyntheticParityObservationPort.class,
                    () -> mock(IntakeSyntheticParityObservationPort.class));
        }
        return runner;
    }

    private static ApplicationContextRunner runnerWithRequiredBeans() {
        return baseRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(
                        IntakeSignedSyntheticAdmissionPort.class,
                        () -> mock(IntakeSignedSyntheticAdmissionPort.class))
                .withBean(
                        IntakeSnapshotPublicationPort.class,
                        () -> mock(IntakeSnapshotPublicationPort.class))
                .withBean(
                        IntakeSignedSyntheticGraphExecutionPort.class,
                        () -> mock(IntakeSignedSyntheticGraphExecutionPort.class))
                .withBean(
                        IntakeSyntheticParityObservationPort.class,
                        () -> mock(IntakeSyntheticParityObservationPort.class));
    }

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(IntakeSyntheticShadowConfiguration.class);
    }
}
