package com.example.dispute.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record HearingAgentResult(
        JsonNode raw,
        boolean requiresAdditionalEvidence,
        boolean manualRequired,
        List<String> executedNodes,
        String promptVersion,
        String model) {}
