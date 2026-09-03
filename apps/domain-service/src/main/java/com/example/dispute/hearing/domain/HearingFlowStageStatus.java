package com.example.dispute.hearing.domain;

/** Lifecycle of one durable hearing_flow.v2 stage row. */
public enum HearingFlowStageStatus {
    PENDING,
    RUNNING,
    WAITING_PARTIES,
    COMPLETED,
    FAILED
}
