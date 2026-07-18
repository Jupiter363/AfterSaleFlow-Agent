package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.config.AppProperties;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflow;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.config.TemporalWorkerProperties.QueueCapacity;
import com.example.dispute.workflow.config.TemporalWorkerProperties.VersioningMode;
import com.example.dispute.workflow.config.TemporalWorkerProperties.WorkerRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
    void v2AgentWorkerFailsClosedWhenExecutionGatewayIsMissing() {
        TemporalWorkerProperties properties = properties(WorkerRole.AGENT);
        AgentRunV2Properties v2Properties = new AgentRunV2Properties(
                true,
                AgentRunProtocol.V1,
                AgentRunV2Properties.SchedulerMode.OFF,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5));

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();
            assertThatThrownBy(() -> configuration.temporalAgentWorkerFactory(
                            environment.getWorkflowClient(),
                            properties,
                            new TemporalWorkerOptionsFactory(properties),
                            v2Properties,
                            provider(mock(AgentRunLedger.class)),
                            mockProvider(AgentRunExecutionGateway.class),
                            provider(mock(AgentRunFinalizationGateway.class))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one AgentRunExecutionGateway");
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
                    new AgentRunV2Properties(
                            false,
                            AgentRunProtocol.V1,
                            AgentRunV2Properties.SchedulerMode.EXECUTOR,
                            Duration.ofMinutes(10),
                            Duration.ofSeconds(15),
                            Duration.ofSeconds(5)),
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
                mock(ProcessProjectionActivitiesImpl.class));
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

    private static TemporalWorkerProperties properties(WorkerRole role) {
        QueueCapacity control = new QueueCapacity(64, 32, 2, 2, 0);
        QueueCapacity room = new QueueCapacity(64, 16, 2, 2, 0);
        QueueCapacity agent = new QueueCapacity(8, 32, 2, 2, 0);
        QueueCapacity tools = new QueueCapacity(8, 16, 2, 2, 0);
        return new TemporalWorkerProperties(
                true,
                role,
                VersioningMode.NONE,
                role == WorkerRole.CONTROL ? "after-sale-control" : "after-sale-agent",
                "test-build",
                256,
                control,
                room,
                agent,
                tools);
    }

    private static void assertProbe(
            TestWorkflowEnvironment environment, String taskQueue, WorkerRole role) {
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
        assertThat(description.versioningMode()).isEqualTo("NONE");
    }

    private static void shutdown(WorkerFactory factory) {
        factory.shutdownNow();
        factory.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
    }
}
