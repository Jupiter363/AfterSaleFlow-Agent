package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW;

import com.example.dispute.config.AppProperties;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder;
import com.example.dispute.workflow.activity.agent.AgentRunTerminalFailureCommitter;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivityImpl;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesV2Adapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import com.example.dispute.workflow.activity.system.IntakeInfrastructurePreparationWorkflowImpl;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflow;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflowImpl;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.contract.v1.TemporalTaskQueues;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivitiesV2;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.targete2e.TargetE2eAgentDeploymentBinding;
import com.example.dispute.workflow.targete2e.temporal.TargetTemporalWorkerRegistration;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.JdbcIntakeChildBridgeReadPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.taskqueue.v1.TaskQueuePartitionMetadata;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest;
import io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.VersioningOverride;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.temporal.worker.enabled",
        havingValue = "true")
public class TemporalWorkerConfiguration {

    static final Duration STARTUP_PROBE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration BUILD_ID_POLLER_READINESS_TIMEOUT = Duration.ofMinutes(4);
    static final Duration BUILD_ID_STARTUP_PROBE_TIMEOUT = Duration.ofMinutes(4);
    static final Duration DEPLOYMENT_STARTUP_PROBE_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration BUILD_ID_POLLER_CLOCK_SKEW = Duration.ofSeconds(2);
    private static final Duration BUILD_ID_POLLER_FRESHNESS = Duration.ofMinutes(1);
    private static final Duration BUILD_ID_POLLER_RETRY_DELAY = Duration.ofMillis(250);
    private static final int MAX_WORKFLOW_TASK_QUEUE_PARTITIONS = 64;
    private static final Pattern WORKER_DEPLOYMENT_COMPONENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @Bean
    IntakeChildBridgeReadPort intakeChildBridgeReadPort(DataSource dataSource) {
        return new JdbcIntakeChildBridgeReadPort(dataSource);
    }

