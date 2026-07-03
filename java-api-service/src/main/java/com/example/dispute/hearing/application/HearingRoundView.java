package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import java.time.Instant;

public record HearingRoundView(
        String roundId,
        String caseId,
        int roundNo,
        HearingRoundStatus status,
        int dossierVersion,
        HearingStopReason stopReason,
        String summaryJson,
        Instant openedAt,
        Instant closedAt) {}
