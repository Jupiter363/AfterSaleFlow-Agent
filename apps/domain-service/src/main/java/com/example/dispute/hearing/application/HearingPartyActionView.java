package com.example.dispute.hearing.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Durable acknowledgement for one party terminal action. */
public record HearingPartyActionView(
        String actionId,
        String schemaVersion,
        String participantId,
        String participantRole,
        String submissionStatus,
        Instant submittedAt,
        JsonNode payload,
        RoomMessageAcknowledgement roomMessage) {

    public record RoomMessageAcknowledgement(
            String id,
            long sequenceNo,
            String senderRole,
            String messageType,
            String messageText,
            JsonNode attachmentRefs,
            Instant createdAt) {}
}
