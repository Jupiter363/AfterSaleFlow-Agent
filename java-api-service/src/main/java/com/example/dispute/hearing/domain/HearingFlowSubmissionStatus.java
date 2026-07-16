package com.example.dispute.hearing.domain;

/** Terminal state recorded inside a party action; provenance remains PARTY_ACTION. */
public enum HearingFlowSubmissionStatus {
    SUBMITTED,
    AUTO_TIMEOUT
}
