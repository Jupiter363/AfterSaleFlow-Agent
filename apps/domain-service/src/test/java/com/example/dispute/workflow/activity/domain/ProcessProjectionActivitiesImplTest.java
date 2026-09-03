package com.example.dispute.workflow.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.infrastructure.delivery.CaseEventWakeup;
import com.example.dispute.room.infrastructure.delivery.CaseEventWakeupPublisher;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionOutcome;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionResult;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProcessProjectionActivitiesImplTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-02T12:00:00Z");

    @Test
    void mapsCanonicalPrimaryCompletionResult() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        CaseEventService caseEventService = mock(CaseEventService.class);
        CaseEventWakeupPublisher wakeupPublisher = mock(CaseEventWakeupPublisher.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class),
                        completionService,
                        caseEventService,
                        wakeupPublisher);
        CompleteConsumedIntakeProjectionCommand command = command();
        stubDurableEvent(caseEventService, 41);
        when(completionService.completeConsumedEvent(command))
                .thenReturn(
                        new CompletionResult(
                                CompletionOutcome.APPLIED,
                                "target-intake-run:primary",
                                "target-intake-attempt:primary:1",
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
        assertThat(result.readyEventId()).isEqualTo(readyEventId(41));
        assertThat(result.readyEventSequence()).isEqualTo(41);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(caseEventService)
                .recordLifecycleEvent(
                        eq(command.caseId()),
                        eq(null),
                        eq("INTAKE_PROJECTION_READY"),
                        payload.capture(),
                        eq(expectedEventKey(command)),
                        eq("intake-projection-control"));
        assertThat(payload.getValue())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.ofEntries(
                                Map.entry(
                                        "schema_version",
                                        "intake-projection-ready.v1"),
                                Map.entry(
                                        "logical_run_id",
                                        "target-intake-run:primary"),
                                Map.entry(
                                        "attempt_id",
                                        "target-intake-attempt:primary:1"),
                                Map.entry("process_revision", 1L),
                                Map.entry("room_revision", 1L),
                                Map.entry("room_epoch", command.roomEpoch()),
                                Map.entry("fencing_token", command.fencingToken()),
                                Map.entry(
                                        "command_sequence",
                                        command.lastCommandSequence()),
                                Map.entry("event_id", command.eventId()),
                                Map.entry("command_admission_state", "READY")));
        verify(wakeupPublisher)
                .publish(
                        new CaseEventWakeup(
                                CaseEventWakeup.SCHEMA_VERSION, command.caseId(), 41));
    }

    @Test
    void retriesIdempotentProjectionNotificationWithTheSameEventIdentity() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        CaseEventService caseEventService = mock(CaseEventService.class);
        CaseEventWakeupPublisher wakeupPublisher = mock(CaseEventWakeupPublisher.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class),
                        completionService,
                        caseEventService,
                        wakeupPublisher);
        CompleteConsumedIntakeProjectionCommand command = command();
        stubDurableEvent(caseEventService, 42);
        when(completionService.completeConsumedEvent(command))
                .thenReturn(completion(CompletionOutcome.IDEMPOTENT_REPLAY));

        var first = activities.completeConsumedIntakeProjection(command);
        var replay = activities.completeConsumedIntakeProjection(command);

        assertThat(first.readyEventId()).isEqualTo(readyEventId(42));
        assertThat(first.readyEventSequence()).isEqualTo(42);
        assertThat(replay.readyEventId()).isEqualTo(first.readyEventId());
        assertThat(replay.readyEventSequence()).isEqualTo(first.readyEventSequence());

        verify(caseEventService, times(2))
                .recordLifecycleEvent(
                        eq(command.caseId()),
                        eq(null),
                        eq("INTAKE_PROJECTION_READY"),
                        any(),
                        eq(expectedEventKey(command)),
                        eq("intake-projection-control"));
        verify(wakeupPublisher, times(2))
                .publish(
                        new CaseEventWakeup(
                                CaseEventWakeup.SCHEMA_VERSION, command.caseId(), 42));
    }

    @Test
    void mapsAuthorityRejectionToNonRetryableActivityFailure() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        CaseEventService caseEventService = mock(CaseEventService.class);
        CaseEventWakeupPublisher wakeupPublisher = mock(CaseEventWakeupPublisher.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class),
                        completionService,
                        caseEventService,
                        wakeupPublisher);
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
        verify(caseEventService, never())
                .recordLifecycleEvent(any(), any(), any(), any(), any(), any());
        verify(wakeupPublisher, never()).publish(any());
    }

    @Test
    void propagatesDurableEventFailureSoTemporalCanRetryAfterProjectionCommit() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        CaseEventService caseEventService = mock(CaseEventService.class);
        CaseEventWakeupPublisher wakeupPublisher = mock(CaseEventWakeupPublisher.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class),
                        completionService,
                        caseEventService,
                        wakeupPublisher);
        CompleteConsumedIntakeProjectionCommand command = command();
        when(completionService.completeConsumedEvent(command))
                .thenReturn(completion(CompletionOutcome.APPLIED));
        when(
                        caseEventService.recordLifecycleEvent(
                                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("event ledger unavailable"));

        assertThatThrownBy(() -> activities.completeConsumedIntakeProjection(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event ledger unavailable");
        verify(wakeupPublisher, never()).publish(any());
    }

    @Test
    void ignoresBestEffortWakeupFailureAfterDurableEventCommit() {
        IntakeProcessProjectionCompletionService completionService =
                mock(IntakeProcessProjectionCompletionService.class);
        CaseEventService caseEventService = mock(CaseEventService.class);
        CaseEventWakeupPublisher wakeupPublisher = mock(CaseEventWakeupPublisher.class);
        ProcessProjectionActivitiesImpl activities =
                new ProcessProjectionActivitiesImpl(
                        mock(FencedProcessProjectionService.class),
                        completionService,
                        caseEventService,
                        wakeupPublisher);
        CompleteConsumedIntakeProjectionCommand command = command();
        when(completionService.completeConsumedEvent(command))
                .thenReturn(completion(CompletionOutcome.APPLIED));
        stubDurableEvent(caseEventService, 43);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(wakeupPublisher)
                .publish(any());

        var result = activities.completeConsumedIntakeProjection(command);

        assertThat(result.outcome())
                .isEqualTo(CompleteConsumedIntakeProjectionOutcome.APPLIED);
        assertThat(result.readyEventId()).isEqualTo(readyEventId(43));
        assertThat(result.readyEventSequence()).isEqualTo(43);
        verify(wakeupPublisher)
                .publish(
                        new CaseEventWakeup(
                                CaseEventWakeup.SCHEMA_VERSION, command.caseId(), 43));
    }

    private static void stubDurableEvent(
            CaseEventService caseEventService, long sequence) {
        CaseTimelineEventEntity event = mock(CaseTimelineEventEntity.class);
        when(event.getId()).thenReturn(readyEventId(sequence));
        when(event.getSequenceNo()).thenReturn(sequence);
        when(
                        caseEventService.recordLifecycleEvent(
                                any(), any(), any(), any(), any(), any()))
                .thenReturn(event);
    }

    private static String readyEventId(long sequence) {
        return "EVENT_INTAKE_PROJECTION_READY_" + sequence;
    }

    private static CompletionResult completion(CompletionOutcome outcome) {
        return new CompletionResult(
                outcome,
                "target-intake-run:primary",
                "target-intake-attempt:primary:1",
                1,
                1,
                1,
                "urn:test:intake:result",
                "b".repeat(64),
                COMPLETED_AT);
    }

    private static String expectedEventKey(CompleteConsumedIntakeProjectionCommand command) {
        return "intake-projection-ready:"
                + sha256(
                        String.join(
                                "|",
                                command.tenantSurrogate(),
                                command.caseId(),
                                command.eventId(),
                                "target-intake-run:primary",
                                "target-intake-attempt:primary:1",
                                "1",
                                "1",
                                Long.toString(command.roomEpoch()),
                                Long.toString(command.fencingToken()),
                                Long.toString(command.lastCommandSequence())));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
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
