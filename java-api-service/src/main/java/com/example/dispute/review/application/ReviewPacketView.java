package com.example.dispute.review.application;

import com.fasterxml.jackson.databind.JsonNode;

public record ReviewPacketView(
        String id,
        String caseId,
        String planId,
        int packetVersion,
        JsonNode caseSummary,
        JsonNode claims,
        JsonNode issues,
        JsonNode evidenceMatrix,
        JsonNode draft,
        JsonNode remedy,
        JsonNode riskFlags,
        String status) {}
