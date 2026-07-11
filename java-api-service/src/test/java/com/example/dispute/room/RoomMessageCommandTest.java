package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class RoomMessageCommandTest {

    @Test
    void acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract() {
        assertThatCode(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_EVIDENCE_REFERENCE,
                                        "",
                                        Collections.nCopies(50, "EVIDENCE_1")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsValuesThatCannotFitTheEvidenceEnvelopeContract() {
        assertThatThrownBy(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_TEXT,
                                        "x".repeat(2_000_001),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text exceeds transport limit");
        assertThatThrownBy(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_EVIDENCE_REFERENCE,
                                        null,
                                        Collections.nCopies(51, "EVIDENCE_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attachment references exceed transport limit");
    }
}
