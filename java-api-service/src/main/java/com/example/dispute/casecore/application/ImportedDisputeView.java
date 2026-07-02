package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.CaseStatus;
import java.time.OffsetDateTime;

public record ImportedDisputeView(
        String id,
        String sourceType,
        String sourceSystem,
        String externalCaseReference,
        CaseStatus caseStatus,
        String currentRoom,
        OffsetDateTime currentDeadlineAt) {}
