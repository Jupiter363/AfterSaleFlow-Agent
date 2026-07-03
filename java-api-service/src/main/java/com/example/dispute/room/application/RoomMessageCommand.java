package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import java.util.List;
import java.util.Objects;

public record RoomMessageCommand(
        MessageType messageType,
        String text,
        List<String> attachmentRefs) {

    public RoomMessageCommand {
        Objects.requireNonNull(messageType, "messageType must not be null");
        if ((text == null || text.isBlank())
                && (attachmentRefs == null || attachmentRefs.isEmpty())) {
            throw new IllegalArgumentException("message requires text or attachment references");
        }
        attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
    }
}
