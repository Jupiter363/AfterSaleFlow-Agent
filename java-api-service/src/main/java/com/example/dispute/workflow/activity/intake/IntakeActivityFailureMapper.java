package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Converts typed formal-boundary failures into executable Temporal retry semantics. */
public final class IntakeActivityFailureMapper {

    public static final String RETRYABLE_PROPOSAL_ACCESS = "IntakeProposalAccessRetryable";
    public static final String UNCLASSIFIED_FINALIZATION_FAILURE =
            "IntakeFinalizationUnclassified";

    private IntakeActivityFailureMapper() {}

    public static ApplicationFailure toApplicationFailure(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof ApplicationFailure applicationFailure) {
            return applicationFailure;
        }
        if (failure instanceof IntakeProposalLoadException) {
            return ApplicationFailure.newFailureWithCause(
                    "Intake proposal access is temporarily unavailable",
                    RETRYABLE_PROPOSAL_ACCESS,
                    failure,
                    failure.getClass().getSimpleName());
        }
        if (failure instanceof IntakeFinalizationRejectedException rejected) {
            return ApplicationFailure.newNonRetryableFailureWithCause(
                    rejected.getMessage(), rejected.code(), rejected, rejected.code());
        }
        return ApplicationFailure.newNonRetryableFailureWithCause(
                "Intake finalization failed without an explicit retry classification",
                UNCLASSIFIED_FINALIZATION_FAILURE,
                failure,
                failure.getClass().getName());
    }
}
