package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessState;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessProjectionContractTest {

    private static final Instant NANOSECOND_DEADLINE =
            Instant.parse("2026-07-17T12:00:00.123456789Z");
    private static final Instant MICROSECOND_DEADLINE =
            Instant.parse("2026-07-17T12:00:00.123456Z");

    @Test
    void canonicalizesProjectionDeadlinesToPostgresqlPrecisionAtContractBoundaries() {
        ApplyProjectionCommand command =
                command("run-precision-1", "run-precision-1");
        AuthoritativeProcessState state =
                new AuthoritativeProcessState(
                        "tenant-precision",
                        "CASE_Precision",
                        "HEARING_PENDING",
                        "EVIDENCE",
                        "SEALED",
                        RoomType.EVIDENCE,
                        2,
                        6,
                        4,
                        17,
                        11,
                        20,
                        NANOSECOND_DEADLINE,
                        "case-process:precision",
                        "run-precision-1",
                        "build-precision-1",
                        "urn:test:projection:precision",
                        "b".repeat(64));

        assertThat(command.projectedDeadlineAt()).isEqualTo(MICROSECOND_DEADLINE);
        assertThat(state.projectedDeadlineAt()).isEqualTo(MICROSECOND_DEADLINE);
    }

    @Test
    void rejectsReplacingTheStableFirstExecutionRunBinding() {
        assertThatThrownBy(() -> command("run-precision-1", "run-precision-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first-execution run binding");
    }

    @Test
    void bindsConsumedIntakeCompletionToFormalEventAndTemporalRuns() {
        CompleteConsumedIntakeProjectionCommand command = consumedCommand("INTAKE_TURN_NEEDS_INPUT");
        CompleteConsumedIntakeProjectionResult result =
                new CompleteConsumedIntakeProjectionResult(
                        "complete-consumed-intake-projection-result.v1",
                        command.eventId(),
                        command.caseEventSequence(),
                        CompleteConsumedIntakeProjectionOutcome.APPLIED,
                        command.lastCommandSequence(),
                        command.processRevision(),
                        command.roomRevision(),
                        command.roomEpoch(),
                        command.fencingToken(),
                        command.temporalWorkflowId(),
                        command.firstExecutionRunId(),
                        command.activeChildRunId(),
                        "urn:test:intake:completion",
                        "c".repeat(64),
                        NANOSECOND_DEADLINE);

        assertThat(result.completedAt()).isEqualTo(MICROSECOND_DEADLINE);
        assertThat(result.firstExecutionRunId()).isEqualTo("case-run-1");
        assertThat(result.activeChildRunId()).isEqualTo("room-run-1");
    }

    @Test
    void rejectsNonFormalIntakeCompletionEvents() {
        assertThatThrownBy(() -> consumedCommand("ROOM_MESSAGE_CREATED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formal Intake turn event");
    }

    private static ApplyProjectionCommand command(
            String expectedTemporalRunId, String temporalRunId) {
        return new ApplyProjectionCommand(
                "apply-process-projection.v1",
                "projection:precision",
                "tenant-precision",
                "CASE_Precision",
                "command.precision",
                "a".repeat(64),
                RoomType.EVIDENCE,
                2,
                17,
                5,
                6,
                3,
                4,
                "HEARING_PENDING",
                "EVIDENCE",
                "SEALED",
                11,
                20,
                NANOSECOND_DEADLINE,
                "case-process:precision",
                expectedTemporalRunId,
                temporalRunId,
                "build-precision-1",
                "urn:test:projection:precision",
                "b".repeat(64));
    }

    private static CompleteConsumedIntakeProjectionCommand consumedCommand(String eventType) {
        return new CompleteConsumedIntakeProjectionCommand(
                "complete-consumed-intake-projection.v1",
                "tenant-precision",
                "CASE_Precision",
                "event.precision",
                1,
                eventType,
                1,
                0,
                17,
                1,
                1,
                "case-process:precision",
                "case-run-1",
                "room-run-1");
    }
}
