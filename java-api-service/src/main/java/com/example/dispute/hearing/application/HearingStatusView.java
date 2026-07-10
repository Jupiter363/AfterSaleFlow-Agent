package com.example.dispute.hearing.application;

import java.time.Instant;

public record HearingStatusView(
        String caseId,
        String hearingPhase,
        String phaseLabel,
        String nextStepHint,
        boolean canCompleteHearing,
        boolean reviewGateReady,
        String latestDraftId,
        String reviewTaskId,
        Integer currentRoundNo,
        String roundStage,
        String currentRoundStatus,
        Instant roundDeadlineAt,
        boolean finalRoundSealed) {}
