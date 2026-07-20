package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

class IntakeActivityFailureMapperTest {

    @Test
    void persistenceResourceFailureRemainsRetryable() {
        ApplicationFailure failure = IntakeActivityFailureMapper.toApplicationFailure(
                new IntakeFinalizationPersistenceException(
                        "database unavailable", new IllegalStateException("connection lost")));

        assertThat(failure.getType())
                .isEqualTo(IntakeActivityFailureMapper.RETRYABLE_FINALIZATION_PERSISTENCE);
        assertThat(failure.isNonRetryable()).isFalse();
    }

    @Test
    void mapsFormalRejectionsToNonRetryableApplicationFailures() {
        ApplicationFailure failure = IntakeActivityFailureMapper.toApplicationFailure(
                new IntakeFinalizationRejectedException(
                        "INTAKE_PROPOSAL_OBJECT_NOT_FOUND",
                        "proposal object does not exist"));

        assertThat(failure.getType()).isEqualTo("INTAKE_PROPOSAL_OBJECT_NOT_FOUND");
        assertThat(failure.isNonRetryable()).isTrue();
    }

    @Test
    void leavesExplicitTransientProposalAccessRetryable() {
        ApplicationFailure failure = IntakeActivityFailureMapper.toApplicationFailure(
                new IntakeProposalLoadException(
                        "proposal store timed out", new IllegalStateException("timeout")));

        assertThat(failure.getType())
                .isEqualTo(IntakeActivityFailureMapper.RETRYABLE_PROPOSAL_ACCESS);
        assertThat(failure.isNonRetryable()).isFalse();
    }

    @Test
    void failsClosedForUnclassifiedRuntimeFailures() {
        ApplicationFailure failure = IntakeActivityFailureMapper.toApplicationFailure(
                new IllegalStateException("unexpected SDK failure"));

        assertThat(failure.getType())
                .isEqualTo(IntakeActivityFailureMapper.UNCLASSIFIED_FINALIZATION_FAILURE);
        assertThat(failure.isNonRetryable()).isTrue();
    }
}
