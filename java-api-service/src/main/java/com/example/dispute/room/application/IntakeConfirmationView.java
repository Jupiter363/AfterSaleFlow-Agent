package com.example.dispute.room.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.room.domain.RoomType;
import java.time.OffsetDateTime;

public record IntakeConfirmationView(
        String caseId,
        CaseStatus caseStatus,
        RoomType currentRoom,
        OffsetDateTime deadlineAt) {}
