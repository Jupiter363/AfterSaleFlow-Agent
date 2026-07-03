package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import java.time.OffsetDateTime;

public record EvidenceCompletionView(
        String caseId,
        int dossierVersion,
        ActorRole completedRole,
        boolean allPartiesCompleted,
        String nextRoom,
        OffsetDateTime nextDeadlineAt) {}
