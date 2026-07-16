package com.example.dispute.hearing.domain;

/** Immutable adjudication artifacts produced after trial_dossier.v1 is frozen. */
public enum HearingArtifactType {
    JUDGE_PROPOSAL("judge_proposal.v1"),
    JURY_REVIEW_REPORT("jury_review_report.v1"),
    ADJUDICATION_DRAFT("adjudication_draft.v2");

    private final String schemaVersion;

    HearingArtifactType(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String schemaVersion() {
        return schemaVersion;
    }
}
