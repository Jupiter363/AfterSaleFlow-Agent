package com.example.dispute.room.application;

import java.time.Instant;

public record CaseEventView(
        long sequenceNo,
        String eventType,
        String roomId,
        String payloadJson,
        Instant eventTime) {}
