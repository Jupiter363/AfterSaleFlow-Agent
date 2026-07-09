package com.example.dispute.outcome.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

public record AdjudicationDraftView(
        String id,
        int draftVersion,
        String recommendedDecision,
        BigDecimal confidence,
        String draftText,
        String draftStatus,
        JsonNode factFindings,
        JsonNode evidenceAssessment,
        JsonNode policyApplication,
        JsonNode reviewerAttention) {}
