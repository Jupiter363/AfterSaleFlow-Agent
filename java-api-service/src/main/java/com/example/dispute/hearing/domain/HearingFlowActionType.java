package com.example.dispute.hearing.domain;

/** Immutable structured objects emitted or submitted during collaboration stages. */
public enum HearingFlowActionType {
    QUESTION_SET("hearing_question_set.v1", false),
    ANSWER_BUNDLE("hearing_answer_bundle.v1", true),
    EVIDENCE_REQUEST_SET("hearing_evidence_request_set.v1", false),
    EVIDENCE_BATCH("hearing_evidence_batch.v1", true);

    private final String schemaVersion;
    private final boolean partyAction;

    HearingFlowActionType(String schemaVersion, boolean partyAction) {
        this.schemaVersion = schemaVersion;
        this.partyAction = partyAction;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public boolean acceptsSchemaVersion(String candidate) {
        return schemaVersion.equals(candidate)
                || (this == ANSWER_BUNDLE
                        && "hearing_party_statement.v1".equals(candidate));
    }

    public boolean isPartyAction() {
        return partyAction;
    }
}
