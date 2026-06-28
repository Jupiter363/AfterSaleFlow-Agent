package com.example.dispute.workflow.application;

import java.math.BigDecimal;

public record AdjudicationDraftView(
        String draftId,
        String caseId,
        int draftVersion,
        String recommendedDecision,
        BigDecimal confidence,
        String draftText,
        String draftStatus) {}
