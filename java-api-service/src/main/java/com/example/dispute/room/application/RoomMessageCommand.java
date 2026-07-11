package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import java.util.List;
import java.util.Objects;

public record RoomMessageCommand(
        MessageType messageType,
        String text,
        List<String> attachmentRefs) {

    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final int MAX_ATTACHMENT_REFS = 50;

    public RoomMessageCommand {
        Objects.requireNonNull(messageType, "messageType must not be null");
        if ((text == null || text.isBlank())
                && (attachmentRefs == null || attachmentRefs.isEmpty())) {
            throw new IllegalArgumentException("message requires text or attachment references");
        }
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("message text exceeds transport limit");
        }
        if (attachmentRefs != null && attachmentRefs.size() > MAX_ATTACHMENT_REFS) {
            throw new IllegalArgumentException("message attachment references exceed transport limit");
        }
        attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
    }
}
