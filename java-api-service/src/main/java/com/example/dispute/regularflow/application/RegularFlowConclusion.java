package com.example.dispute.regularflow.application;

import java.util.List;

public record RegularFlowConclusion(
        String conclusionCode, String summary, List<String> recommendedActions) {}