    @Bean(destroyMethod = "shutdown")
    @Lazy(false)
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "CONTROL",
            matchIfMissing = true)
    WorkerFactory temporalControlWorkerFactory(
            WorkflowClient workflowClient,
            AppProperties appProperties,
            TemporalWorkerProperties properties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            TemporalWorkerOptionsFactory optionsFactory,
            EvidenceWindowActivitiesAdapter evidenceWindowActivities,
            CaseProcessLedgerActivitiesImpl ledgerActivities,
            ProcessProjectionActivitiesImpl projectionActivities,
            ObjectProvider<IntakeAuthorityWorkerRegistration> intakeAuthorityRegistrationProvider,
            ObjectProvider<IntakeChildBridgeReadPort> intakeChildBridgeReadPortProvider,
            ObjectProvider<TargetTemporalWorkerRegistration> targetRegistrationProvider) {
        requireVersionedControlWorker(properties);
        boolean caseProcessRecoveryOnly = properties.controlRegistrationScope()
                == TemporalWorkerProperties.ControlRegistrationScope.CASE_PROCESS_RECOVERY_ONLY;
        IntakeAuthorityWorkerRegistration intakeAuthorityRegistration = caseProcessRecoveryOnly
                ? null
                : resolveIntakeAuthorityRegistration(
                        intakeEpochSelectionProperties,
                        intakeAuthorityRegistrationProvider,
                        intakeChildBridgeReadPortProvider);
        TargetTemporalWorkerRegistration.Registration targetRegistration =
                resolveTargetRegistration(targetRegistrationProvider, properties);
        WorkerFactory factory =
                WorkerFactory.newInstance(workflowClient, optionsFactory.factoryOptions());
        return start(
                factory,
                () ->
                        registerControlWorkers(
                                factory,
                                appProperties.temporal().legacyTaskQueue(),
                                properties,
                                optionsFactory,
                                evidenceWindowActivities,
                                ledgerActivities,
                                projectionActivities,
                                intakeAuthorityRegistration,
                                targetRegistration),
                workflowClient,
                properties);
    }

    @Bean(destroyMethod = "shutdown")
    @Lazy(false)
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    WorkerFactory temporalAgentWorkerFactory(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            AgentRunV2Properties agentRunV2Properties,
            GraphCommandClientProperties graphClientProperties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            ObjectProvider<IntakeSyntheticWorkerRegistration> syntheticRegistrationProvider,
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunTerminalFailureCommitter> terminalFailureCommitterProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider,
            ObjectProvider<AgentRunFinalizationFailureRecorder> failureRecorderProvider,
            ObjectProvider<TargetE2eAgentDeploymentBinding> targetBindingProvider,
            ObjectProvider<GraphTransportBundle> graphTransportBundleProvider) {
        requireVersionedAgentWorker(
                properties, agentRunV2Properties, intakeEpochSelectionProperties);
        requireTargetAgentDeploymentBinding(
                properties, graphClientProperties, targetBindingProvider);
        GraphTransportBundle graphTransportBundle =
                requireGraphTransportBundle(graphClientProperties, graphTransportBundleProvider);
        WorkerFactory factory =
                WorkerFactory.newInstance(workflowClient, optionsFactory.factoryOptions());
        return start(
                factory,
                () ->
                        registerAgentWorker(
                                factory,
                                properties,
                                optionsFactory,
                                agentRunV2Properties,
                                intakeEpochSelectionProperties,
                                syntheticRegistrationProvider,
                                ledgerProvider,
                                executionGatewayProvider,
                                terminalFailureCommitterProvider,
                                finalizationGatewayProvider,
                                failureRecorderProvider,
                                graphTransportBundle),
                workflowClient,
                properties,
                graphPollingBinding(graphClientProperties, graphTransportBundle, factory));
    }

    WorkerFactory temporalAgentWorkerFactory(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            AgentRunV2Properties agentRunV2Properties,
            GraphCommandClientProperties graphClientProperties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            ObjectProvider<IntakeSyntheticWorkerRegistration> syntheticRegistrationProvider,
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider,
            ObjectProvider<AgentRunFinalizationFailureRecorder> failureRecorderProvider,
            ObjectProvider<TargetE2eAgentDeploymentBinding> targetBindingProvider) {
        requireVersionedAgentWorker(
                properties, agentRunV2Properties, intakeEpochSelectionProperties);
        requireTargetAgentDeploymentBinding(
                properties, graphClientProperties, targetBindingProvider);
        WorkerFactory factory =
                WorkerFactory.newInstance(workflowClient, optionsFactory.factoryOptions());
        return start(
                factory,
                () ->
                        registerAgentWorker(
                                factory,
                                properties,
                                optionsFactory,
                                agentRunV2Properties,
                                intakeEpochSelectionProperties,
                                syntheticRegistrationProvider,
                                ledgerProvider,
                                executionGatewayProvider,
                                null,
                                finalizationGatewayProvider,
                                failureRecorderProvider,
                                null),
                workflowClient,
                properties);
    }

    private static void registerControlWorkers(
            WorkerFactory factory,
            String legacyTaskQueue,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            EvidenceWindowActivitiesAdapter evidenceWindowActivities,
            CaseProcessLedgerActivitiesImpl ledgerActivities,
            ProcessProjectionActivitiesImpl projectionActivities,
            IntakeAuthorityWorkerRegistration intakeAuthorityRegistration,
            TargetTemporalWorkerRegistration.Registration targetRegistration) {
        if (properties.controlRegistrationScope()
                == TemporalWorkerProperties.ControlRegistrationScope.CASE_PROCESS_RECOVERY_ONLY) {
            registerCaseProcessRecoveryOnlyWorker(
                    factory,
                    properties,
                    optionsFactory,
                    ledgerActivities,
                    projectionActivities,
                    targetRegistration);
            return;
        }
        if (properties.controlRegistrationScope()
                == TemporalWorkerProperties.ControlRegistrationScope
                        .CASE_PROCESS_INTAKE_CONTINUATION_ONLY) {
            registerCaseProcessIntakeContinuationOnlyWorker(
                    factory,
                    properties,
                    optionsFactory,
                    ledgerActivities,
                    projectionActivities,
                    intakeAuthorityRegistration,
                    targetRegistration);
            return;
        }
        requireDedicatedLegacyTaskQueue(legacyTaskQueue);

        Worker caseControl =
                factory.newWorker(CASE_CONTROL, optionsFactory.workerOptions(CASE_CONTROL));
        // CASE_CONTROL 是 CaseProcessWorkflowImpl 的执行端：Update-with-Start 的根流程在此 poll，
        // 同一个 worker 还提供它调用的 ledger、Intake bridge 与 process-projection 活动实现。
        List<Class<?>> caseControlWorkflows = new ArrayList<>();
        caseControlWorkflows.add(
                targetRegistration == null
                        ? CaseProcessWorkflowImpl.class
                        : targetRegistration.caseProcessWorkflowImplementation());
        caseControlWorkflows.add(TemporalWorkerProbeWorkflowImpl.class);
        IntakeChildBridgeActivitiesV2Adapter v2BridgeActivities =
                new IntakeChildBridgeActivitiesV2Adapter(intakeAuthorityRegistration.bridgeActivities());
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2BridgeRegistration =
                intakeAuthorityRegistration.authorityBackedV2Activity(
                        v2BridgeActivities, IntakeChildBridgeActivitiesV2.class);
        List<Object> caseControlActivities = new ArrayList<>(
                intakeAuthorityRegistration.caseControlActivityImplementations(
                        v2BridgeRegistration,
                        ledgerActivities,
                        projectionActivities,
                        new TemporalWorkerProbeActivitiesImpl(properties, CASE_CONTROL)));
        if (targetRegistration != null) {
            caseControlActivities.addAll(targetRegistration.caseControlActivities());
        }
        if (targetRegistration != null) {
            IntakeAuthorityWorkerRegistration.requireNoForbiddenRuntimeTypes(caseControlWorkflows);
        }
        intakeAuthorityRegistration.validateCaseControlRegistration(
                caseControlWorkflows,
                caseControlActivities,
                v2BridgeRegistration);
        // 注册后，SDK gateway 投递到 CASE_CONTROL 的 CaseProcessWorkflow Update 才会由上面的实现消费；
        // room child 则由独立的 ROOM_CONTROL worker 执行，保持父/子工作流职责边界清晰。
        caseControl.registerWorkflowImplementationTypes(caseControlWorkflows.toArray(Class[]::new));
        caseControl.registerActivitiesImplementations(caseControlActivities.toArray());

        Worker roomControl =
                factory.newWorker(ROOM_CONTROL, optionsFactory.workerOptions(ROOM_CONTROL));
        List<Class<?>> roomControlWorkflows = new ArrayList<>();
        roomControlWorkflows.add(RoomControlWorkflowImpl.class);
        roomControlWorkflows.add(IntakeRoomWorkflowImpl.class);
        roomControlWorkflows.add(TemporalWorkerProbeWorkflowImpl.class);
        List<Object> roomControlActivities = new ArrayList<>();
        roomControlActivities.add(new TemporalWorkerProbeActivitiesImpl(properties, ROOM_CONTROL));
        if (targetRegistration != null) {
            roomControlWorkflows.addAll(targetRegistration.roomWorkflowImplementations());
            roomControlActivities.addAll(targetRegistration.roomControlActivities());
        }
        if (roomControlWorkflows.stream().distinct().count() != roomControlWorkflows.size()) {
            throw new IllegalStateException(
                    "ROOM_CONTROL workflow implementation types must be unique");
        }
        intakeAuthorityRegistration.validateRoomControlRegistration(
                roomControlWorkflows, roomControlActivities);
        roomControl.registerWorkflowImplementationTypes(roomControlWorkflows.toArray(Class[]::new));
        roomControl.registerActivitiesImplementations(roomControlActivities.toArray());

        Worker notificationAndTools =
                factory.newWorker(
                        NOTIFICATION_AND_TOOLS,
                        optionsFactory.workerOptions(NOTIFICATION_AND_TOOLS));
        notificationAndTools.registerWorkflowImplementationTypes(
                TemporalWorkerProbeWorkflowImpl.class);
        notificationAndTools.registerActivitiesImplementations(
                new TemporalWorkerProbeActivitiesImpl(properties, NOTIFICATION_AND_TOOLS));

        Worker legacyEvidenceWindow =
                factory.newWorker(
                        legacyTaskQueue, optionsFactory.legacyControlWorkerOptions());
        legacyEvidenceWindow.registerWorkflowImplementationTypes(
                EvidenceWindowWorkflowImpl.class, TemporalWorkerProbeWorkflowImpl.class);
        legacyEvidenceWindow.registerActivitiesImplementations(
                evidenceWindowActivities,
                new TemporalWorkerProbeActivitiesImpl(properties, legacyTaskQueue));
    }

    private static void registerCaseProcessRecoveryOnlyWorker(
            WorkerFactory factory,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            CaseProcessLedgerActivitiesImpl ledgerActivities,
            ProcessProjectionActivitiesImpl projectionActivities,
            TargetTemporalWorkerRegistration.Registration targetRegistration) {
        if (targetRegistration == null) {
            throw new IllegalStateException(
                    "CASE_PROCESS_RECOVERY_ONLY requires one exact target Temporal registration");
        }
        Worker caseControl =
                factory.newWorker(CASE_CONTROL, optionsFactory.workerOptions(CASE_CONTROL));
        caseControl.registerWorkflowImplementationTypes(
                targetRegistration.caseProcessWorkflowImplementation(),
                TemporalWorkerProbeWorkflowImpl.class);
        caseControl.registerActivitiesImplementations(
                ledgerActivities,
                projectionActivities,
                new TemporalWorkerProbeActivitiesImpl(properties, CASE_CONTROL));
    }

    private static void registerCaseProcessIntakeContinuationOnlyWorker(
            WorkerFactory factory,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            CaseProcessLedgerActivitiesImpl ledgerActivities,
            ProcessProjectionActivitiesImpl projectionActivities,
            IntakeAuthorityWorkerRegistration intakeAuthorityRegistration,
            TargetTemporalWorkerRegistration.Registration targetRegistration) {
        if (targetRegistration == null) {
            throw new IllegalStateException(
                    "CASE_PROCESS_INTAKE_CONTINUATION_ONLY requires one exact target Temporal registration");
        }
        if (intakeAuthorityRegistration == null) {
            throw new IllegalStateException(
                    "CASE_PROCESS_INTAKE_CONTINUATION_ONLY requires Intake authority activities");
        }

        Worker caseControl =
                factory.newWorker(CASE_CONTROL, optionsFactory.workerOptions(CASE_CONTROL));
        List<Class<?>> caseControlWorkflows = new ArrayList<>();
        caseControlWorkflows.add(targetRegistration.caseProcessWorkflowImplementation());
        caseControlWorkflows.add(TemporalWorkerProbeWorkflowImpl.class);
        IntakeChildBridgeActivitiesV2Adapter v2BridgeActivities =
                new IntakeChildBridgeActivitiesV2Adapter(intakeAuthorityRegistration.bridgeActivities());
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2BridgeRegistration =
                intakeAuthorityRegistration.authorityBackedV2Activity(
                        v2BridgeActivities, IntakeChildBridgeActivitiesV2.class);
        List<Object> caseControlActivities = new ArrayList<>(
                intakeAuthorityRegistration.caseControlActivityImplementations(
                        v2BridgeRegistration,
                        ledgerActivities,
                        projectionActivities,
                        new TemporalWorkerProbeActivitiesImpl(properties, CASE_CONTROL)));
        caseControlActivities.addAll(targetRegistration.caseControlActivities());
        IntakeAuthorityWorkerRegistration.requireNoForbiddenRuntimeTypes(caseControlWorkflows);
        intakeAuthorityRegistration.validateCaseControlRegistration(
                caseControlWorkflows, caseControlActivities, v2BridgeRegistration);
        caseControl.registerWorkflowImplementationTypes(caseControlWorkflows.toArray(Class[]::new));
        caseControl.registerActivitiesImplementations(caseControlActivities.toArray());

        Worker roomControl =
                factory.newWorker(ROOM_CONTROL, optionsFactory.workerOptions(ROOM_CONTROL));
        roomControl.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
    }

    private static void registerAgentWorker(
            WorkerFactory factory,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            AgentRunV2Properties agentRunV2Properties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            ObjectProvider<IntakeSyntheticWorkerRegistration> syntheticRegistrationProvider,
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunTerminalFailureCommitter> terminalFailureCommitterProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider,
            ObjectProvider<AgentRunFinalizationFailureRecorder> failureRecorderProvider,
            GraphTransportBundle graphTransportBundle) {
        Worker agentExecution =
                factory.newWorker(
                        AGENT_EXECUTION, optionsFactory.workerOptions(AGENT_EXECUTION));
        List<Class<?>> workflowTypes = new ArrayList<>();
        List<Object> activityImplementations = new ArrayList<>();
        if (!agentRunV2Properties.enabled()) {
            workflowTypes.add(TemporalWorkerProbeWorkflowImpl.class);
            activityImplementations.add(
                    new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION));
        } else {
            AgentRunLedger ledger = requireUnique(ledgerProvider, "AgentRunLedger");
            AgentRunExecutionGateway executionGateway =
                    requireUnique(executionGatewayProvider, "AgentRunExecutionGateway");
            AgentRunTerminalFailureCommitter terminalFailureCommitter =
                    terminalFailureCommitterProvider == null
                            ? AgentRunTerminalFailureCommitter.ledgerOnly(ledger)
                            : requireUnique(
                                    terminalFailureCommitterProvider,
                                    "AgentRunTerminalFailureCommitter");
            AgentRunFinalizationGateway finalizationGateway =
                    requireUnique(finalizationGatewayProvider, "AgentRunFinalizationGateway");
            AgentRunFinalizationFailureRecorder failureRecorder =
                    requireUnique(
                            failureRecorderProvider,
                            "AgentRunFinalizationFailureRecorder");
            workflowTypes.add(AgentRunWorkflowImpl.class);
            workflowTypes.add(TemporalWorkerProbeWorkflowImpl.class);
            if (graphTransportBundle != null) {
                workflowTypes.add(IntakeInfrastructurePreparationWorkflowImpl.class);
            }
            activityImplementations.add(new ExecuteAgentRunActivityImpl(
                    ledger, executionGateway, terminalFailureCommitter));
            activityImplementations.add(
                    new FinalizeAgentRunActivityImpl(finalizationGateway, failureRecorder));
            activityImplementations.add(
                    new TemporalWorkerProbeActivitiesImpl(
                            properties, AGENT_EXECUTION, graphTransportBundle));
        }

        if (intakeEpochSelectionProperties.shadowSelectionConfigured()) {
            activityImplementations.add(
                    requireExactlyOneSyntheticRegistration(syntheticRegistrationProvider)
                            .activityImplementation());
        }
        agentExecution.registerWorkflowImplementationTypes(workflowTypes.toArray(Class[]::new));
        agentExecution.registerActivitiesImplementations(activityImplementations.toArray());
    }

    private static <T> T requireUnique(ObjectProvider<T> provider, String dependency) {
        T value = provider.getIfUnique();
        if (value == null) {
            throw new IllegalStateException(
                    "AgentRun V2 requires exactly one " + dependency);
        }
        return value;
    }

    private static IntakeSyntheticWorkerRegistration requireExactlyOneSyntheticRegistration(
            ObjectProvider<IntakeSyntheticWorkerRegistration> provider) {
        List<IntakeSyntheticWorkerRegistration> registrations = provider.stream().toList();
        if (registrations.size() != 1) {
            throw new IllegalStateException(
                    "Signed synthetic Intake requires exactly one IntakeSyntheticWorkerRegistration");
        }
        return registrations.getFirst();
    }

    private static IntakeAuthorityWorkerRegistration resolveIntakeAuthorityRegistration(
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            ObjectProvider<IntakeAuthorityWorkerRegistration> registrationProvider,
            ObjectProvider<IntakeChildBridgeReadPort> readPortProvider) {
        List<IntakeAuthorityWorkerRegistration> registrations = registrationProvider.stream().toList();
        if (intakeEpochSelectionProperties.shadowSelectionConfigured()) {
            if (registrations.size() != 1) {
                throw new IllegalStateException(
                        "Signed synthetic Intake CONTROL requires exactly one admission-backed IntakeAuthorityWorkerRegistration");
            }
            return registrations.getFirst();
        }
        if (registrations.size() == 1) {
            return registrations.getFirst();
        }
        if (registrations.size() > 1) {
            throw new IllegalStateException(
                    "CASE_CONTROL requires at most one IntakeAuthorityWorkerRegistration");
        }
        return IntakeAuthorityWorkerRegistration.fromReadPortProvider(readPortProvider);
    }

    private static TargetTemporalWorkerRegistration.Registration resolveTargetRegistration(
            ObjectProvider<TargetTemporalWorkerRegistration> provider,
            TemporalWorkerProperties properties) {
        List<TargetTemporalWorkerRegistration> registrations = provider.stream().toList();
        if (registrations.isEmpty()) {
            return null;
        }
        if (registrations.size() != 1) {
            throw new IllegalStateException(
                    "target Temporal worker requires exactly one target registration");
        }
        TargetTemporalWorkerRegistration.Registration registration =
                registrations.getFirst().registration();
        if (!properties.buildId().equals(registration.controlBuildId())) {
            throw new IllegalStateException(
                    "target Temporal worker registration does not match the configured control Build ID");
        }
        return registration;
    }

    private static void requireVersionedAgentWorker(
            TemporalWorkerProperties properties,
            AgentRunV2Properties agentRunV2Properties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties) {
        if (agentRunV2Properties.enabled()
                && properties.versioningMode()
                        == TemporalWorkerProperties.VersioningMode.NONE) {
            throw new IllegalStateException(
                    "AgentRun v3 requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }
        if (intakeEpochSelectionProperties.shadowSelectionConfigured()
                && properties.versioningMode()
                        == TemporalWorkerProperties.VersioningMode.NONE) {
            throw new IllegalStateException(
                    "Signed synthetic Intake requires Temporal versioningMode BUILD_ID or DEPLOYMENT");
        }
        if (intakeEpochSelectionProperties.shadowSelectionConfigured()
                && agentRunV2Properties.enabled()) {
            throw new IllegalStateException(
                    "Signed synthetic Intake cannot share AGENT_EXECUTION with AgentRunV2");
        }
    }

    private static void requireTargetAgentDeploymentBinding(
            TemporalWorkerProperties workerProperties,
            GraphCommandClientProperties graphProperties,
            ObjectProvider<TargetE2eAgentDeploymentBinding> bindingProvider) {
        if (graphProperties.mode()
                != GraphCommandClientProperties.Mode.TARGET_E2E_CANDIDATE) {
            return;
        }
        TargetE2eAgentDeploymentBinding binding =
                requireUnique(bindingProvider, "target AGENT deployment binding");
        binding.requireWorkerConfiguration(
                graphProperties.activationId(), workerProperties.buildId());
    }

    private static GraphTransportBundle requireGraphTransportBundle(
            GraphCommandClientProperties properties,
            ObjectProvider<GraphTransportBundle> provider) {
        if (properties.mode() == GraphCommandClientProperties.Mode.DISABLED) {
            return null;
        }
        GraphTransportBundle bundle = provider.getIfUnique();
        if (bundle == null) {
            throw new IllegalStateException(
                    "Graph-enabled AGENT worker requires exactly one GraphTransportBundle");
        }
        return bundle;
    }

    private static Runnable graphPollingBinding(
            GraphCommandClientProperties properties,
            GraphTransportBundle bundle,
            WorkerFactory factory) {
        if (properties.mode() == GraphCommandClientProperties.Mode.DISABLED
                || !"https".equalsIgnoreCase(properties.baseUri().getScheme())) {
            return () -> {};
        }
        return () -> bundle.bindWorkerPolling(factory::suspendPolling, factory::resumePolling);
    }

    private static void requireVersionedControlWorker(TemporalWorkerProperties properties) {
        if (properties.versioningMode() == TemporalWorkerProperties.VersioningMode.NONE) {
            throw new IllegalStateException(
                    "Control worker requires Temporal versioningMode BUILD_ID or DEPLOYMENT for v1 activity compatibility");
        }
    }

    static WorkerFactory start(
            WorkerFactory factory,
            Runnable registration,
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties) {
        return start(factory, registration, workflowClient, properties, () -> {});
    }

    static WorkerFactory start(
            WorkerFactory factory,
            Runnable registration,
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            Runnable afterReadiness) {
        return start(
                factory,
                registration,
                workflowClient,
                properties,
                afterReadiness,
                System::nanoTime);
    }

    static WorkerFactory start(
            WorkerFactory factory,
            Runnable registration,
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            Runnable afterReadiness,
            LongSupplier nanoTime) {
        try {
            registration.run();
            Instant pollerNotBefore = Instant.now().minus(BUILD_ID_POLLER_CLOCK_SKEW);
            factory.start();
            requireStartupProbes(
                    workflowClient, properties, pollerNotBefore, nanoTime);
            afterReadiness.run();
            return factory;
        } catch (RuntimeException | Error failure) {
            try {
                factory.shutdownNow();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    static void requireStartupProbes(
            WorkflowClient workflowClient, TemporalWorkerProperties properties) {
        requireStartupProbes(workflowClient, properties, System::nanoTime);
    }

    static void requireStartupProbes(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            LongSupplier nanoTime) {
        requireStartupProbes(
                workflowClient,
                properties,
                Instant.now().minus(BUILD_ID_POLLER_CLOCK_SKEW),
                nanoTime);
    }

    private static void requireStartupProbes(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            Instant pollerNotBefore,
            LongSupplier nanoTime) {
        List<String> taskQueues = switch (properties.role()) {
            case CONTROL -> switch (properties.controlRegistrationScope()) {
                case FULL -> List.of(CASE_CONTROL, ROOM_CONTROL);
                case CASE_PROCESS_RECOVERY_ONLY -> List.of(CASE_CONTROL);
                case CASE_PROCESS_INTAKE_CONTINUATION_ONLY -> List.of(CASE_CONTROL);
            };
            case AGENT -> List.of(AGENT_EXECUTION);
            case API -> List.of();
        };
        BuildIdStartupPollerAuthority buildIdPollerAuthority =
                properties.versioningMode() == TemporalWorkerProperties.VersioningMode.BUILD_ID
                                && !taskQueues.isEmpty()
                        ? buildIdStartupPollerAuthority(
                                workflowClient, properties, pollerNotBefore)
                        : null;
        long sharedDeadlineNanos = properties.versioningMode()
                        == TemporalWorkerProperties.VersioningMode.NONE
                ? nanoTime.getAsLong() + STARTUP_PROBE_TIMEOUT.toNanos()
                : 0L;
        for (String taskQueue : taskQueues) {
            long deadlineNanos = switch (properties.versioningMode()) {
                case BUILD_ID -> {
                    long readinessDeadlineNanos = nanoTime.getAsLong()
                            + BUILD_ID_POLLER_READINESS_TIMEOUT.toNanos();
                    requireBuildIdStartupRootPoller(
                            buildIdPollerAuthority,
                            taskQueue,
                            readinessDeadlineNanos,
                            nanoTime);
                    yield nanoTime.getAsLong() + BUILD_ID_STARTUP_PROBE_TIMEOUT.toNanos();
                }
                case DEPLOYMENT -> nanoTime.getAsLong()
                        + DEPLOYMENT_STARTUP_PROBE_TIMEOUT.toNanos();
                case NONE -> sharedDeadlineNanos;
            };
            requireStartupProbe(
                    workflowClient, properties, taskQueue, deadlineNanos, nanoTime);
        }
    }

    private static BuildIdStartupPollerAuthority buildIdStartupPollerAuthority(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            Instant pollerNotBefore) {
        WorkflowClientOptions clientOptions = workflowClient.getOptions();
        if (clientOptions == null
                || clientOptions.getNamespace() == null
                || clientOptions.getNamespace().isBlank()
                || clientOptions.getIdentity() == null
                || clientOptions.getIdentity().isBlank()) {
            throw new IllegalStateException(
                    "Temporal BUILD_ID startup poller client authority is invalid");
        }
        WorkflowServiceStubs serviceStubs = workflowClient.getWorkflowServiceStubs();
        WorkflowServiceBlockingStub blockingStub =
                serviceStubs == null ? null : serviceStubs.blockingStub();
        String buildId = properties.legacyBuildId();
        if (blockingStub == null || buildId == null || buildId.isBlank()) {
            throw new IllegalStateException(
                    "Temporal BUILD_ID startup poller routing authority is invalid");
        }
        return new BuildIdStartupPollerAuthority(
                blockingStub,
                clientOptions.getNamespace(),
                clientOptions.getIdentity(),
                buildId,
                pollerNotBefore);
    }

    private static void requireBuildIdStartupRootPoller(
            BuildIdStartupPollerAuthority authority,
            String taskQueue,
            long deadlineNanos,
            LongSupplier nanoTime) {
        while (true) {
            long remainingNanos = requireRemainingProbeTime(deadlineNanos, nanoTime);
            WorkflowServiceBlockingStub boundedStub = authority.blockingStub()
                    .withDeadlineAfter(remainingNanos, TimeUnit.NANOSECONDS);
            String rootPartition = requireWorkflowPartitionKeys(
                    boundedStub.listTaskQueuePartitions(
                            ListTaskQueuePartitionsRequest.newBuilder()
                                    .setNamespace(authority.namespace())
                                    .setTaskQueue(normalTaskQueue(taskQueue))
                                    .build()),
                    taskQueue)
                    .getFirst();
            Instant minimumLastAccess = laterOf(
                    authority.pollerNotBefore(),
                    Instant.now().minus(BUILD_ID_POLLER_FRESHNESS));
            DescribeTaskQueueResponse response = boundedStub.describeTaskQueue(
                    DescribeTaskQueueRequest.newBuilder()
                            .setNamespace(authority.namespace())
                            .setTaskQueue(normalTaskQueue(rootPartition))
                            .setTaskQueueType(TASK_QUEUE_TYPE_WORKFLOW)
                            .setReportPollers(true)
                            .build());
            if (hasExactBuildIdPoller(response, authority, minimumLastAccess)) {
                return;
            }
            long remainingBeforeRetry = requireRemainingProbeTime(deadlineNanos, nanoTime);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(
                        "Temporal BUILD_ID startup poller readiness was interrupted");
            }
            LockSupport.parkNanos(Math.min(
                    remainingBeforeRetry, BUILD_ID_POLLER_RETRY_DELAY.toNanos()));
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(
                        "Temporal BUILD_ID startup poller readiness was interrupted");
            }
        }
    }

    private static List<String> requireWorkflowPartitionKeys(
            ListTaskQueuePartitionsResponse response, String taskQueue) {
        List<TaskQueuePartitionMetadata> partitions =
                response.getWorkflowTaskQueuePartitionsList();
        if (partitions.isEmpty()
                || partitions.size() > MAX_WORKFLOW_TASK_QUEUE_PARTITIONS) {
            throw new IllegalStateException(
                    "Temporal BUILD_ID workflow partition authority is invalid");
        }
        Set<String> actualKeys = new HashSet<>();
        for (TaskQueuePartitionMetadata partition : partitions) {
            if (partition.getKey().isBlank() || !actualKeys.add(partition.getKey())) {
                throw new IllegalStateException(
                        "Temporal BUILD_ID workflow partition authority is invalid");
            }
        }
        List<String> exactKeys = new ArrayList<>(partitions.size());
        for (int partition = 0; partition < partitions.size(); partition++) {
            String expectedKey = workflowPartitionKey(taskQueue, partition);
            if (!actualKeys.contains(expectedKey)) {
                throw new IllegalStateException(
                        "Temporal BUILD_ID workflow partition authority is invalid");
            }
            exactKeys.add(expectedKey);
        }
        return List.copyOf(exactKeys);
    }

    private static boolean hasExactBuildIdPoller(
            DescribeTaskQueueResponse response,
            BuildIdStartupPollerAuthority authority,
            Instant minimumLastAccess) {
        for (PollerInfo poller : response.getPollersList()) {
            if (!authority.identity().equals(poller.getIdentity())
                    || !poller.hasLastAccessTime()
                    || !poller.hasWorkerVersionCapabilities()
                    || poller.hasDeploymentOptions()
                    || !poller.getWorkerVersionCapabilities().getUseVersioning()
                    || !authority.buildId().equals(
                            poller.getWorkerVersionCapabilities().getBuildId())) {
                continue;
            }
            try {
                Instant lastAccess = Instant.ofEpochSecond(
                        poller.getLastAccessTime().getSeconds(),
                        poller.getLastAccessTime().getNanos());
                if (!lastAccess.isBefore(minimumLastAccess)) {
                    return true;
                }
            } catch (DateTimeException ignored) {
                // A malformed server timestamp cannot establish current poller authority.
            }
        }
        return false;
    }

    private static TaskQueue normalTaskQueue(String name) {
        return TaskQueue.newBuilder()
                .setName(name)
                .setKind(TASK_QUEUE_KIND_NORMAL)
                .build();
    }

    private static String workflowPartitionKey(String taskQueue, int partition) {
        return partition == 0 ? taskQueue : "/_sys/" + taskQueue + "/" + partition;
    }

    private static Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static void requireStartupProbe(
            WorkflowClient workflowClient,
            TemporalWorkerProperties properties,
            String taskQueue,
            long deadlineNanos,
            LongSupplier nanoTime) {
        long workflowTimeoutNanos = requireRemainingProbeTime(deadlineNanos, nanoTime);
        TemporalWorkerProbeWorkflow probe = workflowClient.newWorkflowStub(
                TemporalWorkerProbeWorkflow.class,
                startupProbeOptions(properties, taskQueue, workflowTimeoutNanos));
        WorkflowClient.start(probe::probe);
        TemporalWorkerDescription actual;
        try {
            actual = WorkflowStub.fromTyped(probe)
                    .getResult(
                            requireRemainingProbeTime(deadlineNanos, nanoTime),
                            TimeUnit.NANOSECONDS,
                            TemporalWorkerDescription.class);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("Temporal worker startup probe timed out", failure);
        }
        TemporalWorkerDescription expected = new TemporalWorkerDescription(
                "temporal-worker-description.v1",
                properties.role().name(),
                taskQueue,
                properties.deploymentName(),
                properties.buildId(),
                properties.versioningMode().name());
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Temporal worker startup probe result mismatch");
        }
    }

    private static WorkflowOptions startupProbeOptions(
            TemporalWorkerProperties properties,
            String taskQueue,
            long workflowTimeoutNanos) {
        WorkflowOptions.Builder options = WorkflowOptions.newBuilder()
                .setWorkflowId(startupProbeWorkflowId(properties, taskQueue))
                .setTaskQueue(taskQueue)
                .setWorkflowExecutionTimeout(Duration.ofNanos(workflowTimeoutNanos))
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
        if (properties.versioningMode()
                == TemporalWorkerProperties.VersioningMode.BUILD_ID) {
            options.setDisableEagerExecution(false);
        } else if (properties.versioningMode()
                == TemporalWorkerProperties.VersioningMode.DEPLOYMENT) {
            options.setVersioningOverride(deploymentProbeOverride(properties));
        }
        return options.build();
    }

    private static String startupProbeWorkflowId(
            TemporalWorkerProperties properties, String taskQueue) {
        return "temporal-worker-startup-probe:"
                + properties.role().name()
                + ":"
                + taskQueue
                + ":"
                + UUID.randomUUID();
    }

    private static VersioningOverride.PinnedVersioningOverride deploymentProbeOverride(
            TemporalWorkerProperties properties) {
        String deploymentName = properties.deploymentName();
        String buildId = properties.buildId();
        if (deploymentName == null
                || !WORKER_DEPLOYMENT_COMPONENT.matcher(deploymentName).matches()
                || buildId == null
                || !WORKER_DEPLOYMENT_COMPONENT.matcher(buildId).matches()) {
            throw new IllegalStateException(
                    "Temporal deployment startup probe authority is invalid");
        }
        WorkerDeploymentVersion version = new WorkerDeploymentVersion(deploymentName, buildId);
        String canonical = version.toCanonicalString();
        if (canonical.length() > 255 || !canonical.equals(properties.legacyBuildId())) {
            throw new IllegalStateException(
                    "Temporal deployment startup probe authority is inconsistent");
        }
        return new VersioningOverride.PinnedVersioningOverride(version);
    }

    private static long requireRemainingProbeTime(
            long deadlineNanos, LongSupplier nanoTime) {
        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0) {
            throw new IllegalStateException("Temporal worker startup probe timed out");
        }
        return remainingNanos;
    }

    private record BuildIdStartupPollerAuthority(
            WorkflowServiceBlockingStub blockingStub,
            String namespace,
            String identity,
            String buildId,
            Instant pollerNotBefore) {}

    private static void requireDedicatedLegacyTaskQueue(String legacyTaskQueue) {
        if (legacyTaskQueue == null || legacyTaskQueue.isBlank()) {
            throw new IllegalArgumentException(
                    "legacy EvidenceWindow task queue must be configured");
        }
        if (TemporalTaskQueues.all().contains(legacyTaskQueue)) {
            throw new IllegalArgumentException(
                    "legacy EvidenceWindow task queue must be distinct from protocol task queues");
        }
    }
}
