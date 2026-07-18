package com.example.dispute.workflow.infrastructure.projection;

import com.example.dispute.workflow.application.projection.ProcessProjectionReconciler;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult;
import com.example.dispute.workflow.config.ProcessProjectionReconciliationProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.orchestration.projection-reconciliation.enabled",
        havingValue = "true")
public class ProcessProjectionReconciliationScheduler {

    private final ProcessProjectionReconciler reconciler;
    private final ProcessProjectionReconciliationProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public ProcessProjectionReconciliationScheduler(
            ProcessProjectionReconciler reconciler,
            ProcessProjectionReconciliationProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    public void scheduledReconciliation() {
        runOnce();
    }

    public ReconciliationRun runOnce() {
        if (!running.compareAndSet(false, true)) {
            return new ReconciliationRun(Status.SKIPPED_ALREADY_RUNNING, List.of());
        }
        try {
            // Only the history-backed bootstrap checkpoint can currently be Verified. Later
            // workflow states remain detect-only until their complete authority proof is defined.
            return new ReconciliationRun(
                    Status.COMPLETED, reconciler.scan(properties.batchSize()));
        } finally {
            running.set(false);
        }
    }

    public record ReconciliationRun(
            Status status, List<ProcessProjectionReconciliationResult> results) {

        public ReconciliationRun {
            results = List.copyOf(results);
        }
    }

    public enum Status {
        COMPLETED,
        SKIPPED_ALREADY_RUNNING
    }
}
