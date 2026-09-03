package com.example.dispute.evidence.domain;

/** Durable parser-delivery lifecycle; IN_FLIGHT may be reclaimed after its lease expires. */
public enum EvidenceParseOutboxStatus {
    PENDING,
    IN_FLIGHT,
    APPLIED,
    FAILED
}
