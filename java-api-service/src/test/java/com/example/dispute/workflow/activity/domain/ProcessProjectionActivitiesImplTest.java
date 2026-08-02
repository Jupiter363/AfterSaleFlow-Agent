package com.example.dispute.workflow.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionOutcome;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionResult;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessProjectionActivitiesImplTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-02T12:00:00Z");

    @Test
    void mapsCanonicalPrimaryCompletionResult() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class), completionService);
        CompleteConsumedIntakeProjectionCommand command = command();
        when(completionService.completeConsumedEvent(command))
                .thenReturn(
                        new CompletionResult(
                                CompletionOutcome.APPLIED,
                                1,
                                1,
                                1,
                                "urn:test:intake:result",
                                "b".repeat(64),
                                COMPLETED_AT));

        var result = activities.completeConsumedIntakeProjection(command);

        assertThat(result.outcome())
                .isEqualTo(CompleteConsumedIntakeProjectionOutcome.APPLIED);
        assertThat(result.eventId()).isEqualTo(command.eventId());
        assertThat(result.processRevision()).isEqualTo(1);
        assertThat(result.roomRevision()).isEqualTo(1);
        assertThat(result.firstExecutionRunId()).isEqualTo(command.firstExecutionRunId());
        assertThat(result.activeChildRunId()).isEqualTo(command.activeChildRunId());
    }

    @Test
    void mapsAuthorityRejectionToNonRetryableActivityFailure() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class), completionService);
        CompleteConsumedIntakeProjectionCommand command = command();
        when(completionService.completeConsumedEvent(command))
                .thenThrow(
                        new ProjectionWriteRejectedException(
                                "INTAKE_PROJECTION_TEMPORAL_AUTHORITY_MISMATCH",
                                "authority mismatch"));

        assertThatThrownBy(() -> activities.completeConsumedIntakeProjection(command))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure -> {
                            ApplicationFailure application = (ApplicationFailure) failure;
                            assertThat(application.getType())
                                    .isEqualTo(
                                            "INTAKE_PROJECTION_TEMPORAL_AUTHORITY_MISMATCH");
                            assertThat(application.isNonRetryable()).isTrue();
                        });
    }

    private static CompleteConsumedIntakeProjectionCommand command() {
        return new CompleteConsumedIntakeProjectionCommand(
                "complete-consumed-intake-projection.v1",
                "tenant-primary",
                "CASE_Primary",
                "event.primary",
                1,
                "INTAKE_TURN_NEEDS_INPUT",
                1,
                0,
                9,
                1,
                1,
                "case-process:tenant-primary:CASE_Primary",
                "case-run-primary",
                "room-run-primary");
    }
}
