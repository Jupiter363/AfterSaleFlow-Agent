package com.example.dispute.review.domain;

/** Distinguishes a human decision from a system SLA escalation fact. */
public enum ReviewDecisionFactType {
    HUMAN_DECISION,
    SYSTEM_SLA_ESCALATION
}
