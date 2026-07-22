package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticRuntimeSource;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionTrustSet;
import com.example.dispute.workflow.shadow.intake.admission.JdbcIntakeSignedSyntheticAdmissionPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Path;
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
    private static final String RUNTIME_MATERIAL_ENABLED =
            "app.orchestration.intake-synthetic-runtime-material.enabled=true";
    private static final String RUNTIME_MATERIAL_INDEX =
            "app.orchestration.intake-synthetic-runtime-material.manifest-reference-index-path="
                    + Path.of(
                                    System.getProperty("java.io.tmpdir"),
                                    "intake-synthetic-runtime-material-index.json")
                            .toAbsolutePath();

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
    void explicitlyTrustedRuntimeBuildsProductionAdaptersWithoutAFormalSink() {
        productionRuntimeRunner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JdbcIntakeSignedSyntheticAdmissionPort.class);
            assertThat(context).hasSingleBean(JdbcIntakeSyntheticRuntimeSource.class);
            assertThat(context).hasSingleBean(IntakeSyntheticRuntimeSource.class);
            assertThat(context).hasSingleBean(IntakeSnapshotPublicationPort.class);
            assertThat(context).hasSingleBean(IntakeSignedSyntheticGraphExecutionPort.class);
            assertThat(context).hasSingleBean(IntakeSyntheticParityObservationPort.class);
            assertThat(context).hasSingleBean(IntakeSyntheticWorkerRegistration.class);
            assertThat(context).hasSingleBean(IntakeAuthorityWorkerRegistration.class);
        });
    }

    @Test
    void completeRuntimeMaterialStillFailsClosedWithoutAdmissionTrust() {
        productionRuntimeRunner(false).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(
                            "exactly one real "
                                    + IntakeSignedSyntheticAdmissionPort.class.getName());
        });
    }

    @Test
    void signedShadowBuildsAllMaterialPortsFromOneConcreteProviderWithoutAFormalSink() {
        runtimeMaterialRunner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource.class)
                    .hasSingleBean(IntakeSyntheticSnapshotMaterialSource.class)
                    .hasSingleBean(IntakeSyntheticGraphMaterialSource.class)
                    .hasSingleBean(IntakeSyntheticParityMaterialSource.class)
                    .hasSingleBean(IntakeSyntheticRuntimeSource.class);

            Object provider =
                    context.getBean(PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource.class);
            assertThat(context.getBean(IntakeSyntheticSnapshotMaterialSource.class))
                    .isSameAs(provider);
            assertThat(context.getBean(IntakeSyntheticGraphMaterialSource.class))
                    .isSameAs(provider);
            assertThat(context.getBean(IntakeSyntheticParityMaterialSource.class))
                    .isSameAs(provider);
        });
    }

    @Test
    void runtimeMaterialProviderIsAbsentUnlessExplicitlyEnabled() {
        runtimeMaterialRunner(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .doesNotHaveBean(PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource.class)
                    .doesNotHaveBean(IntakeSyntheticSnapshotMaterialSource.class)
                    .doesNotHaveBean(IntakeSyntheticGraphMaterialSource.class)
                    .doesNotHaveBean(IntakeSyntheticParityMaterialSource.class)
                    .doesNotHaveBean(IntakeSyntheticRuntimeSource.class);
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

    private static ApplicationContextRunner productionRuntimeRunner(boolean trusted) {
        ApplicationContextRunner runner = baseRunner()
                .withPropertyValues(ENABLED, EPOCH_MODE, COHORT, POLICY, GRAPH_MODE, GRAPH_ENDPOINT)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(
                        IntakeSyntheticSnapshotMaterialSource.class,
                        () -> mock(IntakeSyntheticSnapshotMaterialSource.class))
                .withBean(
                        IntakeSyntheticGraphMaterialSource.class,
                        () -> mock(IntakeSyntheticGraphMaterialSource.class))
                .withBean(
                        IntakeSyntheticParityMaterialSource.class,
                        () -> mock(IntakeSyntheticParityMaterialSource.class))
                .withBean(
                        IntakeImmutablePayloadPublisher.class,
                        () -> mock(IntakeImmutablePayloadPublisher.class))
                .withBean(
                        IntakeGraphBindingStore.class,
                        () -> mock(IntakeGraphBindingStore.class))
                .withBean(
                        AgentGraphCommandClient.class,
                        () -> mock(AgentGraphCommandClient.class))
                .withBean(
                        AgentGraphReconciliationClient.class,
                        () -> mock(AgentGraphReconciliationClient.class))
                .withBean(
                        IntakeChildBridgeReadPort.class,
                        () -> mock(IntakeChildBridgeReadPort.class));
        if (trusted) {
            runner = runner.withBean(
                    IntakeSyntheticAdmissionTrustSet.class,
                    () -> mock(IntakeSyntheticAdmissionTrustSet.class));
        }
        return runner;
    }

    private static ApplicationContextRunner runtimeMaterialRunner(boolean enabled) {
        ApplicationContextRunner runner = runnerWithRequiredBeans()
                .withPropertyValues(ENABLED, EPOCH_MODE, COHORT, POLICY, GRAPH_MODE, GRAPH_ENDPOINT)
                .withBean(
                        IntakeSyntheticAdmissionTrustSet.class,
                        () -> mock(IntakeSyntheticAdmissionTrustSet.class))
                .withBean(
                        IntakeRuntimeMaterialManifestReferenceSource.class,
                        () -> mock(IntakeRuntimeMaterialManifestReferenceSource.class))
                .withBean(
                        IntakeRuntimeMaterialObjectStore.class,
                        () -> mock(IntakeRuntimeMaterialObjectStore.class));
        if (enabled) {
            runner = runner.withPropertyValues(RUNTIME_MATERIAL_ENABLED, RUNTIME_MATERIAL_INDEX);
        }
        return runner;
    }

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(IntakeSyntheticShadowConfiguration.class);
    }
}
