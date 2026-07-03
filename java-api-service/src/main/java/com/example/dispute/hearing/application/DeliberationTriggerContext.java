package com.example.dispute.hearing.application;

import com.example.dispute.domain.model.RiskLevel;

public record DeliberationTriggerContext(
        RiskLevel riskLevel,
        boolean settlementConfirmed,
        double confidence,
        boolean majorEvidenceConflict,
        boolean ruleUncertain) {}
