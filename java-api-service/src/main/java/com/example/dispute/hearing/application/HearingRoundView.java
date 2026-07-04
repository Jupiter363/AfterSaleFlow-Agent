package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import java.time.Instant;
import java.util.List;

public record HearingRoundView(
        String roundId,
        String caseId,
        int roundNo,
        HearingRoundStatus status,
        int dossierVersion,
        HearingStopReason stopReason,
        String summaryJson,
        Instant openedAt,
        Instant roundDeadlineAt,
        List<ActorRole> submittedRoles,
        boolean currentActorSubmitted,
        Instant closedAt) {}
