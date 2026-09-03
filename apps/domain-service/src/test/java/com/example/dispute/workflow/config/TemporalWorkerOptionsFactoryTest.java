package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static io.temporal.common.VersioningBehavior.PINNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.workflow.config.TemporalWorkerProperties.QueueCapacity;
import com.example.dispute.workflow.config.TemporalWorkerProperties.VersioningMode;
import com.example.dispute.workflow.config.TemporalWorkerProperties.WorkerRole;
import com.example.dispute.workflow.observability.TemporalTracingWorkerInterceptor;
import org.junit.jupiter.api.Test;

class TemporalWorkerOptionsFactoryTest {

    @Test
    void configuresLegacyBuildIdRoutingWithoutLosingQueueCapacityBounds() {
        TemporalWorkerOptionsFactory factory =
                new TemporalWorkerOptionsFactory(properties(VersioningMode.BUILD_ID));

        var options = factory.workerOptions(AGENT_EXECUTION);

        assertThat(options.isUsingBuildIdForVersioning()).isTrue();
        assertThat(options.getBuildId()).isEqualTo("after-sale-control.git-81d2969a");
        assertThat(options.getMaxConcurrentActivityExecutionSize()).isEqualTo(96);
        assertThat(options.getMaxConcurrentActivityTaskPollers()).isEqualTo(8);
        assertThat(options.isUsingVirtualThreadsOnActivityWorker()).isTrue();
    }

    @Test
    void configuresPinnedWorkerDeploymentVersioning() {
        TemporalWorkerOptionsFactory factory =
                new TemporalWorkerOptionsFactory(properties(VersioningMode.DEPLOYMENT));

        var options = factory.workerOptions(CASE_CONTROL);

        assertThat(options.isUsingBuildIdForVersioning()).isFalse();
        assertThat(options.getDeploymentOptions().isUsingVersioning()).isTrue();
        assertThat(options.getDeploymentOptions().getVersion().getDeploymentName())
                .isEqualTo("after-sale-control");
        assertThat(options.getDeploymentOptions().getVersion().getBuildId())
                .isEqualTo("git-81d2969a");
        assertThat(options.getDeploymentOptions().getDefaultVersioningBehavior())
                .isEqualTo(PINNED);
        assertThat(factory.factoryOptions().getMaxWorkflowThreadCount()).isEqualTo(512);
    }

    @Test
    void registersTheTracingInterceptorAtWorkerFactoryScope() {
        TemporalTracingWorkerInterceptor interceptor =
                mock(TemporalTracingWorkerInterceptor.class);
        TemporalWorkerOptionsFactory factory =
                new TemporalWorkerOptionsFactory(
                        properties(VersioningMode.NONE), interceptor);

        assertThat(factory.factoryOptions().getWorkerInterceptors())
                .containsExactly(interceptor);
    }

    private static TemporalWorkerProperties properties(VersioningMode versioningMode) {
        QueueCapacity control = new QueueCapacity(64, 32, 4, 4, 0);
        QueueCapacity room = new QueueCapacity(128, 16, 8, 2, 0);
        QueueCapacity agent = new QueueCapacity(8, 96, 2, 8, 0);
        QueueCapacity tools = new QueueCapacity(8, 32, 2, 4, 0);
        return new TemporalWorkerProperties(
                true,
                WorkerRole.CONTROL,
                versioningMode,
                "after-sale-control",
                "git-81d2969a",
                512,
                control,
                room,
                agent,
                tools);
    }
}
