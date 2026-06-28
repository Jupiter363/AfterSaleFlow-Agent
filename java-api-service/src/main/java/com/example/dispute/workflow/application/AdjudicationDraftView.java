package com.example.dispute.workflow.application;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;

public record AdjudicationDraftView(
        String draftId,
        String caseId,
        int draftVersion,
        String recommendedDecision,
        BigDecimal confidence,
        String draftText,
        JsonNode factFindings,
        JsonNode evidenceAssessment,
        JsonNode policyApplication,
        JsonNode reviewerAttention,
        String draftStatus) {}
