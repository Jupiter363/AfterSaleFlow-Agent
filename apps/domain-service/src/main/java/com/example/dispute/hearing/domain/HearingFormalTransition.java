package com.example.dispute.hearing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Exact V035 cursor effect committed with a formal Hearing fact. */
public record HearingFormalTransition(
        String sourceStageId,
        HearingFlowStage resultStage,
        int resultStageSequence,
        Instant sharedDeadlineAt,
        String targetStageId,
        String targetInputJson,
        String sourceOutputJson,
        String actorId) {

    public HearingFormalTransition {
        sourceStageId = HearingAuthorityExpectation.identifier(sourceStageId, "sourceStageId");
        actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
        if (resultStage == null || resultStageSequence != resultStage.ordinal() + 1) {
            throw new IllegalArgumentException("result stage must use its durable ordinal sequence");
        }
        if (sharedDeadlineAt != null) {
            sharedDeadlineAt = sharedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (resultStage.hasSharedPartyDeadline() != (sharedDeadlineAt != null)) {
            throw new IllegalArgumentException("result stage has an invalid shared deadline");
        }
        if (targetStageId != null) {
            targetStageId = HearingAuthorityExpectation.identifier(targetStageId, "targetStageId");
            targetInputJson = HearingFormalPayload.canonicalJson(targetInputJson);
            sourceOutputJson = HearingFormalPayload.canonicalJson(sourceOutputJson);
        } else if (targetInputJson != null || sourceOutputJson != null) {
            throw new IllegalArgumentException("same-stage result cannot carry a target transition");
        }
    }

    public void requireSource(HearingAuthorityExpectation authority) {
        boolean same = resultStage == authority.stage()
                && resultStageSequence == authority.stageSequence();
        boolean adjacent = resultStage.ordinal() == authority.stage().ordinal() + 1
                && resultStageSequence == authority.stageSequence() + 1;
        if (!same && !adjacent) {
            throw new IllegalArgumentException("formal result must stay on or advance one Hearing stage");
        }
        if (adjacent != (targetStageId != null)) {
            throw new IllegalArgumentException("adjacent Hearing result requires one target stage row");
        }
    }

    public boolean advances() {
        return targetStageId != null;
    }
}
