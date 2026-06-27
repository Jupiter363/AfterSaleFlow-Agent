package com.example.dispute.domain.model;

public enum CaseStatus {
    INTAKE_PENDING,
    INTAKE_COMPLETED,
    DOSSIER_BUILDING,
    DOSSIER_BUILT,
    ROUTED,
    HEARING,
    WAITING_EVIDENCE,
    WAITING_HUMAN_REVIEW,
    APPROVED_FOR_EXECUTION,
    EXECUTING,
    CLOSED,
    CANCELLED
}
