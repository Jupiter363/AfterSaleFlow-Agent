package com.example.dispute.hearing.domain;

/** Overall lifecycle of one hearing_flow.v2 instance. */
public enum HearingFlowStatus {
    ACTIVE,
    HUMAN_REVIEW,
    CLOSED,
    FAILED
}
