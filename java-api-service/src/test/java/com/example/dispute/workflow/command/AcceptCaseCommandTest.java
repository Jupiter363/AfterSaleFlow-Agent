package com.example.dispute.workflow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AcceptCaseCommandTest {

    @Test
    void normalizesDeadlineToPostgresqlPrecision() {
        var command =
                new AcceptCaseCommand(
                        CommandType.EVIDENCE_SUBMIT,
                        RoomType.EVIDENCE,
                        0,
                        payload("urn:command:payload"),
                        0,
                        Instant.parse("2026-07-17T10:00:00.123456789Z"));

        assertThat(command.deadlineAt())
                .isEqualTo(Instant.parse("2026-07-17T10:00:00.123456Z"));
    }

    @Test
    void rejectsMalformedPayloadUrisAndCommandRoomMismatch() {
        assertThatThrownBy(
                        () ->
                                new AcceptCaseCommand(
                                        CommandType.EVIDENCE_SUBMIT,
                                        RoomType.EVIDENCE,
                                        0,
                                        payload("urn:invalid payload"),
                                        0,
                                        Instant.parse("2026-07-17T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AcceptCaseCommand(
                                        CommandType.HEARING_STATEMENT,
                                        RoomType.EVIDENCE,
                                        0,
                                        payload("urn:command:payload"),
                                        0,
                                        Instant.parse("2026-07-17T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PayloadRef payload(String uri) {
        return new PayloadRef("evidence-command.v1", uri, "a".repeat(64), 128);
    }
}
