package com.example.dispute.review.application;

import com.fasterxml.jackson.databind.JsonNode;

public record ReviewPacketView(
        String id,
        String caseId,
        String planId,
        int packetVersion,
        long caseVersion,
        int dossierVersion,
        int issueVersion,
        int adjudicationDraftVersion,
        int deliberationReportVersion,
        int remedyPlanVersion,
        String rulesetVersion,
        String promptVersion,
        String skillVersion,
        String profileVersion,
        String actionHash,
        JsonNode agentRunRefs,
        java.time.OffsetDateTime frozenAt,
        java.time.OffsetDateTime expiresAt,
        JsonNode caseSummary,
        JsonNode claims,
        JsonNode issues,
        JsonNode evidenceMatrix,
        JsonNode draft,
        JsonNode remedy,
        JsonNode riskFlags,
        String status) {}
