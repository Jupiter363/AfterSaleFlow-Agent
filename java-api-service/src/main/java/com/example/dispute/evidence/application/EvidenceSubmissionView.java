package com.example.dispute.evidence.application;

import com.example.dispute.room.application.RoomMessageView;
import java.time.Instant;
import java.util.List;

public record EvidenceSubmissionView(
        String batchId,
        String caseId,
        String actorRole,
        String actorId,
        List<String> evidenceIds,
        String batchNote,
        String submitStatus,
        Instant submittedAt,
        RoomMessageView roomMessage) {}
