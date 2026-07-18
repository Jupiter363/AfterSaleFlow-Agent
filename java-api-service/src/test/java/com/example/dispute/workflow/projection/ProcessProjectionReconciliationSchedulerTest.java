package com.example.dispute.workflow.projection;

import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.DRIFT_DETECTED;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.SOURCE_INCOMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.projection.ProcessProjectionReconciler;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult;
import com.example.dispute.workflow.config.ProcessProjectionReconciliationProperties;
import com.example.dispute.workflow.infrastructure.projection.ProcessProjectionReconciliationScheduler;
import com.example.dispute.workflow.infrastructure.projection.ProcessProjectionReconciliationScheduler.Status;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessProjectionReconciliationSchedulerTest {

    @Mock private ProcessProjectionReconciler reconciler;

    @Test
    void invokesTheControlledScanWithTheConfiguredBatchLimit() {
        ProcessProjectionReconciliationScheduler scheduler = scheduler(7);
        List<ProcessProjectionReconciliationResult> results =
                List.of(
                        result(DRIFT_DETECTED, "SHADOW_PROJECTION_DRIFT"),
                        result(
                                SOURCE_INCOMPLETE,
                                "CASE_PROCESS_SNAPSHOT_V1_INCOMPLETE_FOR_REPAIR"));
        when(reconciler.scan(7)).thenReturn(results);

        var run = scheduler.runOnce();

        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(run.results()).containsExactlyElementsOf(results);
        verify(reconciler).scan(7);
    }

    @Test
    void rejectsAnOverlappingRunWithinTheSameServiceInstance() throws Exception {
        ProcessProjectionReconciliationScheduler scheduler = scheduler(5);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reconciler.scan(5))
                .thenAnswer(
                        ignored -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test scan was not released");
                            }
                            return List.of();
                        });

        CompletableFuture<ProcessProjectionReconciliationScheduler.ReconciliationRun> first =
                CompletableFuture.supplyAsync(scheduler::runOnce);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var overlapping = scheduler.runOnce();
        release.countDown();
        var completed = first.get(5, TimeUnit.SECONDS);

        assertThat(overlapping.status()).isEqualTo(Status.SKIPPED_ALREADY_RUNNING);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        verify(reconciler).scan(5);
    }

    private ProcessProjectionReconciliationScheduler scheduler(int batchSize) {
        return new ProcessProjectionReconciliationScheduler(
                reconciler,
                new ProcessProjectionReconciliationProperties(
                        true,
                        batchSize,
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(30)));
    }

    private static ProcessProjectionReconciliationResult result(
            ProcessProjectionReconciliationResult.Outcome outcome, String reasonCode) {
        return new ProcessProjectionReconciliationResult(outcome, reasonCode, null, 5, 6);
    }
}
