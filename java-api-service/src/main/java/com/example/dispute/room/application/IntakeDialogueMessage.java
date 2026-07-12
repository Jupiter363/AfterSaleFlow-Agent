package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntakeDialogueMessage(
        @JsonProperty("message_id") String messageId,
        @JsonProperty("sequence_no") long sequenceNo,
        String role,
        String source,
        String text) {}
