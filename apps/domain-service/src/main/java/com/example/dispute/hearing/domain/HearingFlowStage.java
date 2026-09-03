package com.example.dispute.hearing.domain;

/** Durable stages in the hearing_flow.v2 state machine. */
public enum HearingFlowStage {
    COURT_PREPARING(false),
    CASE_INTRODUCTION(false),
    EVIDENCE_INTRODUCTION(false),
    INTAKE_QUESTIONS_GENERATING(false),
    PARTY_ANSWERS_OPEN(true),
    INTAKE_SYNTHESIZING(false),
    EVIDENCE_REQUESTS_GENERATING(false),
    PARTY_EVIDENCE_OPEN(true),
    EVIDENCE_SYNTHESIZING(false),
    DOSSIER_FREEZING(false),
    JUDGE_V1_GENERATING(false),
    JURY_REVIEWING(false),
    JUDGE_V2_GENERATING(false),
    HUMAN_REVIEW_OPEN(false),
    CLOSED(false);

    private final boolean partyDeadlineStage;

    HearingFlowStage(boolean partyDeadlineStage) {
        this.partyDeadlineStage = partyDeadlineStage;
    }

    public boolean hasSharedPartyDeadline() {
        return partyDeadlineStage;
    }
}
