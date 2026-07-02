package com.example.dispute.workflow.domain;

public record FrozenDeliberationSnapshot(
        String caseId,
        long caseSnapshotVersion,
        long dossierVersion,
        long draftVersion,
        String ruleVersion,
        long remedyPlanVersion,
        String fingerprint) {}
