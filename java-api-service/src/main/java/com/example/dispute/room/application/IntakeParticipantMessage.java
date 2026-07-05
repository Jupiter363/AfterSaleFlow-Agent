package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntakeParticipantMessage(
        @JsonProperty("message_id") String messageId,
        String role,
        String text) {}
