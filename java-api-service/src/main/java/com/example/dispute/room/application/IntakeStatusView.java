package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import java.time.OffsetDateTime;

public record IntakeStatusView(
        String caseId,
        ActorRole initiatorRole,
        ActorRole respondentRole,
        String initiatorStatus,
        String respondentStatus,
        boolean currentActorCompleted,
        boolean canUseIntake,
        boolean canEnterEvidence,
        OffsetDateTime evidenceDeadlineAt) {}
