package com.example.dispute.workflow.config;

import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapRelay;
import com.example.dispute.workflow.infrastructure.projection.ProcessProjectionReconciliationScheduler;
import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.orchestration.control-recovery-scheduling.enabled",
        havingValue = "true")
@EnableConfigurationProperties({
    CaseDomainEventRecoveryProperties.class,
    ProcessProjectionReconciliationProperties.class,
    RoomEpochBootstrapProperties.class
})
public class ControlPlaneRecoverySchedulingConfiguration {

    public static final String TASK_SCHEDULER_BEAN = "controlPlaneRecoveryTaskScheduler";

    @Bean(name = TASK_SCHEDULER_BEAN, destroyMethod = "shutdown")
    ThreadPoolTaskScheduler controlPlaneRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("control-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    ControlPlaneRecoveryScheduleRegistrar controlPlaneRecoveryScheduleRegistrar(
            @Qualifier(TASK_SCHEDULER_BEAN) TaskScheduler taskScheduler,
            ObjectProvider<CaseDomainEventRecoveryScheduler> domainEventRecovery,
            ObjectProvider<ProcessProjectionReconciliationScheduler> projectionReconciliation,
            ObjectProvider<RoomEpochBootstrapRelay> roomEpochBootstrap,
            CaseDomainEventRecoveryProperties domainEventProperties,
            ProcessProjectionReconciliationProperties reconciliationProperties,
            RoomEpochBootstrapProperties bootstrapProperties) {
        return new ControlPlaneRecoveryScheduleRegistrar(
                taskScheduler,
                domainEventRecovery,
                projectionReconciliation,
                roomEpochBootstrap,
                domainEventProperties,
                reconciliationProperties,
                bootstrapProperties);
    }
}

final class ControlPlaneRecoveryScheduleRegistrar implements SmartLifecycle {

    private final TaskScheduler taskScheduler;
    private final ObjectProvider<CaseDomainEventRecoveryScheduler> domainEventRecovery;
    private final ObjectProvider<ProcessProjectionReconciliationScheduler> projectionReconciliation;
    private final ObjectProvider<RoomEpochBootstrapRelay> roomEpochBootstrap;
    private final CaseDomainEventRecoveryProperties domainEventProperties;
    private final ProcessProjectionReconciliationProperties reconciliationProperties;
    private final RoomEpochBootstrapProperties bootstrapProperties;
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();
    private volatile boolean running;

    ControlPlaneRecoveryScheduleRegistrar(
            TaskScheduler taskScheduler,
            ObjectProvider<CaseDomainEventRecoveryScheduler> domainEventRecovery,
            ObjectProvider<ProcessProjectionReconciliationScheduler> projectionReconciliation,
            ObjectProvider<RoomEpochBootstrapRelay> roomEpochBootstrap,
            CaseDomainEventRecoveryProperties domainEventProperties,
            ProcessProjectionReconciliationProperties reconciliationProperties,
            RoomEpochBootstrapProperties bootstrapProperties) {
        this.taskScheduler = taskScheduler;
        this.domainEventRecovery = domainEventRecovery;
        this.projectionReconciliation = projectionReconciliation;
        this.roomEpochBootstrap = roomEpochBootstrap;
        this.domainEventProperties = domainEventProperties;
        this.reconciliationProperties = reconciliationProperties;
        this.bootstrapProperties = bootstrapProperties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        domainEventRecovery.ifAvailable(
                recovery ->
                        scheduledTasks.add(
                                taskScheduler.scheduleWithFixedDelay(
                                        recovery::recoverMissedEvents,
                                        domainEventProperties.pollInterval())));
        projectionReconciliation.ifAvailable(
                reconciliation ->
                        scheduledTasks.add(
                                taskScheduler.scheduleWithFixedDelay(
                                        reconciliation::scheduledReconciliation,
                                        reconciliationProperties.pollInterval())));
        roomEpochBootstrap.ifAvailable(
                bootstrap ->
                        scheduledTasks.add(
                                taskScheduler.scheduleWithFixedDelay(
                                        bootstrap::recoverBootstraps,
                                        bootstrapProperties.pollInterval())));
        running = true;
    }

    @Override
    public synchronized void stop() {
        scheduledTasks.forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1_000;
    }
}
