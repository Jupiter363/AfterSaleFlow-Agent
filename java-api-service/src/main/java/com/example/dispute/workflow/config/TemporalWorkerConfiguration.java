package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;

import com.example.dispute.config.AppProperties;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivityImpl;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesV2Adapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
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
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.JdbcIntakeChildBridgeReadPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.ArrayList;
import java.util.List;
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
        IntakeAuthorityWorkerRegistration intakeAuthorityRegistration =
                resolveIntakeAuthorityRegistration(
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
                                targetRegistration));
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
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider,
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
                                finalizationGatewayProvider));
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
        requireDedicatedLegacyTaskQueue(legacyTaskQueue);

        Worker caseControl =
                factory.newWorker(CASE_CONTROL, optionsFactory.workerOptions(CASE_CONTROL));
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

    private static void registerAgentWorker(
            WorkerFactory factory,
            TemporalWorkerProperties properties,
            TemporalWorkerOptionsFactory optionsFactory,
            AgentRunV2Properties agentRunV2Properties,
            IntakeEpochSelectionProperties intakeEpochSelectionProperties,
            ObjectProvider<IntakeSyntheticWorkerRegistration> syntheticRegistrationProvider,
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider) {
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
            AgentRunFinalizationGateway finalizationGateway =
                    requireUnique(finalizationGatewayProvider, "AgentRunFinalizationGateway");
            workflowTypes.add(AgentRunWorkflowImpl.class);
            workflowTypes.add(TemporalWorkerProbeWorkflowImpl.class);
            activityImplementations.add(new ExecuteAgentRunActivityImpl(ledger, executionGateway));
            activityImplementations.add(new FinalizeAgentRunActivityImpl(finalizationGateway));
            activityImplementations.add(
                    new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION));
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

    private static void requireVersionedControlWorker(TemporalWorkerProperties properties) {
        if (properties.versioningMode() == TemporalWorkerProperties.VersioningMode.NONE) {
            throw new IllegalStateException(
                    "Control worker requires Temporal versioningMode BUILD_ID or DEPLOYMENT for v1 activity compatibility");
        }
    }

    private static WorkerFactory start(WorkerFactory factory, Runnable registration) {
        try {
            registration.run();
            factory.start();
            return factory;
        } catch (RuntimeException failure) {
            factory.shutdownNow();
            throw failure;
        }
    }

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
