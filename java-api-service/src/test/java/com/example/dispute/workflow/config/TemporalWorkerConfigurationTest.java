package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.config.AppProperties;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities;
import com.example.dispute.workflow.activity.system.IntakeInfrastructurePreparationWorkflowImpl;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.IntakeInfrastructurePreparationResult;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflow;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflowImpl;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.config.TemporalWorkerProperties.QueueCapacity;
import com.example.dispute.workflow.config.TemporalWorkerProperties.VersioningMode;
import com.example.dispute.workflow.config.TemporalWorkerProperties.WorkerRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.targete2e.TargetE2eAgentDeploymentBinding;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;

class TemporalWorkerConfigurationTest {

    private static final String LEGACY_EVIDENCE_WINDOW = "legacy-evidence-window";

    @Test
    void startupProbeLifecycleIsRoleExactBoundedAndFailClosed() {
        assertSuccessfulStartupProbe(
                properties(WorkerRole.CONTROL), List.of(CASE_CONTROL, ROOM_CONTROL));
        assertSuccessfulStartupProbe(
                properties(WorkerRole.AGENT), List.of(AGENT_EXECUTION));
        assertSuccessfulStartupProbe(disabledApiProperties(), List.of());

        assertProbeFailureCleansUnpublishedFactory(false);
        assertProbeFailureCleansUnpublishedFactory(true);
        assertStartFailurePreservesCleanupFailure();
        assertRegistrationFailurePreservesSameCleanupFailureIdentity();
        assertEagerSingletonWorkerBeansAndDisabledGuard();
        assertBusinessNeutralProbeShape();
    }

