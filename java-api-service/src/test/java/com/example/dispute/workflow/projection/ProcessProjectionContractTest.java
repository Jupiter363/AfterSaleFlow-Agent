package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessState;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
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
                new ApplyProjectionCommand(
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
                        "run-precision-1",
                        "run-precision-2",
                        "build-precision-1",
                        "urn:test:projection:precision",
                        "b".repeat(64));
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
                        "run-precision-2",
                        "build-precision-1",
                        "urn:test:projection:precision",
                        "b".repeat(64));

        assertThat(command.projectedDeadlineAt()).isEqualTo(MICROSECOND_DEADLINE);
        assertThat(state.projectedDeadlineAt()).isEqualTo(MICROSECOND_DEADLINE);
    }
}
