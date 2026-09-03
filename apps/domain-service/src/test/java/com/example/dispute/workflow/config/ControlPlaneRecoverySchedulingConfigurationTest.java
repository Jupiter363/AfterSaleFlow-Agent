package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapRelay;
import com.example.dispute.workflow.infrastructure.projection.ProcessProjectionReconciliationScheduler;
import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryScheduler;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ControlPlaneRecoverySchedulingConfigurationTest {

    private final CaseDomainEventRecoveryScheduler domainEventRecovery =
            mock(CaseDomainEventRecoveryScheduler.class);
    private final ProcessProjectionReconciliationScheduler projectionReconciliation =
            mock(ProcessProjectionReconciliationScheduler.class);
    private final RoomEpochBootstrapRelay roomEpochBootstrap =
            mock(RoomEpochBootstrapRelay.class);

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CommonConfiguration.class,
                            ControlPlaneRecoverySchedulingConfiguration.class)
                    .withBean(
                            CaseDomainEventRecoveryScheduler.class,
                            () -> domainEventRecovery)
                    .withBean(
                            ProcessProjectionReconciliationScheduler.class,
                            () -> projectionReconciliation)
                    .withBean(RoomEpochBootstrapRelay.class, () -> roomEpochBootstrap)
                    .withPropertyValues(
                            "dispute.scheduling.enabled=false",
                            "app.orchestration.domain-event-recovery.enabled=true",
                            "app.orchestration.domain-event-recovery.poll-interval=PT1H",
                            "app.orchestration.projection-reconciliation.enabled=true",
                            "app.orchestration.projection-reconciliation.poll-interval=PT1H",
                            "app.orchestration.room-epoch-bootstrap.enabled=true",
                            "app.orchestration.room-epoch-bootstrap.poll-interval=PT1H");

    @Test
    void startsOnlyTheDedicatedControlRecoverySchedulingDomain() {
        contextRunner
                .withPropertyValues(
                        "app.orchestration.control-recovery-scheduling.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(ControlPlaneRecoveryScheduleRegistrar.class)
                                    .hasSingleBean(ThreadPoolTaskScheduler.class)
                                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                            ThreadPoolTaskScheduler scheduler =
                                    context.getBean(ThreadPoolTaskScheduler.class);
                            assertThat(scheduler.getThreadNamePrefix())
                                    .isEqualTo("control-recovery-");
                            verify(domainEventRecovery, timeout(2_000).atLeastOnce())
                                    .recoverMissedEvents();
                            verify(projectionReconciliation, timeout(2_000).atLeastOnce())
                                    .scheduledReconciliation();
                            verify(roomEpochBootstrap, timeout(2_000).atLeastOnce())
                                    .recoverBootstraps();
                        });
    }

    @Test
    void doesNotCreateTheDedicatedDomainOutsideTheControlWorkerProfileContract() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(ControlPlaneRecoveryScheduleRegistrar.class)
                            .doesNotHaveBean(ThreadPoolTaskScheduler.class)
                            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    void registrarCancelsEveryTaskAndCanRestartCleanly() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> second = mock(ScheduledFuture.class);
        ScheduledFuture<?> third = mock(ScheduledFuture.class);
        ScheduledFuture<?> fourth = mock(ScheduledFuture.class);
        ScheduledFuture<?> fifth = mock(ScheduledFuture.class);
        ScheduledFuture<?> sixth = mock(ScheduledFuture.class);
        doReturn(first, second, third, fourth, fifth, sixth)
                .when(taskScheduler)
                .scheduleWithFixedDelay(
                        any(Runnable.class), eq(Duration.ofHours(1)));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("domainEventRecovery", domainEventRecovery);
        beans.addBean("projectionReconciliation", projectionReconciliation);
        beans.addBean("roomEpochBootstrap", roomEpochBootstrap);
        ControlPlaneRecoveryScheduleRegistrar registrar =
                new ControlPlaneRecoveryScheduleRegistrar(
                        taskScheduler,
                        beans.getBeanProvider(CaseDomainEventRecoveryScheduler.class),
                        beans.getBeanProvider(ProcessProjectionReconciliationScheduler.class),
                        beans.getBeanProvider(RoomEpochBootstrapRelay.class),
                        new CaseDomainEventRecoveryProperties(
                                true,
                                32,
                                64,
                                Duration.ofMinutes(5),
                                Duration.ofHours(1)),
                        new ProcessProjectionReconciliationProperties(
                                true,
                                32,
                                Duration.ofMinutes(5),
                                Duration.ofHours(1)),
                        new RoomEpochBootstrapProperties(
                                true,
                                32,
                                32,
                                Duration.ofMinutes(2),
                                Duration.ofSeconds(90),
                                Duration.ofSeconds(1),
                                Duration.ofMinutes(5),
                                Duration.ofHours(1)));

        registrar.start();
        assertThat(registrar.isRunning()).isTrue();
        registrar.stop();
        assertThat(registrar.isRunning()).isFalse();
        verify(first).cancel(false);
        verify(second).cancel(false);
        verify(third).cancel(false);

        registrar.start();
        assertThat(registrar.isRunning()).isTrue();
        registrar.stop();
        verify(fourth).cancel(false);
        verify(fifth).cancel(false);
        verify(sixth).cancel(false);
        verify(taskScheduler, times(6))
                .scheduleWithFixedDelay(
                        any(Runnable.class), eq(Duration.ofHours(1)));
    }
}
