package com.example.dispute.evidence.application;

import java.time.OffsetDateTime;

public record EvidenceCompletionStatusView(
        String caseId,
        int dossierVersion,
        boolean userCompleted,
        boolean merchantCompleted,
        boolean sealed,
        String nextRoom,
        OffsetDateTime nextDeadlineAt) {}
