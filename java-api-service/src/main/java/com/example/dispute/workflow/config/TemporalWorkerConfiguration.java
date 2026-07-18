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
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivitiesImpl;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeWorkflowImpl;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.contract.v1.TemporalTaskQueues;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
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
            TemporalWorkerOptionsFactory optionsFactory,
            EvidenceWindowActivitiesAdapter evidenceWindowActivities,
            CaseProcessLedgerActivitiesImpl ledgerActivities,
            ProcessProjectionActivitiesImpl projectionActivities) {
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
                                projectionActivities));
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
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider) {
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
            ProcessProjectionActivitiesImpl projectionActivities) {
        requireDedicatedLegacyTaskQueue(legacyTaskQueue);

        Worker caseControl =
                factory.newWorker(CASE_CONTROL, optionsFactory.workerOptions(CASE_CONTROL));
        caseControl.registerWorkflowImplementationTypes(
                CaseProcessWorkflowImpl.class, TemporalWorkerProbeWorkflowImpl.class);
        caseControl.registerActivitiesImplementations(
                ledgerActivities,
                projectionActivities,
                new TemporalWorkerProbeActivitiesImpl(properties, CASE_CONTROL));

        Worker roomControl =
                factory.newWorker(ROOM_CONTROL, optionsFactory.workerOptions(ROOM_CONTROL));
        roomControl.registerWorkflowImplementationTypes(
                RoomControlWorkflowImpl.class, TemporalWorkerProbeWorkflowImpl.class);
        roomControl.registerActivitiesImplementations(
                new TemporalWorkerProbeActivitiesImpl(properties, ROOM_CONTROL));

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
            ObjectProvider<AgentRunLedger> ledgerProvider,
            ObjectProvider<AgentRunExecutionGateway> executionGatewayProvider,
            ObjectProvider<AgentRunFinalizationGateway> finalizationGatewayProvider) {
        Worker agentExecution =
                factory.newWorker(
                        AGENT_EXECUTION, optionsFactory.workerOptions(AGENT_EXECUTION));
        if (!agentRunV2Properties.enabled()) {
            agentExecution.registerWorkflowImplementationTypes(
                    TemporalWorkerProbeWorkflowImpl.class);
            agentExecution.registerActivitiesImplementations(
                    new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION));
            return;
        }

        AgentRunLedger ledger = requireUnique(ledgerProvider, "AgentRunLedger");
        AgentRunExecutionGateway executionGateway =
                requireUnique(executionGatewayProvider, "AgentRunExecutionGateway");
        AgentRunFinalizationGateway finalizationGateway =
                requireUnique(finalizationGatewayProvider, "AgentRunFinalizationGateway");
        agentExecution.registerWorkflowImplementationTypes(
                AgentRunWorkflowImpl.class, TemporalWorkerProbeWorkflowImpl.class);
        agentExecution.registerActivitiesImplementations(
                new ExecuteAgentRunActivityImpl(ledger, executionGateway),
                new FinalizeAgentRunActivityImpl(finalizationGateway),
                new TemporalWorkerProbeActivitiesImpl(properties, AGENT_EXECUTION));
    }

    private static <T> T requireUnique(ObjectProvider<T> provider, String dependency) {
        T value = provider.getIfUnique();
        if (value == null) {
            throw new IllegalStateException(
                    "AgentRun V2 requires exactly one " + dependency);
        }
        return value;
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
