package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import java.time.Instant;
import java.util.List;

public record RoomMessageView(
        String id,
        String caseId,
        String roomId,
        long sequenceNo,
        String senderRole,
        String senderId,
        MessageType messageType,
        String messageText,
        List<String> attachmentRefs,
        Integer hearingRound,
        Instant createdAt) {}
