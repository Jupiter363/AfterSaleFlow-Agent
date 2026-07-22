package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.config.AppProperties;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflow;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.config.TemporalWorkerProperties.QueueCapacity;
import com.example.dispute.workflow.config.TemporalWorkerProperties.VersioningMode;
import com.example.dispute.workflow.config.TemporalWorkerProperties.WorkerRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TemporalWorkerConfigurationTest {

    private static final String LEGACY_EVIDENCE_WINDOW = "legacy-evidence-window";

    @Test
    void controlRolePollsOnlyControlRoomAndToolQueues() {
        TemporalWorkerProperties properties = properties(WorkerRole.CONTROL);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            WorkerFactory factory = createFactory(environment, properties);
            try {
                assertThat(factory.isStarted()).isTrue();
                assertThat(factory.tryGetWorker(CASE_CONTROL)).isNotNull();
                assertThat(factory.tryGetWorker(ROOM_CONTROL)).isNotNull();
                assertThat(factory.tryGetWorker(NOTIFICATION_AND_TOOLS)).isNotNull();
                assertThat(factory.tryGetWorker(LEGACY_EVIDENCE_WINDOW)).isNotNull();
                assertThat(factory.tryGetWorker(AGENT_EXECUTION)).isNull();
                assertProbe(environment, CASE_CONTROL, WorkerRole.CONTROL);
                assertProbe(environment, ROOM_CONTROL, WorkerRole.CONTROL);
                assertProbe(environment, NOTIFICATION_AND_TOOLS, WorkerRole.CONTROL);
                assertProbe(environment, LEGACY_EVIDENCE_WINDOW, WorkerRole.CONTROL);
            } finally {
                shutdown(factory);
            }
        }
    }

    @Test
    void controlWorkerRejectsUnversionedRoutingBeforeResolvingAuthorityDependencies() {
        TemporalWorkerProperties properties = properties(WorkerRole.CONTROL, VersioningMode.NONE);
        org.springframework.beans.factory.ObjectProvider<IntakeChildBridgeReadPort>
                intakeChildBridgeReadPortProvider = mockProvider(IntakeChildBridgeReadPort.class);
        AppProperties appProperties = mock(AppProperties.class);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(
                            () ->
                                    configuration.temporalControlWorkerFactory(
                                            environment.getWorkflowClient(),
                                            appProperties,
                                            properties,
                                            new TemporalWorkerOptionsFactory(properties),
                                            mock(EvidenceWindowActivitiesAdapter.class),
                                            mock(CaseProcessLedgerActivitiesImpl.class),
                                            mock(ProcessProjectionActivitiesImpl.class),
                                            intakeChildBridgeReadPortProvider))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }

        verifyNoInteractions(intakeChildBridgeReadPortProvider);
    }

    @Test
    void agentRolePollsOnlyTheAgentExecutionQueue() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            WorkerFactory factory = createFactory(environment, properties);
            try {
                assertThat(factory.isStarted()).isTrue();
                assertThat(factory.tryGetWorker(AGENT_EXECUTION)).isNotNull();
                assertThat(factory.tryGetWorker(CASE_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(ROOM_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(NOTIFICATION_AND_TOOLS)).isNull();
                assertThat(factory.tryGetWorker(LEGACY_EVIDENCE_WINDOW)).isNull();
                assertProbe(environment, AGENT_EXECUTION, WorkerRole.AGENT);
            } finally {
                shutdown(factory);
            }
        }
    }

    @Test
    void rejectsLegacyQueueThatCollidesWithAProtocolQueue() {
        TemporalWorkerProperties properties = properties(WorkerRole.CONTROL);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            assertThatThrownBy(
                            () ->
                                    createFactory(
                                            environment,
                                            properties,
                                            CASE_CONTROL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be distinct");
        }
    }

    @Test
    void enabledAgentRunWorkerRejectsUnversionedRoutingBeforeResolvingDependencies() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        AgentRunV2Properties v2Properties = enabledAgentRunProperties();
        org.springframework.beans.factory.ObjectProvider<AgentRunLedger> ledgerProvider =
                mockProvider(AgentRunLedger.class);
        org.springframework.beans.factory.ObjectProvider<AgentRunExecutionGateway>
                executionGatewayProvider = mockProvider(AgentRunExecutionGateway.class);
        org.springframework.beans.factory.ObjectProvider<AgentRunFinalizationGateway>
                finalizationGatewayProvider = mockProvider(AgentRunFinalizationGateway.class);
        org.springframework.beans.factory.ObjectProvider<IntakeSyntheticWorkerRegistration>
                syntheticRegistrationProvider = mockProvider(IntakeSyntheticWorkerRegistration.class);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            v2Properties,
                            disabledIntakeSelection(),
                            syntheticRegistrationProvider,
                            ledgerProvider,
                            executionGatewayProvider,
                            finalizationGatewayProvider))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }

        verifyNoInteractions(
                syntheticRegistrationProvider,
                ledgerProvider,
                executionGatewayProvider,
                finalizationGatewayProvider);
    }

    @Test
    void enabledAgentRunWorkerStartsWithBuildIdVersioningAndPreservesRoleIsolation() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            WorkerFactory factory = configuration.temporalAgentWorkerFactory(
                    environment.getWorkflowClient(),
                    properties,
                    new TemporalWorkerOptionsFactory(properties),
                    enabledAgentRunProperties(),
                    disabledIntakeSelection(),
                    mockProvider(IntakeSyntheticWorkerRegistration.class),
                    provider(mock(AgentRunLedger.class)),
                    provider(mock(AgentRunExecutionGateway.class)),
                    provider(mock(AgentRunFinalizationGateway.class)));
            try {
                assertThat(factory.isStarted()).isTrue();
                assertThat(factory.tryGetWorker(AGENT_EXECUTION)).isNotNull();
                assertThat(factory.tryGetWorker(CASE_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(ROOM_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(NOTIFICATION_AND_TOOLS)).isNull();
                assertThat(factory.tryGetWorker(LEGACY_EVIDENCE_WINDOW)).isNull();
            } finally {
                shutdown(factory);
            }
        }
    }

    @Test
    void v2AgentWorkerFailsClosedWhenExecutionGatewayIsMissing() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);
        AgentRunV2Properties v2Properties = enabledAgentRunProperties();

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            v2Properties,
                            disabledIntakeSelection(),
                            mockProvider(IntakeSyntheticWorkerRegistration.class),
                            provider(mock(AgentRunLedger.class)),
                            mockProvider(AgentRunExecutionGateway.class),
                            provider(mock(AgentRunFinalizationGateway.class))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one AgentRunExecutionGateway");
        }
    }

    @Test
    void disabledAndPartialSyntheticSelectionDoNotResolveTheRegistrationProvider() {
        for (IntakeEpochSelectionProperties selection :
                new IntakeEpochSelectionProperties[] {
                    disabledIntakeSelection(), partialIntakeSelection()
                }) {
            TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
            org.springframework.beans.factory.ObjectProvider<IntakeSyntheticWorkerRegistration>
                    syntheticRegistrationProvider =
                            mockProvider(IntakeSyntheticWorkerRegistration.class);

            try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
                TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
                WorkerFactory factory = configuration.temporalAgentWorkerFactory(
                        environment.getWorkflowClient(),
                        properties,
                        new TemporalWorkerOptionsFactory(properties),
                        disabledAgentRunProperties(),
                        selection,
                        syntheticRegistrationProvider,
                        mockProvider(AgentRunLedger.class),
                        mockProvider(AgentRunExecutionGateway.class),
                        mockProvider(AgentRunFinalizationGateway.class));
                try {
                    assertThat(factory.isStarted()).isTrue();
                } finally {
                    shutdown(factory);
                }
            }

            verifyNoInteractions(syntheticRegistrationProvider);
        }
    }

    @Test
    void enabledSyntheticSelectionRequiresExactlyOneRegistration() {
        assertSyntheticRegistrationRejected(streamProvider());
        assertSyntheticRegistrationRejected(
                streamProvider(syntheticRegistration(), syntheticRegistration()));
    }

    @Test
    void enabledSyntheticSelectionRejectsUnversionedWorkerBeforeResolvingRegistration() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT, VersioningMode.NONE);
        org.springframework.beans.factory.ObjectProvider<IntakeSyntheticWorkerRegistration>
                syntheticRegistrationProvider = mockProvider(IntakeSyntheticWorkerRegistration.class);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            disabledAgentRunProperties(),
                            enabledIntakeSelection(),
                            syntheticRegistrationProvider,
                            mockProvider(AgentRunLedger.class),
                            mockProvider(AgentRunExecutionGateway.class),
                            mockProvider(AgentRunFinalizationGateway.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Signed synthetic Intake requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }

        verifyNoInteractions(syntheticRegistrationProvider);
    }

    @Test
    void enabledSyntheticWorkerStartsVersionedAndPreservesRoleIsolation() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            WorkerFactory factory = configuration.temporalAgentWorkerFactory(
                    environment.getWorkflowClient(),
                    properties,
                    new TemporalWorkerOptionsFactory(properties),
                    disabledAgentRunProperties(),
                    enabledIntakeSelection(),
                    streamProvider(syntheticRegistration()),
                    mockProvider(AgentRunLedger.class),
                    mockProvider(AgentRunExecutionGateway.class),
                    mockProvider(AgentRunFinalizationGateway.class));
            try {
                assertThat(factory.isStarted()).isTrue();
                assertThat(factory.tryGetWorker(AGENT_EXECUTION)).isNotNull();
                assertThat(factory.tryGetWorker(CASE_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(ROOM_CONTROL)).isNull();
                assertThat(factory.tryGetWorker(NOTIFICATION_AND_TOOLS)).isNull();
                assertThat(factory.tryGetWorker(LEGACY_EVIDENCE_WINDOW)).isNull();
                assertProbe(
                        environment,
                        AGENT_EXECUTION,
                        WorkerRole.AGENT,
                        VersioningMode.BUILD_ID);
            } finally {
                shutdown(factory);
            }
        }
    }

    @Test
    void enabledSyntheticSelectionRejectsAgentRunV2BeforeResolvingDependencies() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);
        org.springframework.beans.factory.ObjectProvider<IntakeSyntheticWorkerRegistration>
                syntheticRegistrationProvider = mockProvider(IntakeSyntheticWorkerRegistration.class);
        org.springframework.beans.factory.ObjectProvider<AgentRunLedger> ledgerProvider =
                mockProvider(AgentRunLedger.class);
        org.springframework.beans.factory.ObjectProvider<AgentRunExecutionGateway>
                executionGatewayProvider = mockProvider(AgentRunExecutionGateway.class);
        org.springframework.beans.factory.ObjectProvider<AgentRunFinalizationGateway>
                finalizationGatewayProvider = mockProvider(AgentRunFinalizationGateway.class);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            enabledAgentRunProperties(),
                            enabledIntakeSelection(),
                            syntheticRegistrationProvider,
                            ledgerProvider,
                            executionGatewayProvider,
                            finalizationGatewayProvider))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Signed synthetic Intake cannot share AGENT_EXECUTION with AgentRunV2");
        }

        verifyNoInteractions(
                syntheticRegistrationProvider,
                ledgerProvider,
                executionGatewayProvider,
                finalizationGatewayProvider);
    }

    private static void assertSyntheticRegistrationRejected(
            org.springframework.beans.factory.ObjectProvider<IntakeSyntheticWorkerRegistration>
                    syntheticRegistrationProvider) {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            disabledAgentRunProperties(),
                            enabledIntakeSelection(),
                            syntheticRegistrationProvider,
                            mockProvider(AgentRunLedger.class),
                            mockProvider(AgentRunExecutionGateway.class),
                            mockProvider(AgentRunFinalizationGateway.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "exactly one IntakeSyntheticWorkerRegistration");
        }
    }

    private static WorkerFactory createFactory(
            TestWorkflowEnvironment environment, TemporalWorkerProperties properties) {
        return createFactory(environment, properties, LEGACY_EVIDENCE_WINDOW);
    }

    private static WorkerFactory createFactory(
            TestWorkflowEnvironment environment,
            TemporalWorkerProperties properties,
            String legacyTaskQueue) {
        AppProperties appProperties = mock(AppProperties.class);
        when(appProperties.temporal())
                .thenReturn(
                        AppProperties.Temporal.defaults(
                                "localhost:7233", "default", legacyTaskQueue));
        TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
        TemporalWorkerOptionsFactory optionsFactory =
                new TemporalWorkerOptionsFactory(properties);
        if (properties.role() == WorkerRole.AGENT) {
            return configuration.temporalAgentWorkerFactory(
                    environment.getWorkflowClient(),
                    properties,
                    optionsFactory,
                    disabledAgentRunProperties(),
                    disabledIntakeSelection(),
                    mockProvider(IntakeSyntheticWorkerRegistration.class),
                    mockProvider(AgentRunLedger.class),
                    mockProvider(AgentRunExecutionGateway.class),
                    mockProvider(AgentRunFinalizationGateway.class));
        }
        return configuration.temporalControlWorkerFactory(
                environment.getWorkflowClient(),
                appProperties,
                properties,
                optionsFactory,
                mock(EvidenceWindowActivitiesAdapter.class),
                mock(CaseProcessLedgerActivitiesImpl.class),
                mock(ProcessProjectionActivitiesImpl.class),
                provider(mock(IntakeChildBridgeReadPort.class)));
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> mockProvider(
            Class<T> type) {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> streamProvider(
            T... values) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(values));
        return provider;
    }

    private static TemporalWorkerProperties properties(WorkerRole role) {
        return properties(
                role,
                role == WorkerRole.CONTROL
                        ? VersioningMode.BUILD_ID
                        : VersioningMode.NONE);
    }

    private static TemporalWorkerProperties properties(
            WorkerRole role, VersioningMode versioningMode) {
        QueueCapacity control = new QueueCapacity(64, 32, 2, 2, 0);
        QueueCapacity room = new QueueCapacity(64, 16, 2, 2, 0);
        QueueCapacity agent = new QueueCapacity(8, 32, 2, 2, 0);
        QueueCapacity tools = new QueueCapacity(8, 16, 2, 2, 0);
        return new TemporalWorkerProperties(
                true,
                role,
                versioningMode,
                role == WorkerRole.CONTROL ? "after-sale-control" : "after-sale-agent",
                "test-build",
                256,
                control,
                room,
                agent,
                tools);
    }

    private static AgentRunV2Properties enabledAgentRunProperties() {
        return new AgentRunV2Properties(
                true,
                AgentRunProtocol.V1,
                AgentRunV2Properties.SchedulerMode.OFF,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5));
    }

    private static AgentRunV2Properties disabledAgentRunProperties() {
        return new AgentRunV2Properties(
                false,
                AgentRunProtocol.V1,
                AgentRunV2Properties.SchedulerMode.EXECUTOR,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5));
    }

    private static IntakeEpochSelectionProperties disabledIntakeSelection() {
        return new IntakeEpochSelectionProperties(WriterMode.LEGACY, 0, null, false);
    }

    private static IntakeEpochSelectionProperties partialIntakeSelection() {
        return new IntakeEpochSelectionProperties(WriterMode.SHADOW, 0, null, true);
    }

    private static IntakeEpochSelectionProperties enabledIntakeSelection() {
        return new IntakeEpochSelectionProperties(
                WriterMode.SHADOW, 1, "synthetic.v1", true);
    }

    private static IntakeSyntheticWorkerRegistration syntheticRegistration() {
        return new IntakeSyntheticWorkerRegistration(
                mock(IntakeSignedSyntheticAdmissionPort.class),
                mock(IntakeSnapshotPublicationPort.class),
                mock(IntakeSignedSyntheticGraphExecutionPort.class),
                mock(IntakeSyntheticParityObservationPort.class),
                mock(IntakeSyntheticComparisonLedger.class));
    }

    private static void assertProbe(
            TestWorkflowEnvironment environment, String taskQueue, WorkerRole role) {
        assertProbe(
                environment,
                taskQueue,
                role,
                role == WorkerRole.CONTROL ? VersioningMode.BUILD_ID : VersioningMode.NONE);
    }

    private static void assertProbe(
            TestWorkflowEnvironment environment,
            String taskQueue,
            WorkerRole role,
            VersioningMode versioningMode) {
        TemporalWorkerProbeWorkflow probe =
                environment.getWorkflowClient()
                        .newWorkflowStub(
                                TemporalWorkerProbeWorkflow.class,
                                WorkflowOptions.newBuilder()
                                        .setWorkflowId("worker-probe:" + taskQueue)
                                        .setTaskQueue(taskQueue)
                                        .build());

        TemporalWorkerDescription description = probe.probe();

        assertThat(description.schemaVersion())
                .isEqualTo("temporal-worker-description.v1");
        assertThat(description.role()).isEqualTo(role.name());
        assertThat(description.taskQueue()).isEqualTo(taskQueue);
        assertThat(description.buildId()).isEqualTo("test-build");
        assertThat(description.versioningMode()).isEqualTo(versioningMode.name());
    }

    private static void shutdown(WorkerFactory factory) {
        factory.shutdownNow();
        factory.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
    }
}
