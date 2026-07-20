package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Framework-free Activity boundary; runtime wiring remains intentionally absent in Phase 4. */
public final class IntakeFinalizationActivityBridge {

    private final IntakeGraphResultFinalizer finalizer;

    public IntakeFinalizationActivityBridge(IntakeGraphResultFinalizer finalizer) {
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    }

    public IntakeFinalizationReceipt finalizeResult(IntakeGraphFinalizationRequest request) {
        try {
            return finalizer.finalizeResult(request);
        } catch (ApplicationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw IntakeActivityFailureMapper.toApplicationFailure(failure);
        }
    }
}
