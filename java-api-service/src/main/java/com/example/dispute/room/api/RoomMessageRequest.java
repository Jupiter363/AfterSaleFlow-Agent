package com.example.dispute.room.api;

import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RoomMessageRequest(
        @NotNull MessageType messageType,
        String text,
        List<String> attachmentRefs) {

    RoomMessageCommand toCommand() {
        return new RoomMessageCommand(messageType, text, attachmentRefs);
    }
}