    @Test
    void graphEnabledAgentBindsContinuousReadinessAfterTemporalProofsAndFailsClosed() {
        assertGraphTransportBundleResolutionIsExactAndConditional();
        assertGraphPollingBindingOrderAndFactoryIdentity();
        assertGraphPollingBindingFailureCleansFactory(false);
        assertGraphPollingBindingFailureCleansFactory(true);
        assertGraphDisabledAndLocalTransportsDoNotBindPolling();
        assertGraphDependencyDirectionIsWorkerToBundleOnly();
        assertIntakePreparationUsesBoundGraphBundle();
    }

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
                                            disabledIntakeSelection(),
                                            new TemporalWorkerOptionsFactory(properties),
                                            mock(EvidenceWindowActivitiesAdapter.class),
                                            mock(CaseProcessLedgerActivitiesImpl.class),
                                            mock(ProcessProjectionActivitiesImpl.class),
                                            streamProvider(),
                                            intakeChildBridgeReadPortProvider,
                                            streamProvider()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }

        verifyNoInteractions(intakeChildBridgeReadPortProvider);
    }

    @Test
    void signedSyntheticControlWorkerRequiresOneAdmissionBackedAuthorityRegistration() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.CONTROL, VersioningMode.BUILD_ID);
        org.springframework.beans.factory.ObjectProvider<IntakeChildBridgeReadPort>
                intakeChildBridgeReadPortProvider = mockProvider(IntakeChildBridgeReadPort.class);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalControlWorkerFactory(
                            environment.getWorkflowClient(),
                            mock(AppProperties.class),
                            properties,
                            enabledIntakeSelection(),
                            new TemporalWorkerOptionsFactory(properties),
                            mock(EvidenceWindowActivitiesAdapter.class),
                            mock(CaseProcessLedgerActivitiesImpl.class),
                            mock(ProcessProjectionActivitiesImpl.class),
                            streamProvider(),
                            intakeChildBridgeReadPortProvider,
                            streamProvider()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "exactly one admission-backed IntakeAuthorityWorkerRegistration");
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
                             disabledGraphClientProperties(),
                             disabledIntakeSelection(),
                            syntheticRegistrationProvider,
                             ledgerProvider,
                             executionGatewayProvider,
                             finalizationGatewayProvider,
                             mockProvider(AgentRunFinalizationFailureRecorder.class),
                             mockProvider(TargetE2eAgentDeploymentBinding.class)))
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
                     disabledGraphClientProperties(),
                     disabledIntakeSelection(),
                    mockProvider(IntakeSyntheticWorkerRegistration.class),
                     provider(mock(AgentRunLedger.class)),
                     provider(mock(AgentRunExecutionGateway.class)),
                     provider(mock(AgentRunFinalizationGateway.class)),
                     provider(mock(AgentRunFinalizationFailureRecorder.class)),
                     mockProvider(TargetE2eAgentDeploymentBinding.class));
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
    void targetAgentWorkerRequiresTheExactRegisteredDeploymentBinding() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);
        TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            assertThatThrownBy(
                            () ->
                                    configuration.temporalAgentWorkerFactory(
                                            environment.getWorkflowClient(),
                                            properties,
                                            new TemporalWorkerOptionsFactory(properties),
                                            enabledAgentRunProperties(),
                                            targetGraphClientProperties(),
                                            disabledIntakeSelection(),
                                            mockProvider(IntakeSyntheticWorkerRegistration.class),
                                            provider(mock(AgentRunLedger.class)),
                                             provider(mock(AgentRunExecutionGateway.class)),
                                             provider(mock(AgentRunFinalizationGateway.class)),
                                             provider(mock(AgentRunFinalizationFailureRecorder.class)),
                                             mockProvider(
                                                    TargetE2eAgentDeploymentBinding.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("target AGENT deployment binding");

            assertThatThrownBy(
                            () ->
                                    configuration.temporalAgentWorkerFactory(
                                            environment.getWorkflowClient(),
                                            properties,
                                            new TemporalWorkerOptionsFactory(properties),
                                            enabledAgentRunProperties(),
                                            targetGraphClientProperties(),
                                            disabledIntakeSelection(),
                                            mockProvider(IntakeSyntheticWorkerRegistration.class),
                                            provider(mock(AgentRunLedger.class)),
                                             provider(mock(AgentRunExecutionGateway.class)),
                                             provider(mock(AgentRunFinalizationGateway.class)),
                                             provider(mock(AgentRunFinalizationFailureRecorder.class)),
                                             provider(targetBinding("wrong-agent-build"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("worker configuration");
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
                             disabledGraphClientProperties(),
                             disabledIntakeSelection(),
                            mockProvider(IntakeSyntheticWorkerRegistration.class),
                             provider(mock(AgentRunLedger.class)),
                             mockProvider(AgentRunExecutionGateway.class),
                             provider(mock(AgentRunFinalizationGateway.class)),
                             provider(mock(AgentRunFinalizationFailureRecorder.class)),
                             mockProvider(TargetE2eAgentDeploymentBinding.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one AgentRunExecutionGateway");
        }
    }

    @Test
    void v2AgentWorkerFailsClosedWhenFinalizationFailureRecorderIsMissingOrAmbiguous() {
        TemporalWorkerProperties properties =
                properties(WorkerRole.AGENT, VersioningMode.BUILD_ID);
        List<org.springframework.beans.factory.ObjectProvider<AgentRunFinalizationFailureRecorder>>
                invalidRecorders = List.of(
                        mockProvider(AgentRunFinalizationFailureRecorder.class),
                        streamProvider(
                                mock(AgentRunFinalizationFailureRecorder.class),
                                mock(AgentRunFinalizationFailureRecorder.class)));

        for (org.springframework.beans.factory.ObjectProvider<AgentRunFinalizationFailureRecorder>
                recorderProvider : invalidRecorders) {
            try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
                TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
                assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                                environment.getWorkflowClient(),
                                properties,
                                new TemporalWorkerOptionsFactory(properties),
                                enabledAgentRunProperties(),
                                disabledGraphClientProperties(),
                                disabledIntakeSelection(),
                                mockProvider(IntakeSyntheticWorkerRegistration.class),
                                provider(mock(AgentRunLedger.class)),
                                provider(mock(AgentRunExecutionGateway.class)),
                                provider(mock(AgentRunFinalizationGateway.class)),
                                recorderProvider,
                                mockProvider(TargetE2eAgentDeploymentBinding.class)))
                        .as("recorder provider %s", recorderProvider)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(
                                "exactly one AgentRunFinalizationFailureRecorder");
            }
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
                         disabledGraphClientProperties(),
                         selection,
                         syntheticRegistrationProvider,
                         mockProvider(AgentRunLedger.class),
                         mockProvider(AgentRunExecutionGateway.class),
                         mockProvider(AgentRunFinalizationGateway.class),
                         mockProvider(AgentRunFinalizationFailureRecorder.class),
                         mockProvider(TargetE2eAgentDeploymentBinding.class));
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
                             disabledGraphClientProperties(),
                             enabledIntakeSelection(),
                             syntheticRegistrationProvider,
                             mockProvider(AgentRunLedger.class),
                             mockProvider(AgentRunExecutionGateway.class),
                             mockProvider(AgentRunFinalizationGateway.class),
                             mockProvider(AgentRunFinalizationFailureRecorder.class),
                             mockProvider(TargetE2eAgentDeploymentBinding.class)))
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
                     disabledGraphClientProperties(),
                     enabledIntakeSelection(),
                     streamProvider(syntheticRegistration()),
                     mockProvider(AgentRunLedger.class),
                     mockProvider(AgentRunExecutionGateway.class),
                     mockProvider(AgentRunFinalizationGateway.class),
                     mockProvider(AgentRunFinalizationFailureRecorder.class),
                     mockProvider(TargetE2eAgentDeploymentBinding.class));
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
                             disabledGraphClientProperties(),
                             enabledIntakeSelection(),
                            syntheticRegistrationProvider,
                             ledgerProvider,
                             executionGatewayProvider,
                             finalizationGatewayProvider,
                             mockProvider(AgentRunFinalizationFailureRecorder.class),
                             mockProvider(TargetE2eAgentDeploymentBinding.class)))
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
                             disabledGraphClientProperties(),
                             enabledIntakeSelection(),
                             syntheticRegistrationProvider,
                             mockProvider(AgentRunLedger.class),
                             mockProvider(AgentRunExecutionGateway.class),
                             mockProvider(AgentRunFinalizationGateway.class),
                             mockProvider(AgentRunFinalizationFailureRecorder.class),
                             mockProvider(TargetE2eAgentDeploymentBinding.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "exactly one IntakeSyntheticWorkerRegistration");
        }
    }

    private static void assertGraphTransportBundleResolutionIsExactAndConditional() {
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);
        org.springframework.beans.factory.ObjectProvider<GraphTransportBundle> exactProvider =
                mockProvider(GraphTransportBundle.class);
        when(exactProvider.getIfUnique()).thenReturn(bundle);

        assertThat(requireGraphTransportBundleForTest(
                        targetGraphClientProperties(), exactProvider))
                .isSameAs(bundle);
        verify(exactProvider, times(1)).getIfUnique();
        verifyNoMoreInteractions(exactProvider);

        org.springframework.beans.factory.ObjectProvider<GraphTransportBundle> disabledProvider =
                mockProvider(GraphTransportBundle.class);
        assertThat(requireGraphTransportBundleForTest(
                        disabledGraphClientProperties(), disabledProvider))
                .isNull();
        verifyNoInteractions(disabledProvider);

        org.springframework.beans.factory.ObjectProvider<GraphTransportBundle> missingProvider =
                mockProvider(GraphTransportBundle.class);
        assertThatThrownBy(() -> requireGraphTransportBundleForTest(
                        targetGraphClientProperties(), missingProvider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Graph-enabled AGENT worker requires exactly one GraphTransportBundle");
        verify(missingProvider, times(1)).getIfUnique();
        verifyNoMoreInteractions(missingProvider);
    }

    private static void assertGraphPollingBindingOrderAndFactoryIdentity() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        WorkerFactory factory = mock(WorkerFactory.class);
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);
        List<String> lifecycle = new ArrayList<>();
        doAnswer(invocation -> {
                    lifecycle.add("factory.start");
                    return null;
                })
                .when(factory)
                .start();
        Runnable graphBinding = graphPollingBindingForTest(
                targetGraphClientProperties(), bundle, factory);

        try (StartupProbeHarness harness = new StartupProbeHarness(
                properties,
                taskQueue -> expectedDescription(properties, taskQueue),
                false,
                lifecycle)) {
            WorkerFactory returned = TemporalWorkerConfiguration.start(
                    factory,
                    () -> lifecycle.add("registration"),
                    harness.workflowClient,
                    properties,
                    () -> {
                        lifecycle.add("bundle.bind");
                        graphBinding.run();
                    });
            lifecycle.add("bean.return");

            assertThat(returned).isSameAs(factory);
            assertThat(lifecycle)
                    .containsExactly(
                            "registration",
                            "factory.start",
                            "probe:" + AGENT_EXECUTION,
                            "bundle.bind",
                            "bean.return");
            harness.assertExactContract(List.of(AGENT_EXECUTION));
        }

        ArgumentCaptor<Runnable> suspend = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> resume = ArgumentCaptor.forClass(Runnable.class);
        verify(bundle, times(1)).bindWorkerPolling(suspend.capture(), resume.capture());
        suspend.getValue().run();
        resume.getValue().run();
        verify(factory, times(1)).start();
        verify(factory, times(1)).suspendPolling();
        verify(factory, times(1)).resumePolling();
        verify(factory, never()).shutdownNow();
        verifyNoMoreInteractions(factory, bundle);
    }

    private static void assertGraphPollingBindingFailureCleansFactory(
            boolean sameCleanupFailure) {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        WorkerFactory factory = mock(WorkerFactory.class);
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);
        IllegalStateException bindFailure = new IllegalStateException("expected bind failure");
        IllegalArgumentException cleanupFailure =
                new IllegalArgumentException("expected cleanup failure");
        doThrow(bindFailure).when(bundle).bindWorkerPolling(any(), any());
        doThrow(sameCleanupFailure ? bindFailure : cleanupFailure)
                .when(factory)
                .shutdownNow();
        Runnable graphBinding = graphPollingBindingForTest(
                targetGraphClientProperties(), bundle, factory);
        List<String> lifecycle = new ArrayList<>();
        doAnswer(invocation -> {
                    lifecycle.add("factory.start");
                    return null;
                })
                .when(factory)
                .start();

        try (StartupProbeHarness harness = new StartupProbeHarness(
                properties,
                taskQueue -> expectedDescription(properties, taskQueue),
                false,
                lifecycle)) {
            assertThatThrownBy(() -> TemporalWorkerConfiguration.start(
                            factory,
                            () -> lifecycle.add("registration"),
                            harness.workflowClient,
                            properties,
                            graphBinding))
                    .isSameAs(bindFailure);
            harness.assertExactContract(List.of(AGENT_EXECUTION));
        }

        assertThat(bindFailure.getSuppressed())
                .containsExactly(sameCleanupFailure ? new Throwable[0] : new Throwable[] {cleanupFailure});
        verify(bundle, times(1)).bindWorkerPolling(any(), any());
        verify(factory, times(1)).start();
        verify(factory, times(1)).shutdownNow();
        verifyNoMoreInteractions(factory, bundle);
    }

    private static void assertGraphDisabledAndLocalTransportsDoNotBindPolling() {
        WorkerFactory factory = mock(WorkerFactory.class);
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);

        graphPollingBindingForTest(disabledGraphClientProperties(), bundle, factory).run();
        graphPollingBindingForTest(localGraphClientProperties(), bundle, factory).run();

        verifyNoInteractions(factory, bundle);
    }

    private static void assertGraphDependencyDirectionIsWorkerToBundleOnly() {
        List<Method> workerBeanMethods = Arrays.stream(
                        TemporalWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == WorkerFactory.class)
                .filter(method -> method.getAnnotation(Bean.class) != null)
                .toList();
        assertThat(workerBeanMethods)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "temporalControlWorkerFactory", "temporalAgentWorkerFactory");
        List<Method> annotatedAgentFactories = Arrays.stream(
                        TemporalWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("temporalAgentWorkerFactory"))
                .filter(method -> method.getAnnotation(Bean.class) != null)
                .toList();
        assertThat(annotatedAgentFactories).hasSize(1);
        Method annotatedAgentFactory = annotatedAgentFactories.getFirst();
        assertThat(Arrays.stream(annotatedAgentFactory.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName))
                .contains(
                        "org.springframework.beans.factory.ObjectProvider<"
                                + "com.example.dispute.workflow.infrastructure.agent."
                                + "GraphTransportBundle>");
        ConditionalOnProperty agentRole =
                annotatedAgentFactory.getAnnotation(ConditionalOnProperty.class);
        assertThat(agentRole.name()).containsExactly("app.temporal.worker.role");
        assertThat(agentRole.havingValue()).isEqualTo(WorkerRole.AGENT.name());

        Method controlFactory = Arrays.stream(
                        TemporalWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("temporalControlWorkerFactory"))
                .findFirst()
                .orElseThrow();
        assertThat(Arrays.stream(controlFactory.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName))
                .noneMatch(type -> type.contains("GraphTransportBundle"));
        assertThat(Arrays.stream(GraphTransportConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("graphTransportBundle"))
                        .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                        .map(Class::getName))
                .noneMatch(type -> type.equals(WorkerFactory.class.getName())
                        || type.equals(
                                org.springframework.beans.factory.ObjectProvider.class.getName()));
    }

    private static GraphTransportBundle requireGraphTransportBundleForTest(
            GraphCommandClientProperties properties,
            org.springframework.beans.factory.ObjectProvider<GraphTransportBundle> provider) {
        return invokeTemporalWorkerPrivateStatic(
                "requireGraphTransportBundle",
                new Class<?>[] {
                    GraphCommandClientProperties.class,
                    org.springframework.beans.factory.ObjectProvider.class
                },
                properties,
                provider);
    }

    private static Runnable graphPollingBindingForTest(
            GraphCommandClientProperties properties,
            GraphTransportBundle bundle,
            WorkerFactory factory) {
        return invokeTemporalWorkerPrivateStatic(
                "graphPollingBinding",
                new Class<?>[] {
                    GraphCommandClientProperties.class,
                    GraphTransportBundle.class,
                    WorkerFactory.class
                },
                properties,
                bundle,
                factory);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeTemporalWorkerPrivateStatic(
            String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method =
                    TemporalWorkerConfiguration.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error errorFailure) {
                throw errorFailure;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertSuccessfulStartupProbe(
            TemporalWorkerProperties properties, List<String> expectedTaskQueues) {
        WorkerFactory factory = mock(WorkerFactory.class);
        List<String> lifecycle = new ArrayList<>();
        doAnswer(invocation -> {
                    lifecycle.add("factory.start");
                    return null;
                })
                .when(factory)
                .start();

        try (StartupProbeHarness harness = new StartupProbeHarness(
                properties,
                taskQueue -> expectedDescription(properties, taskQueue),
                false,
                lifecycle)) {
            WorkerFactory returned = TemporalWorkerConfiguration.start(
                    factory,
                    () -> lifecycle.add("registration"),
                    harness.workflowClient,
                    properties);

            assertThat(returned).isSameAs(factory);
            assertThat(lifecycle)
                    .containsExactlyElementsOf(Stream.concat(
                                    Stream.of("registration", "factory.start"),
                                    expectedTaskQueues.stream().map(taskQueue -> "probe:" + taskQueue))
                            .toList());
            harness.assertExactContract(expectedTaskQueues);
        }

        verify(factory, times(1)).start();
        verify(factory, never()).shutdownNow();
        verifyNoMoreInteractions(factory);
    }

    private static void assertProbeFailureCleansUnpublishedFactory(boolean timeout) {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        WorkerFactory factory = mock(WorkerFactory.class);
        List<String> lifecycle = new ArrayList<>();
        doAnswer(invocation -> {
                    lifecycle.add("factory.start");
                    return null;
                })
                .when(factory)
                .start();
        Function<String, TemporalWorkerDescription> result = timeout
                ? taskQueue -> expectedDescription(properties, taskQueue)
                : taskQueue -> new TemporalWorkerDescription(
                        "temporal-worker-description.v1",
                        WorkerRole.CONTROL.name(),
                        taskQueue,
                        properties.deploymentName(),
                        properties.buildId(),
                        properties.versioningMode().name());

        try (StartupProbeHarness harness =
                new StartupProbeHarness(properties, result, timeout, lifecycle)) {
            assertThatThrownBy(() -> TemporalWorkerConfiguration.start(
                            factory,
                            () -> lifecycle.add("registration"),
                            harness.workflowClient,
                            properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(timeout
                            ? "Temporal worker startup probe timed out"
                            : "Temporal worker startup probe result mismatch");
            harness.assertExactContract(List.of(AGENT_EXECUTION));
        }

        verify(factory, times(1)).start();
        verify(factory, times(1)).shutdownNow();
        verifyNoMoreInteractions(factory);
    }

    private static void assertStartFailurePreservesCleanupFailure() {
        WorkerFactory factory = mock(WorkerFactory.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        IllegalStateException startFailure = new IllegalStateException("expected start failure");
        IllegalArgumentException cleanupFailure =
                new IllegalArgumentException("expected cleanup failure");
        doThrow(startFailure).when(factory).start();
        doThrow(cleanupFailure).when(factory).shutdownNow();

        assertThatThrownBy(() -> TemporalWorkerConfiguration.start(
                        factory, () -> {}, workflowClient, properties(WorkerRole.AGENT)))
                .isSameAs(startFailure);

        assertThat(startFailure.getSuppressed()).containsExactly(cleanupFailure);
        verify(factory, times(1)).start();
        verify(factory, times(1)).shutdownNow();
        verifyNoMoreInteractions(factory);
        verifyNoInteractions(workflowClient);
    }

    private static void assertRegistrationFailurePreservesSameCleanupFailureIdentity() {
        WorkerFactory factory = mock(WorkerFactory.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        AssertionError registrationFailure = new AssertionError("expected registration failure");
        doThrow(registrationFailure).when(factory).shutdownNow();

        assertThatThrownBy(() -> TemporalWorkerConfiguration.start(
                        factory,
                        () -> {
                            throw registrationFailure;
                        },
                        workflowClient,
                        properties(WorkerRole.CONTROL)))
                .isSameAs(registrationFailure);

        assertThat(registrationFailure.getSuppressed()).isEmpty();
        verify(factory, never()).start();
        verify(factory, times(1)).shutdownNow();
        verifyNoMoreInteractions(factory);
        verifyNoInteractions(workflowClient);
    }

    private static void assertEagerSingletonWorkerBeansAndDisabledGuard() {
        List<Method> workerBeanMethods = Arrays.stream(
                        TemporalWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == WorkerFactory.class)
                .filter(method -> method.getAnnotation(Bean.class) != null)
                .toList();
        assertThat(workerBeanMethods)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "temporalControlWorkerFactory", "temporalAgentWorkerFactory");
        for (Method workerBeanMethod : workerBeanMethods) {
            assertThat(workerBeanMethod.getAnnotation(Bean.class)).isNotNull();
            assertThat(workerBeanMethod.getAnnotation(Lazy.class))
                    .isNotNull()
                    .extracting(Lazy::value)
                    .isEqualTo(false);
            assertThat(workerBeanMethod.getAnnotation(Scope.class)).isNull();
        }

        ConditionalOnProperty enabledGuard =
                TemporalWorkerConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(enabledGuard.name()).containsExactly("app.temporal.worker.enabled");
        assertThat(enabledGuard.havingValue()).isEqualTo("true");
    }

    private static void assertBusinessNeutralProbeShape() {
        assertThat(TemporalWorkerProbeWorkflowImpl.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .containsExactly(TemporalWorkerProbeActivities.class);
        assertThat(TemporalWorkerProbeWorkflowImpl.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("probe");
        assertThat(TemporalWorkerProbeActivitiesImpl.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .containsExactly(
                        TemporalWorkerDescription.class, GraphTransportBundle.class);
        assertThat(TemporalWorkerProbeActivitiesImpl.class.getDeclaredConstructors())
                .hasSize(2)
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(TemporalWorkerProperties.class, String.class))
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(
                                TemporalWorkerProperties.class,
                                String.class,
                                GraphTransportBundle.class));
    }

    private static void assertIntakePreparationUsesBoundGraphBundle() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        GraphTransportBundle bundle = mock(GraphTransportBundle.class);
        TemporalWorkerProbeActivitiesImpl activities =
                new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION, bundle);

        assertThat(activities.prepareIntakeInfrastructure())
                .isEqualTo(IntakeInfrastructurePreparationResult.ready());
        verify(bundle).prepareIntakeInfrastructure(Duration.ofSeconds(20));

        TemporalWorkerProbeActivitiesImpl unbound =
                new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION);
        assertThatThrownBy(unbound::prepareIntakeInfrastructure)
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "Intake infrastructure preparation requires the AGENT Graph transport bundle");
        assertThat(IntakeInfrastructurePreparationWorkflowImpl.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("prepare");
    }

    private static TemporalWorkerDescription expectedDescription(
            TemporalWorkerProperties properties, String taskQueue) {
        return new TemporalWorkerDescription(
                "temporal-worker-description.v1",
                properties.role().name(),
                taskQueue,
                properties.deploymentName(),
                properties.buildId(),
                properties.versioningMode().name());
    }

    private static final class StartupProbeHarness implements AutoCloseable {

        private final TemporalWorkerProperties properties;
        private final Function<String, TemporalWorkerDescription> result;
        private final boolean timeout;
        private final List<String> lifecycle;
        private final WorkflowClient workflowClient = mock(WorkflowClient.class);
        private final List<WorkflowOptions> options = new ArrayList<>();
        private final List<Long> resultTimeoutNanos = new ArrayList<>();
        private final MockedStatic<WorkflowClient> workflowStarts = mockStatic(WorkflowClient.class);
        private final MockedStatic<WorkflowStub> workflowStubs = mockStatic(WorkflowStub.class);

        private StartupProbeHarness(
                TemporalWorkerProperties properties,
                Function<String, TemporalWorkerDescription> result,
                boolean timeout,
                List<String> lifecycle) {
            this.properties = properties;
            this.result = result;
            this.timeout = timeout;
            this.lifecycle = lifecycle;
            when(workflowClient.newWorkflowStub(
                            eq(TemporalWorkerProbeWorkflow.class), any(WorkflowOptions.class)))
                    .thenAnswer(invocation -> createProbe(invocation.getArgument(1)));
        }

        private TemporalWorkerProbeWorkflow createProbe(WorkflowOptions workflowOptions) {
            assertThat(lifecycle).startsWith("registration", "factory.start");
            options.add(workflowOptions);
            lifecycle.add("probe:" + workflowOptions.getTaskQueue());
            TemporalWorkerProbeWorkflow probe = mock(TemporalWorkerProbeWorkflow.class);
            WorkflowStub untyped = mock(WorkflowStub.class);
            workflowStubs.when(() -> WorkflowStub.fromTyped(probe)).thenReturn(untyped);
            try {
                when(untyped.getResult(
                                anyLong(),
                                eq(TimeUnit.NANOSECONDS),
                                eq(TemporalWorkerDescription.class)))
                        .thenAnswer(invocation -> {
                            long remainingNanos = invocation.getArgument(0);
                            resultTimeoutNanos.add(remainingNanos);
                            if (timeout) {
                                throw new TimeoutException("expected timeout");
                            }
                            return result.apply(workflowOptions.getTaskQueue());
                        });
            } catch (TimeoutException impossible) {
                throw new AssertionError(impossible);
            }
            return probe;
        }

        private void assertExactContract(List<String> expectedTaskQueues) {
            assertThat(options)
                    .extracting(WorkflowOptions::getTaskQueue)
                    .containsExactlyElementsOf(expectedTaskQueues);
            assertThat(options)
                    .extracting(WorkflowOptions::getWorkflowId)
                    .doesNotHaveDuplicates();
            for (WorkflowOptions workflowOptions : options) {
                String prefix = "temporal-worker-startup-probe:"
                        + properties.role().name()
                        + ":"
                        + workflowOptions.getTaskQueue()
                        + ":";
                assertThat(workflowOptions.getWorkflowId()).startsWith(prefix);
                assertThat(UUID.fromString(
                                workflowOptions.getWorkflowId().substring(prefix.length())))
                        .isNotNull();
                assertThat(workflowOptions.getWorkflowIdReusePolicy())
                        .isEqualTo(
                                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
                assertThat(workflowOptions.getWorkflowExecutionTimeout())
                        .isPositive()
                        .isLessThanOrEqualTo(TemporalWorkerConfiguration.STARTUP_PROBE_TIMEOUT);
            }
            assertThat(resultTimeoutNanos).hasSize(expectedTaskQueues.size());
            assertThat(resultTimeoutNanos)
                    .allSatisfy(remainingNanos -> assertThat(remainingNanos)
                            .isPositive()
                            .isLessThanOrEqualTo(
                                    TemporalWorkerConfiguration.STARTUP_PROBE_TIMEOUT.toNanos()));
            if (expectedTaskQueues.isEmpty()) {
                verifyNoInteractions(workflowClient);
            } else {
                verify(workflowClient, times(expectedTaskQueues.size()))
                        .newWorkflowStub(
                                eq(TemporalWorkerProbeWorkflow.class),
                                any(WorkflowOptions.class));
                verifyNoMoreInteractions(workflowClient);
            }
        }

        @Override
        public void close() {
            workflowStubs.close();
            workflowStarts.close();
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
                     disabledGraphClientProperties(),
                     disabledIntakeSelection(),
                     mockProvider(IntakeSyntheticWorkerRegistration.class),
                     mockProvider(AgentRunLedger.class),
                     mockProvider(AgentRunExecutionGateway.class),
                     mockProvider(AgentRunFinalizationGateway.class),
                     mockProvider(AgentRunFinalizationFailureRecorder.class),
                     mockProvider(TargetE2eAgentDeploymentBinding.class));
        }
        return configuration.temporalControlWorkerFactory(
                environment.getWorkflowClient(),
                appProperties,
                properties,
                disabledIntakeSelection(),
                optionsFactory,
                mock(EvidenceWindowActivitiesAdapter.class),
                mock(CaseProcessLedgerActivitiesImpl.class),
                mock(ProcessProjectionActivitiesImpl.class),
                streamProvider(),
                provider(mock(IntakeChildBridgeReadPort.class)),
                streamProvider());
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
        return properties(true, role, versioningMode);
    }

    private static TemporalWorkerProperties disabledApiProperties() {
        return properties(false, WorkerRole.API, VersioningMode.NONE);
    }

    private static TemporalWorkerProperties properties(
            boolean enabled, WorkerRole role, VersioningMode versioningMode) {
        QueueCapacity control = new QueueCapacity(64, 32, 2, 2, 0);
        QueueCapacity room = new QueueCapacity(64, 16, 2, 2, 0);
        QueueCapacity agent = new QueueCapacity(8, 32, 2, 2, 0);
        QueueCapacity tools = new QueueCapacity(8, 16, 2, 2, 0);
        return new TemporalWorkerProperties(
                enabled,
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

    private static GraphCommandClientProperties disabledGraphClientProperties() {
        return new GraphCommandClientProperties(
                GraphCommandClientProperties.Mode.DISABLED,
                null,
                null,
                Duration.ofSeconds(10),
                false);
    }

    private static GraphCommandClientProperties targetGraphClientProperties() {
        return new GraphCommandClientProperties(
                GraphCommandClientProperties.Mode.TARGET_E2E_CANDIDATE,
                URI.create("https://graph-target.test"),
                "p9act.v1.0123456789abcdef0123456789abcdef",
                Duration.ofSeconds(10),
                false);
    }

    private static GraphCommandClientProperties localGraphClientProperties() {
        return new GraphCommandClientProperties(
                GraphCommandClientProperties.Mode.SHADOW,
                URI.create("http://127.0.0.1:18000"),
                null,
                Duration.ofSeconds(10),
                true);
    }

    private static TargetE2eAgentDeploymentBinding targetBinding(String agentBuildId) {
        return new TargetE2eAgentDeploymentBinding(
                "local-preprod",
                1,
                "p9act.v1.0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                agentBuildId);
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
