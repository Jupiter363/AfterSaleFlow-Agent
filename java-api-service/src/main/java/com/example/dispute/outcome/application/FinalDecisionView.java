package com.example.dispute.outcome.application;

import com.fasterxml.jackson.databind.JsonNode;

public record FinalDecisionView(
        String conclusion,
        String explanation,
        String reviewReason,
        String source,
        boolean humanConfirmed,
        JsonNode approvedPlan) {}
